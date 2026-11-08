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
import kotlinx.coroutines.CancellationException

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
    private val api = PlexApi()

    /**
     * A `get()`, not a `val`: [LibraryClient]'s own KDoc says it is pinned to
     * the [com.cappielloantonio.tempo.plex.PlexSession.serverUri] it was built
     * with and never re-reads it, so a `val` here would keep sending
     * [tracks]'s retry to the address that [addressBook] just moved away
     * from -- recovery would re-probe, adopt a live address, and then replay
     * the request against the dead one anyway, paying the failure twice
     * instead of once. Constructing one per access is cheap:
     * `PlexRetrofitFactory.server()` derives from a shared `OkHttpClient` via
     * `newBuilder`, so the connection pool and dispatcher are shared and no
     * handshake is repeated.
     */
    private val libraryClient: LibraryClient get() = LibraryClient(api)
    private val addressBook = ServerAddressBook.shared

    private val serverUri: String? get() = api.session?.serverUri
    private val token: String?
        get() = api.session?.serverAuthToken

    suspend fun similarTracks(
        ratingKey: String,
        count: Int,
    ): List<MediaItem> = tracks { libraryClient.getNearest(RatingKey(ratingKey), count) }

    suspend fun randomTracks(count: Int): List<MediaItem> {
        val key: SectionKey? = api.session?.musicSectionKey
        if (key == null) {
            Log.w(TAG, "no music section selected")
            return emptyList()
        }
        return tracks {
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
    private suspend fun tracks(request: suspend () -> Either<PlexTransportFailure, PlexResponse>): List<MediaItem> =
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "mix mapping failed", failure)
            emptyList()
        }
}
