package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.api.search.SearchClient
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.util.ConstantsAA
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    private val addressBook = ServerAddressBook.shared

    /**
     * Every browse request runs here. SupervisorJob because the browse calls are
     * unrelated to one another -- a failing Albums fetch must not cancel the
     * Playlists fetch the car issued a moment earlier, which a plain Job would
     * do by cancelling its siblings.
     *
     * IO rather than Main, unlike PlexMixRepository and BaseSessionCallback,
     * which both have to resume on the main thread because their continuations
     * touch a Player or a MediaBrowser. Nothing on this path does: the futures
     * are handed to media3, which posts them onto the application thread itself,
     * and the one cache write behind them (SessionMediaItemRepository.cache)
     * already hops to its own single-threaded executor.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var clientsSession: PlexSession? = null
    private var cachedLibraryClient: LibraryClient? = null
    private var cachedSearchClient: SearchClient? = null

    private val libraryClient: LibraryClient
        get() = synchronized(this) { refreshClients(); cachedLibraryClient!! }

    private val searchClient: SearchClient
        get() = synchronized(this) { refreshClients(); cachedSearchClient!! }

    /**
     * Rebuilds when the session changes, because PlexRetrofitFactory bakes the
     * server URI into the base URL at construction. This instance outlives
     * sign-in -- MediaService creates it when the car first browses, which on a
     * fresh install is before any server is known -- so clients captured then
     * would pin every later browse to the placeholder base URL and leave the
     * tabs permanently empty.
     */
    private fun refreshClients() {
        val session = api.session
        if (cachedLibraryClient == null || session != clientsSession) {
            clientsSession = session
            cachedLibraryClient = LibraryClient(api, session?.serverUri, session?.serverToken)
            cachedSearchClient = SearchClient(api, session?.serverUri, session?.serverToken)
        }
    }

    private val sectionKey: SectionKey? get() = api.session?.musicSectionKey
    private val serverUri: String? get() = api.session?.serverUri
    private val token: String?
        get() = PlexApi.serverTokenOrAccount(api.session?.serverToken, api.accountToken)

    // ── browse nodes ──────────────────────────────────────────

    /**
     * Scoped to the chosen music section, like getArtists/getAlbums:
     * `sectionID` is the query parameter that actually filters a playlist
     * listing on the server, and `librarySectionID` is silently ignored --
     * both measured against a live PMS 1.43.3 server, see
     * SearchService.getPlaylists. A playlist itself carries no section of its
     * own -- in Plex a playlist is a server-level collection that can span
     * libraries -- which is why the scope has to be requested here rather
     * than filtered from the response client-side; left unscoped, this tab
     * shows playlists from whichever library Plex feels like rather than the
     * one the user picked.
     */
    fun getPlaylists(prefix: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch({ searchClient.getPlaylists(key) }) { body ->
            itemsOf(body, TYPE_PLAYLIST)
                .take(ConstantsAA.MAX_ITEMS)
                .mapNotNull { PlexMediaMapper.playlistToMediaItem(it, prefix) }
        }
    }

    /** The browse list: the shuffle row, then the playlist in its own order. */
    fun getPlaylistTracks(playlistId: String) = playlistTracks(playlistId) { tracks ->
        listOf(shufflePlaylistRow(playlistId)) + tracks
    }

    /**
     * The same tracks with no shuffle row, for the queue that row builds.
     *
     * Kept separate rather than filtered later: a queue containing the row would
     * hold a playable item with no stream.
     */
    fun getPlaylistTracksForShuffle(playlistId: String) = playlistTracks(playlistId) { it }

    private fun playlistTracks(
        playlistId: String,
        decorate: (List<MediaItem>) -> List<MediaItem>
    ) = cachedTracks(
        { searchClient.getPlaylistItems(RatingKey(playlistId), 0, ConstantsAA.MAX_ITEMS) },
        decorate
    )

    private fun shufflePlaylistRow(playlistId: String): MediaItem =
        PlexMediaMapper.shuffleRowToMediaItem(
            ConstantsAA.SHUFFLE_PLAYLIST_ID + playlistId,
            App.getContext().getString(R.string.aa_shuffle_playlist)
        )

    /**
     * The decades the section's albums fall into, newest first as the server
     * returns them.
     */
    fun getDecades(prefix: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch({ libraryClient.getDecades(key) }) { body ->
            directoriesOf(body).mapNotNull { PlexMediaMapper.decadeToMediaItem(it, prefix) }
        }
    }

    /** The browse list: the shuffle row, then a random sample of the decade. */
    fun getDecadeTracks(decadeKey: String) = decadeTracks(decadeKey) { tracks ->
        listOf(shuffleDecadeRow(decadeKey)) + tracks
    }

    /**
     * The same query with no shuffle row, for when the row's tap cannot be
     * served from what is already on screen.
     *
     * `MediaLibraryServiceCallback.cachedDecadeTracks` tries the cached browse
     * list first -- the tap should queue what the user was looking at, not
     * spend a round trip drawing a second uniform random 500 that is
     * statistically identical for the purpose but not the same tracks. This
     * function is the fallback for when that cache cannot be trusted: cold
     * after a process restart, or holding a different node's list.
     *
     * Each call is its own `sort=random` draw and Plex offers no seed to pin
     * one -- measured against PMS 1.43.3, where `sort=random:12345` is accepted
     * and ignored -- so even the fallback path yields a *different* sample than
     * whatever the browse list last showed. That is accepted here rather than
     * worked around: it is what "shuffle the decade" means when there is no
     * displayed list left to honor.
     */
    fun getDecadeTracksForShuffle(decadeKey: String) = decadeTracks(decadeKey) { it }

    /**
     * Randomly sorted, unlike [getArtistTracks], and the two do not contradict
     * each other. An artist has a real running order to fall back to when the
     * car's shuffle toggle goes off mid-listen, so inventing one there would be
     * dishonest. A decade has none -- and unsorted would mean permanently
     * sampling the first 500 of the decade in library order, leaving the rest
     * unreachable by any sequence of taps. Random is the honest description of
     * what the server was asked for.
     */
    private fun decadeTracks(
        decadeKey: String,
        decorate: (List<MediaItem>) -> List<MediaItem>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return cachedTracks({
            libraryClient.getSectionContent(
                key,
                PlexItemType.TRACK,
                0,
                ConstantsAA.MAX_ITEMS,
                sort = LibraryClient.SORT_RANDOM,
                decade = decadeKey
            )
        }, decorate)
    }

    private fun shuffleDecadeRow(decadeKey: String): MediaItem =
        PlexMediaMapper.shuffleRowToMediaItem(
            ConstantsAA.SHUFFLE_DECADE_ID + decadeKey,
            App.getContext().getString(R.string.aa_shuffle_decade)
        )

    fun getArtists(prefix: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch(
            { libraryClient.getSectionContent(key, PlexItemType.ARTIST, 0, ConstantsAA.MAX_ITEMS) }
        ) { body ->
            itemsOf(body, TYPE_ARTIST).mapNotNull {
                PlexMediaMapper.artistToMediaItem(it, prefix)
            }
        }
    }

    /**
     * Deliberately the section listing filtered by artist rather than the
     * artist's own children endpoint, which drops albums -- see
     * [com.cappielloantonio.tempo.plex.api.library.LibraryService.getChildren]
     * for the measurements. Being section-scoped, it needs the chosen music
     * section the way getArtists and getAlbums do.
     */
    fun getArtistAlbums(
        albumPrefix: String,
        artistRatingKey: String
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch({
            libraryClient.getSectionContent(
                key,
                PlexItemType.ALBUM,
                0,
                ConstantsAA.MAX_ITEMS,
                artistId = artistRatingKey
            )
        }) { body ->
            listOf(shuffleArtistRow(artistRatingKey)) + itemsOf(body, TYPE_ALBUM).mapNotNull {
                PlexMediaMapper.albumToMediaItem(it, albumPrefix)
            }
        }
    }

    private fun shuffleArtistRow(artistRatingKey: String): MediaItem =
        PlexMediaMapper.shuffleRowToMediaItem(
            ConstantsAA.SHUFFLE_ARTIST_ID + artistRatingKey,
            App.getContext().getString(R.string.aa_shuffle_artist)
        )

    /**
     * Every track by one artist, flat and in library order.
     *
     * Feeds the shuffle row, and left unshuffled on purpose under both settings.
     *
     * With "use the car's shuffle" on, the player owns shuffling (the session
     * already carries the command), so turning the car's toggle off mid-listen
     * falls back to the artist's real running order rather than to an order this
     * function invented. With it off, MediaLibrarySessionCallback shuffles at
     * the tap -- still not here, which keeps this function the single honest
     * description of what the server was asked for.
     *
     * Uses the same `artist.id` filter as [getArtistAlbums] rather than the
     * artist's allLeaves endpoint. Both returned identical counts on a live
     * server (297, 16 and 14 tracks for the three artists sampled), so this
     * picks the query already proven against the artist relation.
     *
     * Unsorted does not contradict [decadeTracks] fetching `sort=random`: an
     * artist has a real running order to fall back to when the car's shuffle
     * toggle goes off mid-listen, whereas a decade has none, and unsorted there
     * would mean permanently sampling only the first 500 of the decade in
     * library order.
     */
    fun getArtistTracks(artistRatingKey: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch({
            libraryClient.getSectionContent(
                key,
                PlexItemType.TRACK,
                0,
                ConstantsAA.MAX_ITEMS,
                artistId = artistRatingKey
            )
        }) { body ->
            tracksOf(body).mapNotNull {
                PlexMediaMapper.trackToMediaItem(it, null, serverUri, token)
            }
        }
    }

    fun getAlbums(prefix: String, sort: String?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch(
            { libraryClient.getSectionContent(key, PlexItemType.ALBUM, 0, ConstantsAA.MAX_ITEMS, sort) }
        ) { body ->
            itemsOf(body, TYPE_ALBUM).mapNotNull {
                PlexMediaMapper.albumToMediaItem(it, prefix)
            }
        }
    }

    fun getAlbumTracks(albumRatingKey: String) =
        cachedTracks({ libraryClient.getChildren(RatingKey(albumRatingKey), 0, ConstantsAA.MAX_ITEMS) }) { it }

    // ── windowed browse ────────────────────────────────────────
    //
    // The car asks for every node in full -- onGetChildren always arrives as
    // page=0, pageSize=Integer.MAX_VALUE, and reaching the end of a short list
    // provokes no follow-up request -- so paging cannot come from the car. It
    // comes from the tree instead: a list too long to browse becomes a list of
    // ranges, each of which is a node the car can ask for in full.

    /**
     * The Artists tab: window rows, or the artists themselves if they fit.
     *
     * [LibraryClient.SORT_DISPLAY_TITLE] rather than the server default, and the
     * window listing and its contents must pass the *same* sort -- the window ids
     * are offsets into this ordering, so a mismatch would point every window at
     * the wrong slice.
     */
    fun getArtistWindows(windowPrefix: String, artistPrefix: String) =
        windowed(PlexItemType.ARTIST, LibraryClient.SORT_DISPLAY_TITLE, windowPrefix, R.drawable.ic_aa_artists) { body ->
            itemsOf(body, TYPE_ARTIST).mapNotNull {
                PlexMediaMapper.artistToMediaItem(it, artistPrefix)
            }
        }

    fun getArtistWindow(start: Int, artistPrefix: String) = window(
        PlexItemType.ARTIST, LibraryClient.SORT_DISPLAY_TITLE, start
    ) { body ->
        itemsOf(body, TYPE_ARTIST).mapNotNull {
            PlexMediaMapper.artistToMediaItem(it, artistPrefix)
        }
    }

    /** The Albums tab, ordered by displayed name for the same reason. */
    fun getAlbumWindows(windowPrefix: String, albumPrefix: String) =
        windowed(PlexItemType.ALBUM, LibraryClient.SORT_DISPLAY_TITLE, windowPrefix, R.drawable.ic_aa_albums) { body ->
            itemsOf(body, TYPE_ALBUM).mapNotNull {
                PlexMediaMapper.albumToMediaItem(it, albumPrefix)
            }
        }

    fun getAlbumWindow(start: Int, albumPrefix: String) = window(
        PlexItemType.ALBUM, LibraryClient.SORT_DISPLAY_TITLE, start
    ) { body ->
        itemsOf(body, TYPE_ALBUM).mapNotNull {
            PlexMediaMapper.albumToMediaItem(it, albumPrefix)
        }
    }

    private fun window(
        type: Int,
        sort: String?,
        start: Int,
        map: (PlexResponse) -> List<MediaItem>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return fetch(
            { libraryClient.getSectionContent(key, type, start, ConstantsAA.WINDOW_SIZE, sort) },
            map
        )
    }

    /**
     * One request decides the shape: it asks for the first window and reads
     * `totalSize` off the same response, so a library that fits costs exactly
     * what it costs today and never pays for the window machinery.
     */
    private fun windowed(
        type: Int,
        sort: String?,
        windowPrefix: String,
        icon: Int,
        map: (PlexResponse) -> List<MediaItem>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val key = sectionKey ?: return errorFuture()
        return launchInto {
            either {
                val head = when (
                    val outcome =
                        libraryClient.getSectionContent(key, type, 0, ConstantsAA.WINDOW_SIZE, sort)
                ) {
                    is Either.Right -> outcome.value
                    is Either.Left -> when (val failure = outcome.value) {
                        is PlexTransportFailure.Http -> {
                            Log.w(TAG, "windowed browse failed with HTTP ${failure.code}")
                            return@either errorFor(failure.code)
                        }

                        is PlexTransportFailure.Unreachable -> raise(failure)
                    }
                }

                val total = head.mediaContainer?.totalSize ?: 0
                val items = if (total <= ConstantsAA.WINDOW_SIZE) {
                    map(head)
                } else {
                    windowRows(key, type, sort, total, windowPrefix, icon, head)
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), null)
            }
        }
    }

    /**
     * Labels each window with the title it starts at and the title the next one
     * starts at -- "Beck - Cake" -- so the boundary name appears on both sides of
     * the seam and reads as a range rather than as an exclusive bound.
     *
     * The boundary titles cost one one-item request each, issued together rather
     * than in series: on a car's connection the round trips dominate, and they
     * are independent. Index 0 needs no request because the count came back on a
     * response that already holds the first window.
     *
     * `titleAt` returns null instead of raising, deliberately: these run inside
     * `async`, and a `raise` crossing a coroutine-builder boundary is exactly
     * what this codebase forbids. A window whose label could not be fetched
     * falls back to its position, which is worth more than failing the tab.
     */
    private suspend fun windowRows(
        key: SectionKey,
        type: Int,
        sort: String?,
        total: Int,
        windowPrefix: String,
        icon: Int,
        head: PlexResponse
    ): List<MediaItem> {
        val size = ConstantsAA.WINDOW_SIZE
        val count = (total + size - 1) / size
        val boundaries = (0 until count).map { it * size } + (total - 1)

        val titles = coroutineScope {
            boundaries.map { index ->
                async {
                    if (index == 0) {
                        head.mediaContainer?.metadata?.firstOrNull()?.title
                    } else {
                        titleAt(key, type, sort, index)
                    }
                }
            }.awaitAll()
        }

        return (0 until count).map { window ->
            val from = titles[window]
            val to = titles[window + 1]
            val label = if (from != null && to != null) {
                "${shortened(from)}  -  ${shortened(to)}"
            } else {
                "${window * size + 1} - ${minOf((window + 1) * size, total)}"
            }
            PlexMediaMapper.windowRowToMediaItem(windowPrefix + (window * size), label, icon)
        }
    }

    private suspend fun titleAt(key: SectionKey, type: Int, sort: String?, index: Int): String? =
        libraryClient.getSectionContent(key, type, index, 1, sort)
            .getOrNull()
            ?.mediaContainer?.metadata?.firstOrNull()?.title

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

        return launchInto {
            val artists = collect(key, query, PlexItemType.ARTIST, TYPE_ARTIST)
            val albums = collect(key, query, PlexItemType.ALBUM, TYPE_ALBUM)
            val tracks = collect(key, query, PlexItemType.TRACK, TYPE_TRACK)

            val items = mutableListOf<MediaItem>()
            artists.forEach { m ->
                PlexMediaMapper.artistToMediaItem(m, artistPrefix)?.let(items::add)
            }
            albums.forEach { m ->
                PlexMediaMapper.albumToMediaItem(m, albumPrefix)?.let(items::add)
            }
            tracks.forEach { m ->
                PlexMediaMapper.trackToMediaItem(m, null, serverUri, token)
                    ?.let(items::add)
            }
            // Always a Right: collect() folds each tier's failure into an empty
            // list, so nothing here has a PlexTransportFailure left to report.
            LibraryResult.ofItemList(ImmutableList.copyOf(items), null).right()
        }
    }

    /**
     * One tier of the search.
     *
     * Narrowed with [itemsOf] and the tier's own [expectedType], the same way
     * every browse node narrows its response, rather than with a filter that
     * admits tracks, albums and artists alike: this is the only caller that
     * builds items of a *specific* kind out of the result, so a type-scoped
     * search that ever answered with a mixed set would have the artist tier
     * building artist entries out of albums.
     *
     * A failure is an empty tier, so one failed tier does not lose the other two.
     * `fold` handles every typed [PlexTransportFailure] this way, but Retrofit's Gson
     * converter does not wrap a malformed response body in `IOException` -- a
     * `JsonSyntaxException` from one tier would escape `plexCall` and this
     * function entirely, aborting the other two tiers and completing the whole
     * search future exceptionally. The `catch` below is what still contains
     * that: both typed failures and unexpected throwables cost only their own
     * tier. `collect` is not lexically inside an `either { }` block -- it is a
     * plain suspend function called from [launchInto]'s lambda in [search] --
     * so a broad catch here cannot swallow a `raise`.
     */
    private suspend fun collect(
        sectionKey: SectionKey,
        query: String,
        type: Int,
        expectedType: String
    ): List<Metadata> =
        try {
            searchClient.search(sectionKey, query, type).fold(
                { failure ->
                    Log.w(TAG, "search tier type=$type failed: $failure")
                    emptyList()
                },
                { itemsOf(it, expectedType) }
            )
        } catch (failure: Throwable) {
            Log.w(TAG, "search tier type=$type failed unexpectedly", failure)
            emptyList()
        }

    // ── plumbing ──────────────────────────────────────────────

    /**
     * The one browse call shape. An HTTP failure becomes a LibraryResult error so
     * MediaLibraryServiceCallback can offer the sign-in resolution on a 401; a
     * transport failure stays a Left and reaches [launchInto], which completes
     * the future exceptionally so the callback reads it as "unreachable" rather
     * than "rejected".
     */
    private fun fetch(
        request: suspend () -> Either<PlexTransportFailure, PlexResponse>,
        map: (PlexResponse) -> List<MediaItem>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        launchInto { resultFor(request, map) }

    /**
     * Tracks from one container, tagged as this browse node's queue source.
     *
     * The [ConstantsAA.QUEUE_CACHED_SOURCE] tag is what lets a tap partway down
     * the list open the queue at that position instead of playing one track
     * alone -- see MediaLibraryServiceCallback.resolveQueueForItem. It is
     * decided here, once, rather than at each node that wants that behaviour.
     *
     * [decorate] is where a node adds its shuffle row, and is the identity
     * function for the variants that feed a queue: a queue holding a shuffle row
     * would hold a playable item with no stream.
     */
    private fun cachedTracks(
        request: suspend () -> Either<PlexTransportFailure, PlexResponse>,
        decorate: (List<MediaItem>) -> List<MediaItem>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        fetch(request) { body ->
            decorate(
                tracksOf(body).mapNotNull {
                    PlexMediaMapper.trackToMediaItem(
                        it, ConstantsAA.QUEUE_CACHED_SOURCE, serverUri, token
                    )
                }
            )
        }

    /**
     * The suspend-to-ListenableFuture bridge, needed because media3's
     * MediaLibraryService.Callback takes a ListenableFuture and nothing else.
     * Runs a browse on [scope] and completes the car's future with the outcome.
     *
     * The block is wrapped in address recovery, so a browse against an address
     * that has gone stale re-probes and retries once rather than failing. One
     * wrapping here covers every browse node in the class; wrapping call sites
     * individually would leave whichever gets forgotten as the surviving bug.
     *
     * Search is the exception, and stays one: [collect] folds each tier's
     * failure into an empty list, so search's block is always Right and never
     * reaches recovery. Changing that means changing what a partial search
     * failure means, which is a separate decision.
     *
     * The future is completed on every path: with a value, with a
     * [PlexTransportException] carrying a transport failure, or exceptionally
     * from the catch. media3 waits on this future, so a browse that neither
     * succeeds nor fails leaves the tab spinning until the car gives up. That
     * includes cancellation at [release] time, which is why the catch stays as
     * wide as it is -- it is outside any `either { }` block, so there is no
     * `raise` for it to swallow.
     */
    private fun launchInto(
        block: suspend () -> Either<PlexTransportFailure, LibraryResult<ImmutableList<MediaItem>>>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

        scope.launch {
            try {
                addressBook.withAddressRecovery(block).fold(
                    { failure ->
                        Log.w(TAG, "browse could not reach the server: $failure")
                        future.setException(PlexTransportException(failure))
                    },
                    { future.set(it) }
                )
            } catch (failure: Throwable) {
                Log.w(TAG, "browse failed unexpectedly", failure)
                future.setException(failure)
            }
        }

        return future
    }

    /** Stops any browse still in flight. Called when MediaService is destroyed. */
    fun release() {
        scope.cancel()
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

        /** Characters per side of a window label; see [shortened]. */
        private const val LABEL_TITLE_MAX = 16

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
         * Narrows a container to its Directory entries. Decades arrive this way
         * rather than as Metadata, so [itemsOf] does not apply -- and unlike
         * `LibraryClient.musicSections` there is no `type` to filter on, because
         * a decade Directory carries none. An absent list is an empty result,
         * not a failure, for the same reason it is in [itemsOf].
         */
        @JvmStatic
        fun directoriesOf(response: PlexResponse?): List<Directory> =
            response?.mediaContainer?.directory ?: emptyList()

        /**
         * Decides what a browse outcome means to media3.
         *
         * An HTTP failure becomes a LibraryResult error, because the server
         * answered and the status is worth acting on -- 401/403 drive the "sign
         * in again" affordance. Anything else stays Left and is left to
         * [launchInto] to complete the future exceptionally, because a
         * reachability problem is not a rejection. That distinction used to be
         * held by a narrow catch clause and a comment warning not to widen it;
         * it is a `when` over a sealed type now.
         */
        internal suspend fun resultFor(
            request: suspend () -> Either<PlexTransportFailure, PlexResponse>,
            map: (PlexResponse) -> List<MediaItem>
        ): Either<PlexTransportFailure, LibraryResult<ImmutableList<MediaItem>>> = either {
            val failure = when (val outcome = request()) {
                is Either.Right ->
                    return@either LibraryResult.ofItemList(
                        ImmutableList.copyOf(map(outcome.value)), null
                    )

                is Either.Left -> outcome.value
            }

            when (failure) {
                is PlexTransportFailure.Http -> {
                    Log.w(TAG, "browse failed with HTTP ${failure.code}")
                    errorFor(failure.code)
                }

                is PlexTransportFailure.Unreachable -> raise(failure)
            }
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

        /**
         * Both ends of a range have to fit one row, so each is cut to half of
         * what fits rather than letting the car truncate the label as a whole --
         * which costs the *second* title entirely, the one that says where the
         * window stops. Measured on a 1024x768 head unit: a row holds roughly 34
         * characters, and album titles routinely exceed that on their own ("A
         * State of Trance Classics, Vol. 2"), so this is the common case for
         * albums rather than an edge case.
         *
         * Known limitation: 16 characters cannot always tell adjacent windows
         * apart -- a run of "A State of Trance Classics, Vol. N" yields two rows
         * reading the same. Widening trades directly against the second title
         * fitting.
         */
        @JvmStatic
        internal fun shortened(title: String): String =
            if (title.length <= LABEL_TITLE_MAX) {
                title
            } else {
                title.take(LABEL_TITLE_MAX - 1).trimEnd() + "…"
            }
    }
}
