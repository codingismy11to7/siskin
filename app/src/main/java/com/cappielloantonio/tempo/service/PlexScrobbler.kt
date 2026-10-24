package com.cappielloantonio.tempo.service

import android.util.Log
import androidx.media3.common.MediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.api.search.SearchClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
 * Fire and forget: reports come from the player's own listeners, where waiting
 * on the network would stall playback.
 *
 * Reports are sent one at a time, in the order they were made. The server keeps
 * whichever state it heard last, so a heartbeat overtaken by the pause that
 * followed it would leave a paused car showing as playing.
 */
object PlexScrobbler {
    private class Report(
        val ratingKey: String,
        val partKey: String,
        val state: String,
        val timeMs: Long,
        val station: PlexMediaMapper.StationTag?,
        val sent: CompletableDeferred<Unit>? = null,
    )

    private val queue = Channel<Report>(Channel.UNLIMITED)

    private val api by lazy { PlexApi() }

    // Touched only by the one coroutine draining the queue.
    private var client: SearchClient? = null
    private var clientServer: Pair<String?, String?>? = null

    // Process-scoped, and deliberately never cancelled: a scrobble outlives the
    // track it describes -- the "stopped" report is sent as the item is being
    // torn down -- so tying it to any player or session lifetime would cancel
    // exactly the reports that matter most.
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (report in queue) {
                try {
                    send(report)
                } catch (failure: Throwable) {
                    // Cancellation included: this scope is never cancelled, so one
                    // reaching here came from a single report, and rethrowing it
                    // would end every report after it. Outside any `either { }`,
                    // so there is no `raise` to swallow.
                    Log.w(TAG, "scrobble failed", failure)
                } finally {
                    report.sent?.complete(Unit)
                }
            }
        }
    }

    @JvmStatic
    fun report(
        ratingKey: String,
        partKey: String,
        state: String,
        timeMs: Long,
        station: PlexMediaMapper.StationTag?,
    ) {
        queue.trySend(Report(ratingKey, partKey, state, timeMs, station))
    }

    /**
     * The same report, returning once it has been sent. For the station top-up,
     * which must not read the play queue until the server has heard which item
     * is playing.
     */
    suspend fun reportNow(
        item: MediaItem,
        state: String,
        timeMs: Long,
    ) {
        val extras = item.mediaMetadata.extras ?: return
        val ratingKey = extras.getString(PlexMediaMapper.EXTRA_ID) ?: return
        val partKey = extras.getString(PlexMediaMapper.EXTRA_PART_KEY) ?: return
        val sent = CompletableDeferred<Unit>()
        queue.trySend(Report(ratingKey, partKey, state, timeMs, PlexMediaMapper.stationTagOf(item), sent))
        sent.await()
    }

    private suspend fun send(report: Report) {
        searchClient()
            .reportProgress(RatingKey(report.ratingKey), report.partKey, report.state, report.timeMs, report.station)
            .onLeft { Log.w(TAG, "scrobble failed: $it") }
    }

    /**
     * Rebuilt when the server changes, because a SearchClient is pinned to the
     * address and token it was built with. A heartbeat every ten seconds is too
     * often to build a Retrofit per report.
     */
    private fun searchClient(): SearchClient {
        val server = api.serverUri to api.serverToken
        val cached = client
        if (cached != null && server == clientServer) return cached
        return SearchClient(api, server.first, server.second).also {
            client = it
            clientServer = server
        }
    }
}
