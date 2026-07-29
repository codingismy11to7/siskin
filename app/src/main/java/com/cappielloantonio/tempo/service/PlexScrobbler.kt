package com.cappielloantonio.tempo.service

import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.search.SearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "PlexScrobbler"

/**
 * The seam that lets Java report playback to Plex's timeline endpoint.
 *
 * `SearchClient.reportProgress` is a suspend function and MediaManager is Java,
 * which cannot call one at all -- not even to discard the result. Converting
 * MediaManager to Kotlin to reach it would be a far larger change than the one
 * call needs, so the call moves here instead.
 *
 * Fire and forget, matching the Retrofit callback this replaces: MediaManager
 * scrobbles from the player's own listeners, where waiting on the network would
 * stall playback, and a report that never lands changes nothing the user can
 * see.
 */
object PlexScrobbler {

    /**
     * Process-scoped, and deliberately never cancelled: a scrobble outlives the
     * track it describes -- the "stopped" report is sent as the item is being
     * torn down -- so tying it to any player or session lifetime would cancel
     * exactly the reports that matter most.
     *
     * IO rather than the main thread the Retrofit callback used to resume on:
     * the callback body was empty, so nothing here reads a Player and there is
     * no reason to make the main thread carry it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun report(ratingKey: String, partKey: String, state: String, timeMs: Long) {
        scope.launch {
            try {
                SearchClient(PlexApi()).reportProgress(ratingKey, partKey, state, timeMs)
                    .onLeft { Log.w(TAG, "scrobble failed: $it") }
            } catch (failure: Throwable) {
                // Covers client construction and anything else still throwing.
                // Outside any `either { }`, so there is no `raise` to swallow.
                Log.w(TAG, "scrobble failed", failure)
            }
        }
    }
}
