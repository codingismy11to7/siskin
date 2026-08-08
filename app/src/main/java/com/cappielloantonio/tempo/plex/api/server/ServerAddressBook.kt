package com.cappielloantonio.tempo.plex.api.server

import android.os.SystemClock
import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.models.Resource
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val TAG = "ServerAddressBook"

/**
 * The one authority on how to reach the current Plex server.
 *
 * A server advertises several addresses and which one works is a property of
 * where the car is, not of the server -- so the address chosen at sign-in stops
 * working the moment the car leaves that network. This holds every address the
 * server advertises beside the one currently in use, so recovering is a race
 * against a list already in hand rather than a trip back through sign-in.
 *
 * Call sites must use [shared] rather than constructing their own -- see its
 * KDoc for why. The constructor stays public only so tests can build isolated
 * instances with stubs and a fake clock.
 *
 * See docs/decisions/2026-08-08-server-address-book-design.md.
 */
class ServerAddressBook(
    private val api: PlexApi = PlexApi(),
    private val probe: ServerProbe = ServerProbe(),
    private val authClient: AuthClient = AuthClient(api),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {

    /**
     * Not reentrant, and nothing reachable from [probe] or [authClient] calls
     * back into [reprobe] today -- but if that ever changes, the outcome is a
     * deadlock, not a recursion: kotlinx's Mutex has no notion of "the same
     * caller already owns this", so a re-entrant lock attempt just suspends
     * forever waiting on itself. The obvious way to introduce this is a future
     * `withAddressRecovery` wrapped around AuthClient's own calls: that would
     * route refreshFromPlexTv -> authClient.getResources() -> reprobe back into
     * this same lock and hang the browse path permanently. withAddressRecovery
     * must never wrap plex.tv calls for exactly this reason.
     */
    private val mutex = Mutex()

    /**
     * Elapsed-realtime of the last total failure, or null when there has not
     * been one (or the most recent race succeeded). Nullable rather than a
     * `0L` sentinel so a clock that legitimately starts at or returns zero --
     * as a test's fake clock does -- can never be mistaken for "no failure
     * recorded yet"; the two domains overlapping is exactly what made an
     * earlier version of the cooldown test vacuous.
     */
    private var lastFailureAt: Long? = null

    /** The address every server request uses, or null without a session. */
    fun current(): String? = api.session?.serverUri

    /**
     * Records a server's full address list beside the address just chosen.
     *
     * Called from the two places that write a session -- sign-in and the More
     * tab's library picker -- both of which already hold the Resource and the
     * probed URI, so this adds no network call.
     */
    fun adopt(resource: Resource, uri: String) {
        val machineIdentifier = resource.clientIdentifier
        if (machineIdentifier.isNullOrBlank()) {
            // Without a stamp the list cannot be proven to belong to the session's
            // server, and racing another server's addresses is worse than having
            // no list at all -- a re-probe falls back to plex.tv.
            Log.d(TAG, "resource has no clientIdentifier; not storing its addresses")
            api.serverCandidates = null
            return
        }
        store(machineIdentifier, ServerProbe.candidates(resource))
        Log.d(TAG, "adopted $uri for $machineIdentifier")
    }

    /**
     * Re-races the server's known addresses and adopts whichever answers.
     *
     * [staleAddress] is the address the caller found not working. It is what
     * makes concurrent callers collapse: four browse tabs that all failed
     * against the same address produce one race, and the three that arrive
     * after it get the winner without probing. Required rather than nullable:
     * a caller with no failure of its own -- the network-change callback and
     * the player-error handler wire in this way -- passes `current()`
     * instead, so it still collapses against whatever every other caller is
     * racing. There is no caller for which passing nothing is meaningful.
     *
     * Returns the address now in use, or null when nothing answered, or when
     * the session changed out from under a race already in flight (see
     * [adoptAddress]).
     */
    suspend fun reprobe(staleAddress: String): String? = mutex.withLock {
        val current = current()
        if (current != staleAddress) {
            Log.d(TAG, "address already moved to $current")
            return@withLock current
        }

        val session = api.session ?: return@withLock null

        val lastFailure = lastFailureAt
        if (lastFailure != null && clock() - lastFailure < FAILURE_COOLDOWN_MS) {
            // A car with no usable network would otherwise pay a full race per
            // browse tab, serially, for as long as it stayed offline.
            Log.d(TAG, "in cooldown; not re-probing")
            return@withLock null
        }

        storedCandidates(session.machineIdentifier)?.let { stored ->
            probe.bestOf(stored)?.let { return@withLock adoptAddress(session, it) }
            Log.d(TAG, "no stored address answered; asking plex.tv for a fresh list")
        }

        refreshFromPlexTv(session.machineIdentifier)?.let { refreshed ->
            probe.bestOf(refreshed)?.let { return@withLock adoptAddress(session, it) }
        }

        Log.d(TAG, "nothing answered for ${session.machineIdentifier}")
        lastFailureAt = clock()
        null
    }

    /**
     * Moves the session onto [uri], leaving everything else in it untouched --
     * or refuses to write at all when the session moved out from under this
     * race. Returns null in that case; the caller's retry would not be
     * meaningful either, since whatever now holds the session already
     * superseded what this race was run for.
     *
     * [mutex] serialises [reprobe] against itself, but a race spends real
     * wall-clock time in network I/O while holding it, and nothing serialises
     * that window against the app's other two session writers -- sign-in and
     * the library picker -- or against sign-out. [session] is what this race
     * was started against, captured before the network calls; re-reading
     * [PlexApi.session] here and comparing machineIdentifier is what stops a
     * race that started before a sign-out from writing the signed-out
     * account's token back, or one that started before a server switch from
     * overwriting the server the user switched to.
     *
     * The write itself is built from the freshly re-read session, not the
     * captured one: if a library picker landed mid-race on the *same* server
     * (same machineIdentifier, different section), copying from the stale
     * captured session would silently revert that choice. Only serverUri
     * moves; machineIdentifier, the section key and the server token all
     * carry forward from whichever session is current now. Rebuilding the
     * whole session here, from either copy, would be the mixed-set hazard
     * PlexSession's KDoc describes.
     */
    private fun adoptAddress(session: PlexSession, uri: String): String? {
        val latest = api.session
        if (latest == null || latest.machineIdentifier != session.machineIdentifier) {
            Log.d(TAG, "session changed during the race; discarding $uri")
            return null
        }
        api.session = latest.copy(serverUri = uri)
        lastFailureAt = null
        Log.d(TAG, "re-probed onto $uri")
        return uri
    }

    /**
     * A fresh address list from plex.tv, or null when plex.tv cannot be reached
     * or no longer lists this server.
     *
     * Outside any either { } block, so nothing here can swallow a raise.
     */
    private suspend fun refreshFromPlexTv(machineIdentifier: String?): ServerProbe.Candidates? {
        if (machineIdentifier.isNullOrBlank()) return null

        val resources = fetchResources() ?: return null
        val resource = AuthClient.mediaServers(resources)
            .firstOrNull { it.clientIdentifier == machineIdentifier } ?: run {
            Log.d(TAG, "plex.tv no longer lists $machineIdentifier")
            return null
        }

        val candidates = ServerProbe.candidates(resource)
        store(machineIdentifier, candidates)
        return candidates
    }

    /**
     * [authClient] runs through PlexRetrofitFactory's shared client, whose
     * timeouts (20s connect, 1 minute call) exist for an ordinary server call,
     * not for a leg that runs inside [mutex]. Without a bound of its own, a
     * captive-portal network that accepts a TCP connection and never replies
     * would hold the lock -- and every browse tab queued behind it -- for up
     * to a minute, reproducing the hang ServerProbe's own short probe timeout
     * exists to avoid.
     *
     * The `catch` below is safe specifically because nothing in this class
     * runs inside an either { } block. Arrow's raise unwinds an either by
     * throwing a CancellationException subclass, which on the JVM extends
     * IllegalStateException, so a broad catch lexically inside one would
     * swallow it. TimeoutCancellationException is that same family, but nothing
     * here can raise, so there is no raise for this to swallow -- it is a
     * plain coroutine timeout being turned into "no fresh list", the same
     * outcome as plex.tv being unreachable outright.
     */
    private suspend fun fetchResources(): List<Resource>? = try {
        withTimeout(PLEX_TV_TIMEOUT_MS) { authClient.getResources() }.getOrNull().also {
            if (it == null) Log.d(TAG, "could not reach plex.tv for a fresh address list")
        }
    } catch (e: TimeoutCancellationException) {
        Log.d(TAG, "plex.tv did not answer within ${PLEX_TV_TIMEOUT_MS}ms")
        null
    }

    /** The stored list, or null when absent, unreadable, or stamped for another server. */
    internal fun storedCandidates(machineIdentifier: String?): ServerProbe.Candidates? {
        if (machineIdentifier.isNullOrBlank()) return null
        val raw = api.serverCandidates ?: return null
        val stored = try {
            gson.fromJson(raw, StoredCandidates::class.java)
        } catch (e: JsonSyntaxException) {
            // Outside any either { }, so this swallows no raise. A blob written
            // by an older build is a cache miss, not a crash.
            Log.w(TAG, "could not read stored addresses", e)
            null
        } ?: return null

        if (stored.machineIdentifier != machineIdentifier) {
            Log.d(TAG, "stored addresses belong to ${stored.machineIdentifier}, not $machineIdentifier")
            return null
        }
        return ServerProbe.Candidates(stored.direct, stored.relay)
    }

    private fun store(machineIdentifier: String, candidates: ServerProbe.Candidates) {
        api.serverCandidates = gson.toJson(
            StoredCandidates(machineIdentifier, candidates.direct, candidates.relay)
        )
    }

    /** The persisted shape. Separate from ServerProbe.Candidates so the stamp travels with it. */
    private data class StoredCandidates(
        val machineIdentifier: String,
        val direct: List<String>,
        val relay: List<String>
    )

    companion object {
        private val gson = Gson()

        /**
         * How long a total failure suppresses another race. Arbitrary, and
         * flagged as such in the spec -- it exists so an offline car does not
         * pay a full round of timeouts per browse tab.
         */
        private const val FAILURE_COOLDOWN_MS = 10_000L

        /**
         * Bounds the whole plex.tv leg of a re-probe. Comfortably under
         * [FAILURE_COOLDOWN_MS] and well over a healthy round trip; see
         * [fetchResources] for why this exists at all.
         */
        private const val PLEX_TV_TIMEOUT_MS = 10_000L

        /**
         * The instance every app-side call site must use.
         *
         * Collapsing concurrent callers into one race and honouring the
         * failure cooldown are properties of a single instance's [mutex] and
         * [lastFailureAt] -- a call site that builds its own
         * `ServerAddressBook()` instead gets its own mutex and a fresh
         * cooldown clock, silently defeating both for that path alone: an
         * offline car would then pay the per-tab round of timeouts the
         * cooldown exists to prevent, once per call site that opted out.
         * Lazily built with production defaults so it is cheap to reference
         * before a session exists.
         */
        val shared: ServerAddressBook by lazy { ServerAddressBook() }
    }
}
