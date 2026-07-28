package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.api.search.SearchClient
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.util.ConstantsAA
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

private const val TAG = "PlexBrowseRepository"

/**
 * Serves the automotive browse tree from a Plex music section.
 *
 * PlexRetrofitFactory bakes the server URI into the Retrofit base URL at
 * construction time -- see its KDoc -- so the clients are rebuilt whenever that
 * URI changes rather than held from construction. This instance outlives
 * sign-in: MediaService creates it when the car first browses, which on a fresh
 * install is *before* any server is known, and the factory falls back to an
 * unreachable placeholder base URL in that state. Clients captured then would
 * pin every later browse to the placeholder, so signing in would leave the tabs
 * permanently empty with no error the user could act on.
 */
@OptIn(UnstableApi::class)
class PlexBrowseRepository {

    private val api = PlexApi()

    private var clientsUri: String? = null
    private var cachedLibraryClient: LibraryClient? = null
    private var cachedSearchClient: SearchClient? = null

    private val libraryClient: LibraryClient
        get() = synchronized(this) { refreshClients(); cachedLibraryClient!! }

    private val searchClient: SearchClient
        get() = synchronized(this) { refreshClients(); cachedSearchClient!! }

    private fun refreshClients() {
        val uri = api.serverUri
        if (cachedLibraryClient == null || uri != clientsUri) {
            clientsUri = uri
            cachedLibraryClient = LibraryClient(api)
            cachedSearchClient = SearchClient(api)
        }
    }

    private val sectionKey: String? get() = api.musicSectionKey
    private val serverUri: String? get() = api.serverUri
    private val token: String? get() = PlexApi.serverTokenOrAccount(api.serverToken, api.accountToken)

    // ── browse nodes ──────────────────────────────────────────

    fun getPlaylists(prefix: String) = fetch(searchClient.getPlaylists()) { body ->
        itemsOf(body, TYPE_PLAYLIST)
            .take(ConstantsAA.MAX_ITEMS)
            .mapNotNull { PlexMediaMapper.playlistToMediaItem(it, prefix) }
    }

    fun getPlaylistTracks(playlistId: String) =
        fetch(searchClient.getPlaylistItems(playlistId, 0, ConstantsAA.MAX_ITEMS)) { body ->
            tracksOf(body).mapNotNull {
                PlexMediaMapper.trackToMediaItem(it, ConstantsAA.QUEUE_CACHED_SOURCE, serverUri, token)
            }
        }

    /**
     * The artist list, with the "view by albums" shortcut prepended.
     *
     * That first entry is the only route to [ConstantsAA.ARTISTS_BY_ALBUMS_ID]:
     * the browse root holds three fixed tabs and this is not one of them, so
     * dropping it silently removes the artist-sorted album view rather than
     * breaking anything a test or the compiler would notice. The Subsonic
     * AutomotiveRepository prepended it for the same reason.
     */
    fun getArtists(prefix: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch(
            libraryClient.getSectionContent(key, PlexItemType.ARTIST, 0, ConstantsAA.MAX_ITEMS)
        ) { body ->
            listOf(viewByAlbumsShortcut()) + itemsOf(body, TYPE_ARTIST).mapNotNull {
                PlexMediaMapper.artistToMediaItem(it, prefix, serverUri, token)
            }
        }
    }

    private fun viewByAlbumsShortcut(): MediaItem = PlexMediaMapper.shortcutToMediaItem(
        ConstantsAA.ARTISTS_BY_ALBUMS_ID,
        App.getContext().getString(R.string.aa_view_by_albums),
        R.drawable.ic_aa_albums
    )

    fun getArtistAlbums(albumPrefix: String, artistRatingKey: String) =
        fetch(libraryClient.getChildren(artistRatingKey, 0, ConstantsAA.MAX_ITEMS)) { body ->
            itemsOf(body, TYPE_ALBUM).mapNotNull {
                PlexMediaMapper.albumToMediaItem(it, albumPrefix, serverUri, token)
            }
        }

    fun getAlbums(prefix: String, sort: String?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch(
            libraryClient.getSectionContent(key, PlexItemType.ALBUM, 0, ConstantsAA.MAX_ITEMS, sort)
        ) { body ->
            itemsOf(body, TYPE_ALBUM).mapNotNull {
                PlexMediaMapper.albumToMediaItem(it, prefix, serverUri, token)
            }
        }
    }

    fun getAlbumTracks(albumRatingKey: String) =
        fetch(libraryClient.getChildren(albumRatingKey, 0, ConstantsAA.MAX_ITEMS)) { body ->
            tracksOf(body).mapNotNull {
                PlexMediaMapper.trackToMediaItem(it, ConstantsAA.QUEUE_CACHED_SOURCE, serverUri, token)
            }
        }

    /**
     * Plex rejects a multi-type search with HTTP 400, so this issues three and
     * merges. They run sequentially rather than in parallel: three small
     * requests against a LAN server are cheap, and sequencing keeps the merge
     * order deterministic without coordinating futures.
     */
    fun search(
        query: String,
        albumPrefix: String,
        artistPrefix: String
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

        collect(key, query, PlexItemType.ARTIST) { artists ->
            collect(key, query, PlexItemType.ALBUM) { albums ->
                collect(key, query, PlexItemType.TRACK) { tracks ->
                    val items = mutableListOf<MediaItem>()
                    artists.forEach { m ->
                        PlexMediaMapper.artistToMediaItem(m, artistPrefix, serverUri, token)
                            ?.let(items::add)
                    }
                    albums.forEach { m ->
                        PlexMediaMapper.albumToMediaItem(m, albumPrefix, serverUri, token)
                            ?.let(items::add)
                    }
                    tracks.forEach { m ->
                        PlexMediaMapper.trackToMediaItem(m, null, serverUri, token)
                            ?.let(items::add)
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), null))
                }
            }
        }

        return future
    }

    private fun collect(
        sectionKey: String,
        query: String,
        type: Int,
        next: (List<Metadata>) -> Unit
    ) {
        searchClient.search(sectionKey, query, type).enqueue(object : Callback<PlexResponse> {
            override fun onResponse(call: Call<PlexResponse>, response: Response<PlexResponse>) {
                next(if (response.isSuccessful) SearchClient.playableResults(response.body()) else emptyList())
            }

            // One failed tier must not lose the other two.
            override fun onFailure(call: Call<PlexResponse>, t: Throwable) {
                Log.w(TAG, "search tier type=$type failed", t)
                next(emptyList())
            }
        })
    }

    // ── plumbing ──────────────────────────────────────────────

    /**
     * The one Retrofit-to-ListenableFuture bridge. An HTTP failure becomes a
     * LibraryResult error so MediaLibraryServiceCallback can offer the sign-in
     * resolution on a 401; a transport failure completes the future
     * exceptionally, which it reads as "unreachable" rather than "rejected".
     */
    private fun fetch(
        call: Call<PlexResponse>,
        map: (PlexResponse?) -> List<MediaItem>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

        call.enqueue(object : Callback<PlexResponse> {
            override fun onResponse(call: Call<PlexResponse>, response: Response<PlexResponse>) {
                val result = resultFor(response, map)
                if (result.resultCode != LibraryResult.RESULT_SUCCESS) {
                    Log.w(TAG, "browse failed with HTTP ${response.code()}")
                }
                future.set(result)
            }

            override fun onFailure(call: Call<PlexResponse>, t: Throwable) {
                Log.w(TAG, "browse could not reach the server", t)
                future.setException(t)
            }
        })

        return future
    }

    private fun errorFuture(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Log.w(TAG, "no music section selected")
        return SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>().apply {
            set(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        }
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403

        private const val TYPE_TRACK = "track"
        private const val TYPE_ALBUM = "album"
        private const val TYPE_ARTIST = "artist"
        private const val TYPE_PLAYLIST = "playlist"

        /**
         * Narrows a container to streamable tracks. An absent Metadata list is an
         * empty result, not a failure: a library with no matching items answers
         * 200 with the wrapper present and the list absent.
         */
        @JvmStatic
        fun tracksOf(response: PlexResponse?): List<Metadata> = itemsOf(response, TYPE_TRACK)

        @JvmStatic
        fun itemsOf(response: PlexResponse?, type: String): List<Metadata> =
            response?.mediaContainer?.metadata
                ?.filter { it.type == type && !it.ratingKey.isNullOrBlank() }
                ?: emptyList()

        /**
         * The response→LibraryResult decision `fetch()` hands to its future,
         * pulled out so it is reachable from a test without a live Retrofit
         * `Call`. An empty library answers 200 with `MediaContainer` present and
         * `Metadata` absent -- `map` (built on [tracksOf]/[itemsOf]) already
         * degrades that to an empty list, so this must still report success:
         * the Subsonic implementation this replaces mistook that shape for a
         * failure and showed "Something went wrong" on the first browse tab for
         * every user with no playlists. A non-2xx response is always an error,
         * mapped by [errorFor].
         */
        @JvmStatic
        internal fun resultFor(
            response: Response<PlexResponse>,
            map: (PlexResponse?) -> List<MediaItem>
        ): LibraryResult<ImmutableList<MediaItem>> =
            if (!response.isSuccessful) {
                errorFor(response.code())
            } else {
                LibraryResult.ofItemList(ImmutableList.copyOf(map(response.body())), null)
            }

        /**
         * 401/403 mean the token Plex issued is no longer accepted, which is
         * distinct from a merely malformed request: `SessionError.ERROR_PERMISSION_DENIED`
         * is what a later task keys the "sign in again" affordance off of, so
         * every other non-2xx code must land on `ERROR_BAD_VALUE` instead.
         */
        private fun errorFor(httpCode: Int): LibraryResult<ImmutableList<MediaItem>> =
            if (httpCode == HTTP_UNAUTHORIZED || httpCode == HTTP_FORBIDDEN) {
                LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
    }
}
