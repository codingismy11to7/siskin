package com.cappielloantonio.tempo.service

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.CredentialGate
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MediaLibrarySessionCallback"
private val queueSourceCache = ConcurrentHashMap<String, List<MediaItem>>()

@UnstableApi
class MediaLibrarySessionCallback(
    context: Context,
    service: BaseMediaService,
    private val browseRepository: PlexBrowseRepository,
    private val sessionMediaItemRepository: SessionMediaItemRepository,
) : BaseSessionCallback(context, service) {
    init {
        MediaBrowserTree.initialize(context, browseRepository)
    }

    // ─────────────────────────────────────────────────────────────
    // Android Auto — browse
    // ─────────────────────────────────────────────────────────────

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.d(TAG, "onGetLibraryRoot Start pkg=${browser.packageName}")
        MediaBrowserTree.buildTree()
        return Futures.immediateFuture(LibraryResult.ofItem(MediaBrowserTree.getRootItem(), params))
    }

    /**
     * The default `onSubscribe` implementation (which this class does not override)
     * delegates here to decide whether a subscription is allowed to stick, requiring
     * success plus `mediaMetadata.isBrowsable == true`. Without this override every
     * subscribe request errors and media3 immediately drops the subscription, which
     * silently defeats [BrowseTreeInvalidator.invalidateRoot] -- notifyChildrenChanged
     * has no subscribed controller left to notify.
     *
     * Deliberately does NOT gate on `CredentialGate.isSignedIn()` the way [onGetChildren]
     * below does. Gating here would error the root subscription while signed out,
     * media3 would drop it for the same reason described above, and a later
     * `invalidateRoot()` after sign-in would once again have no subscriber left to
     * notify -- silently reintroducing the exact bug this override exists to fix.
     * `unitTests.returnDefaultValues = true` means no test catches that regression,
     * so this comment is the only thing standing between a future "fix" and repeating
     * it.
     */
    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = MediaBrowserTree.getItem(mediaId)
        Log.d(TAG, "onGetItem mediaId=$mediaId found=${item != null}")
        return if (item != null) {
            Futures.immediateFuture(LibraryResult.ofItem(item, null))
        } else {
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!CredentialGate.isSignedIn() && parentId != Constants.ROOT_ID) {
            Log.d(TAG, "onGetChildren blocked for $parentId: no usable credentials")
            // A row, not an error, for every parentId except the root.
            // Signed out is a state, not a failure, and an error's Connect
            // button lands under the mini player on head units that always
            // show one. The root is exempted because the browse root is a
            // tab bar, not a list -- measured on an AAOS API 33 emulator, see
            // MediaBrowserTree.signedOutRow's KDoc -- so it falls through
            // below to the same four tabs a signed-in car gets -- which three
            // precede More is the user's saved order, not a static set;
            // MediaBrowserTree.buildTree is static and needs no credentials.
            // The car auto-opens the first tab, landing the user on this row
            // immediately. classifyFailure still returns an error for
            // credentials that were rejected mid-use -- that one is a
            // failure.
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    MediaBrowserTree.signedOutRow(context),
                    null,
                ),
            )
        }

        Log.d(TAG, "onGetChildren parentId = $parentId")

        val future = MediaBrowserTree.getChildren(parentId)

        return Futures.transformAsync(future, { result ->
            if (result != null && result.resultCode == LibraryResult.RESULT_SUCCESS) {
                val items = result.value ?: emptyList()
                queueSourceCache[Constants.QUEUE_CACHED_SOURCE] = items
                rememberTracks(items)
                Futures.immediateFuture(result)
            } else {
                classifyFailure(result)
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Persists a node's tracks so [resolveQueueForItem] can rebuild the list they
     * came from when the tapped item carries no parent tag.
     *
     * The in-memory [queueSourceCache] above covers browse nodes, whose items are
     * tagged QUEUE_CACHED_SOURCE. Search results are not tagged -- a search track
     * has no parent node -- so this Room cache is what makes tapping the third
     * search result start the queue at the third result rather than playing it
     * alone. The Subsonic AutomotiveRepository wrote the same cache from inside
     * its track-returning calls; the write moved here because PlexBrowseRepository
     * deliberately does not touch Room.
     *
     * Browsable rows are filtered out: they would persist with an id like
     * "[albumID]55", which is never looked up, and would round-trip through
     * toMediaItem() as an unplayable track if they ever were.
     */
    private fun rememberTracks(items: List<MediaItem>) {
        // The Mix row is playable but is not a track: it has no ratingKey and
        // no stream, so caching it would put a row in session_media_item that
        // round-trips into an unplayable MediaItem -- the same reason browsable
        // rows are excluded here.
        val tracks =
            items.filter {
                it.mediaMetadata.isPlayable == true && !isMixRow(it)
            }
        if (tracks.isNotEmpty()) sessionMediaItemRepository.cache(tracks)
    }

    /**
     * Pure prefix test, deliberately not "does [mixTracksFor] return
     * something": that issues a request, and this runs against every row of
     * every browse list from [rememberTracks].
     */
    private fun isMixRow(item: MediaItem) =
        item.mediaId.startsWith(Constants.MIX_ARTIST_ID) ||
            item.mediaId.startsWith(Constants.MIX_PLAYLIST_ID) ||
            item.mediaId.startsWith(Constants.MIX_DECADE_ID) ||
            item.mediaId.startsWith(Constants.MIX_HUB_ID)

    /**
     * Fetches the tracks a tapped Mix row stands for, or null if the item is
     * not a Mix row. **Issues a network request** -- call it once per tap --
     * except for the decade branch's cache hit, which is already-complete and
     * issues none; see [cachedDecadeTracks]. The hub branch's cache hit is
     * different: it still issues one, because it saves following the hub's
     * key a second time, not the round trip that expands containers to
     * tracks; see [cachedHubTracks].
     *
     * Dispatch is on the id prefix, which is the only thing the car sends back:
     * it rebuilds the item from the media id alone, so the extras the row was
     * built with are gone by the time it arrives here.
     */
    private fun mixTracksFor(item: MediaItem): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>? {
        val id = item.mediaId
        return when {
            id.startsWith(Constants.MIX_ARTIST_ID) -> {
                val artist = id.removePrefix(Constants.MIX_ARTIST_ID)
                Log.d(TAG, "Fetching every track by artist $artist to shuffle")
                browseRepository.getArtistTracks(artist)
            }

            id.startsWith(Constants.MIX_PLAYLIST_ID) -> {
                val playlist = id.removePrefix(Constants.MIX_PLAYLIST_ID)
                Log.d(TAG, "Fetching every track in playlist $playlist to shuffle")
                browseRepository.getPlaylistTracksForShuffle(playlist)
            }

            id.startsWith(Constants.MIX_DECADE_ID) -> {
                // The whole DecadeKey payload -- library and decade -- passed
                // on unsplit. Only PlexBrowseRepository.decadeTracks takes it
                // apart, and only to build the Plex filter.
                val decadeKey = id.removePrefix(Constants.MIX_DECADE_ID)
                cachedDecadeTracks(decadeKey)
                    ?: run {
                        Log.d(TAG, "Fetching a random sample of decade $decadeKey to shuffle")
                        browseRepository.getDecadeTracksForShuffle(decadeKey)
                    }
            }

            id.startsWith(Constants.MIX_HUB_ID) -> {
                val hubKey = id.removePrefix(Constants.MIX_HUB_ID)
                cachedHubTracks(hubKey)
                    ?: run {
                        Log.d(TAG, "Following hub $hubKey again to shuffle it")
                        browseRepository.getHubTracksForShuffle(hubKey)
                    }
            }

            else -> {
                null
            }
        }
    }

    /**
     * Replays the decade's own browse list instead of drawing a second
     * `sort=random` sample, or null to fall back to
     * [PlexBrowseRepository.getDecadeTracksForShuffle] if the cache cannot be
     * trusted for this decade.
     *
     * The list on screen when the row was tapped is already a uniform random
     * 500 -- [PlexBrowseRepository.getDecadeTracks] draws it the same way this
     * fallback would. A second draw is statistically identical for the tap: it
     * buys no extra randomness, only different tracks, so what plays stops
     * matching what was on screen. Measured cost of that extra round trip:
     * ~300ms of dead air before playback on a fast connection, worse on
     * cellular in a car.
     *
     * [queueSourceCache] is a single slot keyed by one constant, so it can hold
     * any node's most recent list -- a stale or unrelated one would queue the
     * wrong decade's tracks entirely. [PlexBrowseRepository.getDecadeTracks]
     * always puts the decade's own Mix row at index 0, so the cached list
     * identifies itself: a match there is certain enough to trust, and its
     * absence (empty cache, cold after a process restart, or a different node's
     * list) is the signal to fall back rather than guess.
     *
     * [decadeKey] is compared whole, as the
     * [com.cappielloantonio.tempo.util.DecadeKey] payload that came off the
     * tapped row, which is the same string `getDecadeTracks` built its row from.
     * Neither side splits it, so neither side can split it differently -- and
     * the library being *in* it is what makes a row cached before a server
     * switch fail this guard rather than replay another library's tracks.
     *
     * `drop(1)` rather than filtering by [isMixRow]: index 0 is the row by
     * construction, and dropping exactly the item the guard just matched is
     * narrower than a predicate that could also drop something else. The queue
     * must not contain the Mix row -- it is playable with no stream, and a
     * queue holding it would "play" a track that does not exist.
     */
    private fun cachedDecadeTracks(decadeKey: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>? {
        val cached = queueSourceCache[Constants.QUEUE_CACHED_SOURCE]
        if (cached?.firstOrNull()?.mediaId != Constants.MIX_DECADE_ID + decadeKey) return null

        Log.d(TAG, "Serving decade $decadeKey shuffle from the cached browse list")
        return Futures.immediateFuture(
            LibraryResult.ofItemList(ImmutableList.copyOf(cached.drop(1)), null),
        )
    }

    /**
     * Expands the container ids already on screen instead of following the
     * hub's key a second time, or null when the cache cannot be trusted for
     * this hub.
     *
     * **Unlike [cachedDecadeTracks] this still issues a request, and it must.**
     * A decade's browse list is tracks, so a hit replays it directly. A hub's
     * browse list is albums and artists -- browsable, with no stream -- so
     * replaying it would hand media3 a queue of items it cannot play. What the
     * cache saves is the round trip that re-fetches the containers, and for a
     * hub whose key carries `sort=random` it saves more than time: following
     * the key again returns a *different* set, so the mix would stop matching
     * what the driver is looking at.
     *
     * The guard is [cachedDecadeTracks]'s, unchanged in shape:
     * `PlexBrowseRepository.getHubContent` always writes this hub's own Mix row
     * at index 0, so a cached list identifies itself and anything else -- a
     * cold cache, another node's list -- falls back.
     *
     * The ids are read off each row's own prefix rather than from the hub's
     * declared type, so a hub mixing albums and artists sends both filters.
     *
     * Both lists coming out empty (a cache hit whose containers expanded to
     * nothing) also falls back, deliberately: an empty-empty call to
     * [PlexBrowseRepository.getHubTracksForIds] sends neither filter, which
     * Plex answers by shuffling the whole library rather than the hub.
     */
    private fun cachedHubTracks(hubKey: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>? {
        val cached = queueSourceCache[Constants.QUEUE_CACHED_SOURCE]
        if (cached?.firstOrNull()?.mediaId != Constants.MIX_HUB_ID + hubKey) return null

        val rows = cached.drop(1)
        val albums =
            rows
                .filter { it.mediaId.startsWith(Constants.ALBUM_ID) }
                .map { it.mediaId.removePrefix(Constants.ALBUM_ID) }
        val artists =
            rows
                .filter { it.mediaId.startsWith(Constants.ARTIST_ID) }
                .map { it.mediaId.removePrefix(Constants.ARTIST_ID) }
        if (albums.isEmpty() && artists.isEmpty()) return null

        Log.d(TAG, "Mixing hub $hubKey from the ${rows.size} rows already browsed")
        return browseRepository.getHubTracksForIds(albums, artists)
    }

    /**
     * A browse request failed while credentials exist. Plex answers a rejected
     * token with 401, which PlexBrowseRepository surfaces as
     * ERROR_PERMISSION_DENIED; anything else is a reachability problem and keeps
     * its original error, so the car does not tell the user to sign in when the
     * problem is the network.
     *
     * The substitution matters beyond the message: only the error CarSignInResolution
     * builds reaches the car as a tappable button (see its KDoc on
     * ERROR_SESSION_AUTHENTICATION_EXPIRED). Passing the original
     * ERROR_PERMISSION_DENIED straight through would leave a dead-end error.
     *
     * The Subsonic implementation had to issue a second request (a ping) to
     * learn this. Plex does not.
     */
    private fun classifyFailure(
        original: LibraryResult<ImmutableList<MediaItem>>?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val rejected = original?.resultCode == SessionError.ERROR_PERMISSION_DENIED
        return Futures.immediateFuture(
            if (rejected) {
                Log.d(TAG, "browse failed and the server rejected our credentials")
                CarSignInResolution.errorResult(context, R.string.car_sign_in_again)
            } else {
                original ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            },
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Android Auto — queue resolution
    // ─────────────────────────────────────────────────────────────

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.d(TAG, "onSetMediaItems")
        val firstItem =
            mediaItems.firstOrNull()
                ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

        Log.d(TAG, "mediaId = ${firstItem.mediaId}, startIndex = $startIndex, startPositionMs = $startPositionMs")

        val mixRow = isMixRow(firstItem)
        // Nothing here writes shuffleModeEnabled -- see the note on
        // [resolveQueueForItem]. The player's mode is the driver's to set with
        // the car's own control, and a browse tap is not a statement about it.
        val futureQueue = resolveQueueForItem(firstItem, mediaItems)

        return Futures.transform(
            futureQueue,
            { resolvedItems ->
                val items = resolvedItems ?: emptyList()
                val opening = openingPositionIn(items, firstItem, mixRow, startIndex)
                Log.d(TAG, "Opening at $opening of ${items.size} for ${firstItem.mediaId}")

                if (items.isNotEmpty()) {
                    val queue = QueueRepository()
                    // No detagging needed here: Queue.fromMediaItem reads its fields
                    // through PlexMediaMapper.readTrackFields, which never reads
                    // EXTRA_PARENT_ID, and the `queue` table has no parent_id column.
                    // A left-over parent tag on a queued item is inert.
                    queue.insertAll(items, true, 0)
                    // Marks the opening track as the one to resume at. The player
                    // will not do it: BaseMediaService records a last-played row on
                    // a seek or an automatic transition, and opening a fresh queue
                    // is neither, so without this a process death before the first
                    // track change would restore the queue at track one. Same
                    // executor as insertAll above, which is what orders it after
                    // the rows it marks.
                    items.getOrNull(opening)?.let { queue.setLastPlayedTimestamp(it.mediaId) }
                }

                MediaSession.MediaItemsWithStartPosition(items, opening, startPositionMs)
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "onAddMediaItems")
        val firstItem = mediaItems.firstOrNull() ?: return Futures.immediateFuture(mediaItems)

        Log.d(TAG, "mediaId = ${firstItem.mediaId}")
        val extras = firstItem.requestMetadata.extras ?: firstItem.mediaMetadata.extras
        Log.d(TAG, "extras: ${extras?.keySet()?.joinToString { key -> "$key=${extras.getString(key)}" } ?: "null"}")

        // This path cannot choose a start index -- it returns a bare list -- and
        // does not need to: the list it returns is already shuffled, so track
        // one is a random draw.
        return resolveQueueForItem(firstItem, mediaItems)
    }

    /**
     * Where playback opens in [items] -- the part of a browse tap the car leaves
     * entirely to us.
     *
     * It sends `C.INDEX_UNSET`, and media3 turns that into
     * `setMediaItems(items, resetPosition = true)`: open at the player's own
     * default position. With shuffle enabled that default is the head of the
     * *shuffled* order, so echoing the car's index back plays a track the user
     * did not press. Naming the tapped item's position is what makes the tap
     * mean anything.
     *
     * Shuffle being off used to hide that, and no longer hides it by accident:
     * the default position is item 0, and an inherited hack stashed the tapped
     * index in item 0's metadata for BaseMediaService to seek to when it came
     * round. That side channel is gone with this. It could only ever fire on the
     * item the player happened to open on, and under shuffle that was some other
     * track -- which it would then have yanked playback away from mid-listen.
     *
     * A Mix opens at 0. The queue arrived shuffled, so its head is already a
     * random draw, and drawing again would skip a prefix of the queue for
     * nothing.
     *
     * That 0 is explicit rather than left to the `else` branch, which would
     * otherwise be reached: the tapped Mix row is deliberately absent from
     * the queue it builds, so `indexOfFirst` returns -1 and the fallback is
     * `carStartIndex` -- `C.INDEX_UNSET` for a browse tap. media3 reads that as
     * "the player's default position", which is item 0 unless the driver has
     * shuffle on. The right answer, arrived at by accident; this chooses it.
     */
    private fun openingPositionIn(
        items: List<MediaItem>,
        tapped: MediaItem,
        mixRow: Boolean,
        carStartIndex: Int,
    ): Int =
        when {
            items.isEmpty() -> {
                carStartIndex
            }

            // The queue arrived shuffled, so its head is already a random draw.
            mixRow -> {
                0
            }

            // Absent means the queue this resolved to is not the list the row was
            // tapped in -- a stale browse cache is how that happens. The player's
            // default position is a better answer than a made-up one.
            else -> {
                items
                    .indexOfFirst { it.mediaId == tapped.mediaId }
                    .takeIf { it >= 0 } ?: carStartIndex
            }
        }

    /**
     * Resolves what a tapped row actually plays, shuffling a Mix row's tracks on
     * the way through.
     *
     * **Nothing in this class writes `shuffleModeEnabled`, and nothing should be
     * added that does.** A Mix is a queue handed over already shuffled; shuffle
     * is a mode the player is in. Those are separate, which is the whole reason
     * the rows are named "Mix" rather than "Shuffle this artist" -- tapping one
     * says nothing about what the transport control should be doing, so it
     * leaves the driver's toggle alone. See
     * `docs/decisions/2026-08-13-mix-rows-design.md`.
     *
     * Two writers used to live here and both are gone. One turned shuffle *off*
     * on every track tap, to clear a shuffle a row had turned on; with no row
     * turning it on, that only ever stomped a shuffle the driver had set by
     * hand. The other set it from the retired `car_shuffle` preference.
     *
     * The consequence worth knowing: with the car's shuffle already on, a Mix
     * plays a shuffled queue in shuffle order -- random either way, but the
     * visible queue will not match the play order. That is the driver's own
     * control doing exactly what it says, and taking it back would be the
     * behaviour this design removed.
     */
    private fun resolveQueueForItem(
        firstItem: MediaItem,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "Resolve queue for item")

        val extras = firstItem.requestMetadata.extras ?: firstItem.mediaMetadata.extras
        val parentId = extras?.getString(PlexMediaMapper.EXTRA_PARENT_ID)

        // Resolved once: this issues the request.
        val mixTracks = mixTracksFor(firstItem)

        val futureQueue: ListenableFuture<List<MediaItem>> =
            when {
                // Before the parent-tag branches: a Mix row carries no parent tag
                // and is not in any cache, so it would otherwise fall through to the
                // fallback below and "play" itself -- a row with no stream.
                //
                // The one place the artist row and the playlist row meet, which is
                // why the shuffle lives here rather than in either repository call.
                // The order handed over is the order heard, and it is the order the
                // car will show in the queue.
                mixTracks != null -> {
                    Futures.transform(
                        mixTracks,
                        { result -> (result?.value ?: emptyList()).shuffled() },
                        MoreExecutors.directExecutor(),
                    )
                }

                parentId?.startsWith(Constants.QUEUE_CACHED_SOURCE) == true -> {
                    Log.d(TAG, "Fetching AA list source tracks for $parentId")
                    val cachedItems = queueSourceCache[Constants.QUEUE_CACHED_SOURCE] ?: emptyList()
                    Futures.immediateFuture(cachedItems)
                }

                // Two unrelated callers land here, and the `localConfiguration` test
                // below is what separates them.
                //
                // A **browse tap** arrives from com.android.car.media, a different
                // process, so media3 has Bundle-serialized it and dropped the stream:
                // one item with no localConfiguration, which the session cache expands
                // into the list it was tapped in. That is the case the branch was
                // written for.
                //
                // **Continuous play** appends instant-mix tracks through
                // `browser.addMediaItems` (MediaManager.enqueue) and reaches the very
                // same code, but those MediaItems never leave this process and arrive
                // with their streams intact, so each one matches the first clause and
                // passes through untouched. Measured on an API 33 AAOS emulator: an
                // append of 25 mix tracks arrived as 25, all 25 carrying a URI, and
                // returned 25. See issue #70, which was filed on the assumption that
                // it collapsed to one and closed once this was measured.
                //
                // So the correct behaviour of continuous play rests on
                // localConfiguration surviving an in-process addMediaItems. If that
                // ever stopped holding, every mix track would miss both clauses,
                // resolvedItems would come out empty, and the `isEmpty` guard below
                // would append `firstItem` alone -- a mix that silently adds one song,
                // diagnosed three layers from here.
                else -> {
                    Log.d(TAG, "Fallback queue for item ${firstItem.mediaId}")
                    val resolvedItems = ArrayList<MediaItem>()
                    mediaItems.forEach { item ->
                        val sessionItem =
                            item.localConfiguration?.uri?.let { item }
                                ?: sessionMediaItemRepository.get(item.mediaId)?.let { session ->
                                    sessionMediaItemRepository.getSiblings(session.timestamp!!)
                                }
                        sessionItem?.let { resolved ->
                            when (resolved) {
                                is List<*> -> {
                                    resolvedItems.addAll(resolved.filterIsInstance<MediaItem>())
                                }

                                is MediaItem -> {
                                    resolvedItems.add(resolved)
                                }

                                else -> { /* ignore */ }
                            }
                        }
                    }
                    if (resolvedItems.isEmpty()) resolvedItems.add(firstItem)
                    Futures.immediateFuture(resolvedItems)
                }
            }

        return futureQueue
    }

    // ─────────────────────────────────────────────────────────────
    // Android Auto — search
    // ─────────────────────────────────────────────────────────────

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 60, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        // Search tracks carry no parent tag, so remembering them here is the only
        // thing that lets tapping one play the rest of the results after it --
        // see rememberTracks.
        return Futures.transform(
            MediaBrowserTree.search(query),
            { result ->
                if (result != null && result.resultCode == LibraryResult.RESULT_SUCCESS) {
                    rememberTracks(result.value ?: emptyList())
                }
                result
            },
            MoreExecutors.directExecutor(),
        )
    }
}
