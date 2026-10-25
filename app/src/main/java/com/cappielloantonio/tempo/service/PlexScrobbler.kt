package com.cappielloantonio.tempo.service

import android.util.Log
import androidx.media3.common.MediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.api.search.SearchClient
import com.cappielloantonio.tempo.plex.api.search.TimelineState
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
 *
 * A "stopped" report snapshots the server and token on the calling thread; the
 * rest resolve them when sent, off the main thread, as reading them costs
 * AccountManager Binder calls. The snapshot exists for the final "stopped" of a
 * track left by a Home switch, queued just before the new member's session is
 * written, which must still go out as the member who played it.
 *
 * Any other report queued before a switch to another member or server is
 * dropped rather than sent: resolved afterwards, it would go out as the new
 * member and open a Now Playing entry that nothing closes. Such a switch always
 * stops playback, so the "stopped" queued behind it ends the old one.
 */
object PlexScrobbler {
    private data class Server(
        val uri: String?,
        val token: String?,
    )

    /** Whose credentials a report goes out with. */
    private sealed interface Sender {
        /** Resolved on the calling thread, when the report was made. */
        data class Snapshot(
            val server: Server,
        ) : Sender

        /** Resolved when sent, unless the listener or server has changed since [generation]. */
        data class AsOf(
            val generation: Long,
        ) : Sender
    }

    private class Report(
        val ratingKey: String,
        val partKey: String,
        val state: TimelineState,
        val timeMs: Long,
        val station: PlexMediaMapper.StationTag?,
        val sender: Sender,
        val sent: CompletableDeferred<Unit>? = null,
    )

    private val queue = Channel<Report>(Channel.UNLIMITED)

    private val api by lazy { PlexApi() }

    // Touched only by the one coroutine draining the queue.
    private var client: SearchClient? = null
    private var clientServer: Server? = null

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
        state: TimelineState,
        timeMs: Long,
        station: PlexMediaMapper.StationTag?,
    ) {
        queue.trySend(Report(ratingKey, partKey, state, timeMs, station, senderFor(state)))
    }

    /**
     * The same report, returning once it has been sent. For the station top-up,
     * which must not read the play queue until the server has heard which item
     * is playing.
     */
    suspend fun reportNow(
        item: MediaItem,
        state: TimelineState,
        timeMs: Long,
    ) {
        val extras = item.mediaMetadata.extras ?: return
        val ratingKey = extras.getString(PlexMediaMapper.EXTRA_ID) ?: return
        val partKey = extras.getString(PlexMediaMapper.EXTRA_PART_KEY) ?: return
        val sent = CompletableDeferred<Unit>()
        queue.trySend(Report(ratingKey, partKey, state, timeMs, PlexMediaMapper.stationTagOf(item), senderFor(state), sent))
        sent.await()
    }

    private fun currentServer() = Server(api.serverUri, api.serverAuthToken)

    private fun senderFor(state: TimelineState) =
        if (state == TimelineState.STOPPED) Sender.Snapshot(currentServer()) else Sender.AsOf(PlexApi.sessionGeneration)

    /**
     * Null once the listener or server has changed since [generation]. Checked
     * before the read, to spare a stale report its Binder calls, and after it,
     * because the setter bumps before it writes: a read that saw any of the
     * new session fails the second check.
     */
    private fun serverAsOf(generation: Long): Server? {
        if (generation != PlexApi.sessionGeneration) return null
        return currentServer().takeIf { generation == PlexApi.sessionGeneration }
    }

    private suspend fun send(report: Report) {
        val server =
            when (val sender = report.sender) {
                is Sender.Snapshot -> sender.server
                is Sender.AsOf -> serverAsOf(sender.generation)
            }
        if (server == null) {
            Log.i(TAG, "dropped a ${report.state} report queued before the session changed")
            return
        }
        searchClient(server)
            .reportProgress(RatingKey(report.ratingKey), report.partKey, report.state, report.timeMs, report.station)
            .onLeft { Log.w(TAG, "scrobble failed: $it") }
    }

    /**
     * Rebuilt when the server changes, because a SearchClient is pinned to the
     * address and token it was built with. A heartbeat every ten seconds is too
     * often to build a Retrofit per report.
     */
    private fun searchClient(server: Server): SearchClient {
        val cached = client
        if (cached != null && server == clientServer) return cached
        // The token is already resolved, so there is nothing to fall back to.
        return SearchClient(api, server.uri, server.token, { null }).also {
            client = it
            clientServer = server
        }
    }
}
