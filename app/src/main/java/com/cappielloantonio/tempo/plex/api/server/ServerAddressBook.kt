package com.cappielloantonio.tempo.plex.api.server

import android.os.SystemClock
import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.models.Resource
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * See docs/decisions/2026-08-08-server-address-book-design.md.
 */
class ServerAddressBook(
    private val api: PlexApi = PlexApi(),
    private val probe: ServerProbe = ServerProbe(),
    private val authClient: AuthClient = AuthClient(api),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private val mutex = Mutex()
    private var lastFailureAt = 0L

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
     * after it get the winner without probing.
     *
     * Returns the address now in use, or null when nothing answered.
     */
    suspend fun reprobe(staleAddress: String?): String? = mutex.withLock {
        val current = current()
        if (current != staleAddress) {
            Log.d(TAG, "address already moved to $current")
            return@withLock current
        }

        val session = api.session ?: return@withLock null

        if (lastFailureAt != 0L && clock() - lastFailureAt < FAILURE_COOLDOWN_MS) {
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
     * Moves the session onto [uri], leaving everything else in it untouched.
     *
     * The copy is deliberate and load-bearing: machineIdentifier, the section
     * key and the server token all stay, because this is the same server at a
     * different address. Rebuilding the whole session here would be the mixed-set
     * hazard PlexSession's KDoc describes.
     */
    private fun adoptAddress(session: PlexSession, uri: String): String {
        api.session = session.copy(serverUri = uri)
        lastFailureAt = 0L
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

        val resources = authClient.getResources().getOrNull() ?: run {
            Log.d(TAG, "could not reach plex.tv for a fresh address list")
            return null
        }
        val resource = AuthClient.mediaServers(resources)
            .firstOrNull { it.clientIdentifier == machineIdentifier } ?: run {
            Log.d(TAG, "plex.tv no longer lists $machineIdentifier")
            return null
        }

        val candidates = ServerProbe.candidates(resource)
        store(machineIdentifier, candidates)
        return candidates
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
    }
}
