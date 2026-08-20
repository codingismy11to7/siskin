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
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
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

    /**
     * A `get()`, not a `val`: [LibraryClient]'s own KDoc says it is pinned to
     * the [com.cappielloantonio.tempo.plex.PlexSession.serverUri] it was built
     * with and never re-reads it, so a `val` here would keep sending
     * [deliver]'s retry to the address that [addressBook] just moved away
     * from -- recovery would re-probe, adopt a live address, and then replay
     * the request against the dead one anyway, paying the failure twice
     * instead of once. Constructing one per access is cheap:
     * `PlexRetrofitFactory.server()` derives from a shared `OkHttpClient` via
     * `newBuilder`, so the connection pool and dispatcher are shared and no
     * handshake is repeated, and MediaManager already builds a fresh
     * [PlexMixRepository] per mix request, so nothing long-lived is churning.
     */
    private val libraryClient: LibraryClient get() = LibraryClient(api)
    private val addressBook = ServerAddressBook.shared

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

    fun similarTracks(
        ratingKey: String,
        count: Int,
        callback: TracksCallback,
    ) {
        deliver(callback) { libraryClient.getNearest(RatingKey(ratingKey), count) }
    }

    fun randomTracks(
        count: Int,
        callback: TracksCallback,
    ) {
        val key: SectionKey? = api.session?.musicSectionKey
        if (key == null) {
            Log.w(TAG, "no music section selected")
            callback.onTracks(emptyList())
            return
        }
        deliver(callback) {
            libraryClient.getSectionContent(
                key,
                PlexItemType.TRACK,
                0,
                count,
                LibraryClient.SORT_RANDOM,
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
     * [request] is wrapped in [addressBook]'s address recovery, so a mix call
     * against an address that has gone stale re-probes and retries once rather
     * than coming back empty. That retry only recovers because [libraryClient]
     * is a `get()` that re-reads the session on every access -- see its KDoc --
     * so it picks up the address the re-probe just adopted; were it a `val`
     * captured at construction, the retry would replay against the same dead
     * address the first attempt just failed on, paying the timeout twice for
     * no better result.
     *
     * The catch stays wide even though the request's failure is a value now: the
     * mapping inside it is not, and this is outside any `either { }` so there is
     * no `raise` for it to swallow.
     */
    private fun deliver(
        callback: TracksCallback,
        request: suspend () -> Either<PlexTransportFailure, PlexResponse>,
    ) {
        scope.launch {
            val tracks =
                try {
                    addressBook.withAddressRecovery(request).fold(
                        { failure ->
                            Log.w(TAG, "mix request failed: $failure")
                            emptyList()
                        },
                        { response ->
                            PlexBrowseRepository.tracksOf(response).mapNotNull {
                                PlexMediaMapper.trackToMediaItem(it, null, serverUri, token)
                            }
                        },
                    )
                } catch (failure: Throwable) {
                    Log.w(TAG, "mix mapping failed", failure)
                    emptyList()
                }
            callback.onTracks(tracks)
        }
    }
}
