package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import arrow.core.Either
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.base.PlexResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "PlexMixRepository"

/**
 * Supplies tracks for continuous play: sonically similar first, random second.
 *
 * The similar tier needs Plex Pass sonic analysis. Without it the server
 * answers with an empty container rather than an error, so an empty result is
 * an ordinary outcome here and the caller falls through to [randomTracks].
 */
@OptIn(UnstableApi::class)
class PlexMixRepository {

    fun interface TracksCallback {
        fun onTracks(tracks: List<MediaItem>)
    }

    private val api = PlexApi()
    private val libraryClient = LibraryClient(api)

    /**
     * Main, not IO: the only caller is MediaManager.continuousPlay, whose
     * callback reads and mutates a MediaBrowser (`getMediaItemCount`,
     * `addMediaItems`), and a MediaController rejects access from any thread but
     * the one it was built on. Retrofit's Android platform posted `Call.enqueue`
     * callbacks to the main thread, so this is where the callback this replaced
     * already ran -- keeping it there is what stops continuous play from
     * crashing. The request itself still runs on OkHttp's threads; only the
     * resumption lands here.
     *
     * MediaManager builds a fresh repository per mix request and drops it once
     * the callback has run, so there is no owner left to cancel this scope and
     * no hook to cancel it from -- the coroutine and its scope become garbage
     * together when the request finishes.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val serverUri: String? get() = api.session?.serverUri
    private val token: String?
        get() = PlexApi.serverTokenOrAccount(api.session?.serverToken, api.accountToken)

    fun similarTracks(ratingKey: String, count: Int, callback: TracksCallback) {
        deliver(callback) { libraryClient.getNearest(RatingKey(ratingKey), count) }
    }

    fun randomTracks(count: Int, callback: TracksCallback) {
        val key: SectionKey? = api.session?.musicSectionKey
        if (key == null) {
            Log.w(TAG, "no music section selected")
            callback.onTracks(emptyList())
            return
        }
        deliver(callback) {
            libraryClient.getSectionContent(
                key, PlexItemType.TRACK, 0, count, LibraryClient.SORT_RANDOM
            )
        }
    }

    /**
     * Both tiers report failure as "no tracks" -- continuous play is best effort.
     *
     * The callback runs on every path, which is the load-bearing part: its only
     * caller is MediaManager.continuousPlay, and that is where
     * `continuousPlayIsRunning` is set back to false. A path that returns
     * without calling back leaves the flag stuck true and Instant Mix dead for
     * the rest of the process. Hence the mapping sitting inside the `try` and
     * the callback outside it -- a callback that throws must not be retried with
     * an empty list.
     *
     * The catch stays wide even though the request's failure is a value now: the
     * mapping inside it is not, and this is outside any `either { }` so there is
     * no `raise` for it to swallow.
     */
    private fun deliver(
        callback: TracksCallback,
        request: suspend () -> Either<PlexTransportFailure, PlexResponse>
    ) {
        scope.launch {
            val tracks = try {
                request().fold(
                    { failure ->
                        Log.w(TAG, "mix request failed: $failure")
                        emptyList()
                    },
                    { response ->
                        PlexBrowseRepository.tracksOf(response).mapNotNull {
                            PlexMediaMapper.trackToMediaItem(it, null, serverUri, token)
                        }
                    }
                )
            } catch (failure: Throwable) {
                Log.w(TAG, "mix mapping failed", failure)
                emptyList()
            }
            callback.onTracks(tracks)
        }
    }
}
