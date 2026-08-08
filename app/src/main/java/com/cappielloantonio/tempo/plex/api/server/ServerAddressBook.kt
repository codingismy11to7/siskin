package com.cappielloantonio.tempo.plex.api.server

import android.os.SystemClock
import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.models.Resource
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex

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
    }
}
