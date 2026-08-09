package com.cappielloantonio.tempo.service

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import com.cappielloantonio.tempo.util.ConstantsAA
import com.cappielloantonio.tempo.util.CredentialGate
import com.cappielloantonio.tempo.util.Preferences
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TAG = "MediaLibrarySessionCallback"
private val queueSourceCache = ConcurrentHashMap<String, List<MediaItem>>()

@UnstableApi
class MediaLibrarySessionCallback(
    context: Context,
    service: BaseMediaService,
    private val browseRepository: PlexBrowseRepository,
    private val sessionMediaItemRepository: SessionMediaItemRepository
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
        params: MediaLibraryService.LibraryParams?
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
        mediaId: String
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
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!CredentialGate.isSignedIn() && parentId != ConstantsAA.ROOT_ID) {
            Log.d(TAG, "onGetChildren blocked for $parentId: no usable credentials")
            // A row, not an error, for every parentId except the root.
            // Signed out is a state, not a failure, and an error's Connect
            // button lands under the mini player on head units that always
            // show one. The root is exempted because the browse root is a
            // tab bar, not a list -- measured on an AAOS API 33 emulator, see
            // MediaBrowserTree.signedOutRow's KDoc -- so it falls through
            // below to the same four static tabs a signed-in car gets;
            // MediaBrowserTree.buildTree is static and needs no credentials.
            // The car auto-opens the first tab, landing the user on this row
            // immediately. classifyFailure still returns an error for
            // credentials that were rejected mid-use -- that one is a
            // failure.
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    MediaBrowserTree.signedOutRow(context),
                    null
                )
            )
        }

        Log.d(TAG, "onGetChildren parentId = $parentId")

        val future = MediaBrowserTree.getChildren(parentId)

        return Futures.transformAsync(future, { result ->
            if (result != null && result.resultCode == LibraryResult.RESULT_SUCCESS) {
                val items = result.value ?: emptyList()
                queueSourceCache[ConstantsAA.QUEUE_CACHED_SOURCE] = items
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
        // The shuffle row is playable but is not a track: it has no ratingKey and
        // no stream, so caching it would put a row in session_media_item that
        // round-trips into an unplayable MediaItem -- the same reason browsable
        // rows are excluded here.
        val tracks = items.filter {
            it.mediaMetadata.isPlayable == true && !isShuffleRow(it)
        }
        if (tracks.isNotEmpty()) sessionMediaItemRepository.cache(tracks)
    }

    /**
     * Pure prefix test, deliberately not "does [shuffleTracksFor] return
     * something": that issues a request, and this runs against every row of
     * every browse list from [rememberTracks].
     */
    private fun isShuffleRow(item: MediaItem) =
        item.mediaId.startsWith(ConstantsAA.SHUFFLE_ARTIST_ID) ||
            item.mediaId.startsWith(ConstantsAA.SHUFFLE_PLAYLIST_ID) ||
            item.mediaId.startsWith(ConstantsAA.SHUFFLE_DECADE_ID)

    /**
     * Fetches the tracks a tapped shuffle row stands for, or null if the item is
     * not a shuffle row. **Issues a network request** -- call it once per tap --
     * except for the decade branch's cache hit, which is already-complete and
     * issues none; see [cachedDecadeTracks].
     *
     * Dispatch is on the id prefix, which is the only thing the car sends back:
     * it rebuilds the item from the media id alone, so the extras the row was
     * built with are gone by the time it arrives here.
     */
    private fun shuffleTracksFor(
        item: MediaItem
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>? {
        val id = item.mediaId
        return when {
            id.startsWith(ConstantsAA.SHUFFLE_ARTIST_ID) -> {
                val artist = id.removePrefix(ConstantsAA.SHUFFLE_ARTIST_ID)
                Log.d(TAG, "Fetching every track by artist $artist to shuffle")
                browseRepository.getArtistTracks(artist)
            }

            id.startsWith(ConstantsAA.SHUFFLE_PLAYLIST_ID) -> {
                val playlist = id.removePrefix(ConstantsAA.SHUFFLE_PLAYLIST_ID)
                Log.d(TAG, "Fetching every track in playlist $playlist to shuffle")
                browseRepository.getPlaylistTracksForShuffle(playlist)
            }

            id.startsWith(ConstantsAA.SHUFFLE_DECADE_ID) -> {
                val decade = id.removePrefix(ConstantsAA.SHUFFLE_DECADE_ID)
                cachedDecadeTracks(decade)
                    ?: run {
                        Log.d(TAG, "Fetching a random sample of the ${decade}s to shuffle")
                        browseRepository.getDecadeTracksForShuffle(decade)
                    }
            }

            else -> null
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
     * always puts the decade's own shuffle row at index 0, so the cached list
     * identifies itself: a match there is certain enough to trust, and its
     * absence (empty cache, cold after a process restart, or a different node's
     * list) is the signal to fall back rather than guess.
     *
     * `drop(1)` rather than filtering by [isShuffleRow]: index 0 is the row by
     * construction, and dropping exactly the item the guard just matched is
     * narrower than a predicate that could also drop something else. The queue
     * must not contain the shuffle row -- it is playable with no stream, and a
     * queue holding it would "play" a track that does not exist.
     */
    private fun cachedDecadeTracks(
        decade: String
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>? {
        val cached = queueSourceCache[ConstantsAA.QUEUE_CACHED_SOURCE]
        if (cached?.firstOrNull()?.mediaId != ConstantsAA.SHUFFLE_DECADE_ID + decade) return null

        Log.d(TAG, "Serving decade $decade shuffle from the cached browse list")
        return Futures.immediateFuture(
            LibraryResult.ofItemList(ImmutableList.copyOf(cached.drop(1)), null)
        )
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
        original: LibraryResult<ImmutableList<MediaItem>>?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val rejected = original?.resultCode == SessionError.ERROR_PERMISSION_DENIED
        return Futures.immediateFuture(
            if (rejected) {
                Log.d(TAG, "browse failed and the server rejected our credentials")
                CarSignInResolution.errorResult(context, R.string.car_sign_in_again)
            } else {
                original ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
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
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.d(TAG, "onSetMediaItems")
        val firstItem = mediaItems.firstOrNull()
            ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

        Log.d(TAG, "mediaId = ${firstItem.mediaId}, startIndex = $startIndex, startPositionMs = $startPositionMs")

        val shuffleRow = isShuffleRow(firstItem)
        // Read once and threaded through: the queue's order, the player's toggle
        // and the opening position are three answers that have to agree, and
        // re-reading for each would let a Settings write land between them.
        val carShuffle = Preferences.isCarShuffleEnabled()
        setShuffleForTap(shuffleRow && carShuffle, mediaSession.player)

        val futureQueue = resolveQueueForItem(firstItem, mediaItems, carShuffle)

        return Futures.transform(
            futureQueue,
            { resolvedItems ->
                val items = resolvedItems ?: emptyList()
                val opening = openingPositionIn(items, firstItem, shuffleRow, carShuffle, startIndex)
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
            MoreExecutors.directExecutor()
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "onAddMediaItems")
        val firstItem = mediaItems.firstOrNull() ?: return Futures.immediateFuture(mediaItems)

        Log.d(TAG, "mediaId = ${firstItem.mediaId}")
        val extras = firstItem.requestMetadata.extras ?: firstItem.mediaMetadata.extras
        Log.d(TAG, "extras: ${extras?.keySet()?.joinToString { key -> "$key=${extras.getString(key)}" } ?: "null"}")

        // This path cannot choose a start index -- it returns a bare list -- so a
        // car that adds rather than sets gets shuffled continuation from track
        // one instead of a random opener. With the car's shuffle off that costs
        // nothing: the list it returns is already shuffled, so track one is a
        // random draw.
        val carShuffle = Preferences.isCarShuffleEnabled()
        setShuffleForAddedRow(firstItem, mediaSession.player, carShuffle)

        return resolveQueueForItem(firstItem, mediaItems, carShuffle)
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
     * A shuffle row is the one tap whose opener is ours to choose: shuffle mode
     * orders what comes *after* the current item, so a row that opened at item 0
     * would shuffle the same artist from the same song every time.
     *
     * That paragraph describes the deferring branch. With "use the car's
     * shuffle" off the queue arrived shuffled and the player walks it in order,
     * so the opener is 0 -- the head of a shuffled list is already a random
     * draw, and drawing again would skip a prefix of the queue for nothing.
     *
     * That 0 is explicit rather than left to the `else` branch, which would
     * otherwise be reached: the tapped shuffle row is deliberately absent from
     * the queue it builds, so `indexOfFirst` returns -1 and the fallback is
     * `carStartIndex` -- `C.INDEX_UNSET` for a browse tap. media3 reads that as
     * "the player's default position", which with shuffle off is item 0. The
     * right answer, arrived at by accident; this chooses it.
     */
    private fun openingPositionIn(
        items: List<MediaItem>,
        tapped: MediaItem,
        shuffleRow: Boolean,
        carShuffle: Boolean,
        carStartIndex: Int
    ): Int = when {
        items.isEmpty() -> carStartIndex
        shuffleRow && carShuffle -> Random.nextInt(items.size)
        // The queue arrived shuffled, so its head is already a random draw.
        shuffleRow -> 0
        // Absent means the queue this resolved to is not the list the row was
        // tapped in -- a stale browse cache is how that happens. The player's
        // default position is a better answer than a made-up one.
        else -> items.indexOfFirst { it.mediaId == tapped.mediaId }
            .takeIf { it >= 0 } ?: carStartIndex
    }

    /**
     * Sets the player's shuffle from the tap: on only for a shuffle row that
     * "use the car's shuffle" says to defer to, off for anything else.
     *
     * With that setting off the caller passes false for a shuffle row too. That
     * is what makes the pre-shuffled queue play in the order it was handed over
     * instead of being shuffled a second time by the player.
     *
     * Total rather than enable-only, and that is the whole point -- shuffle used
     * to stick, because the only thing that ever wrote it turned it on. There is
     * no third case to handle: tracks and the three shuffle rows are the only
     * items in the browse tree with `isPlayable` set, so every other row
     * navigates and never reaches here.
     *
     * Called from [onSetMediaItems] rather than from [resolveQueueForItem],
     * because that override runs on the session's application thread while the
     * queue future completes on whichever thread the coroutine finished on --
     * and the player may only be touched from the former.
     */
    private fun setShuffleForTap(shuffling: Boolean, player: Player) {
        Log.d(TAG, "tap resolved: shuffle -> $shuffling")
        player.shuffleModeEnabled = shuffling
    }

    /**
     * Sets the player's shuffle from an added shuffle row, and writes nothing
     * for anything else.
     *
     * **Only a shuffle row ever reaches the write**, and that early return is
     * the guarantee this function is shaped around. [onAddMediaItems] is not
     * only a browse-tap path: MediaManager's continuous play appends
     * instant-mix tracks through `browser.addMediaItems`, which arrives here
     * too. A setter that wrote on every call would clear shuffle *mid-listen*
     * every time the queue topped itself up -- and that top-up fires precisely
     * when a queue is running low, which is the long shuffle-this-artist
     * session it would ruin. A mix track is never a shuffle row, so continuous
     * play returns before touching the player, exactly as it did when this was
     * enable-only.
     *
     * Enable-only was the older way of guaranteeing that, and it is no longer
     * enough. `shuffleModeEnabled` outlives the process -- BaseMediaService
     * persists it in `onShuffleModeEnabledChanged` and restores it when the
     * player is built -- so declining to write leaves whatever the last listen
     * left, which is routinely `true`. Tap the row with "use the car's shuffle"
     * on, turn the setting off, tap the row again: [resolveQueueForItem] hands
     * over a shuffled queue and the player shuffles it a second time. That
     * double shuffle is the jumping-around the setting exists to remove, so for
     * a shuffle row the write has to be total.
     *
     * Called from [onAddMediaItems] rather than from [resolveQueueForItem],
     * because that override runs on the session's application thread while the
     * queue future completes on whichever thread the coroutine finished on --
     * and the player may only be touched from the former.
     */
    private fun setShuffleForAddedRow(
        firstItem: MediaItem,
        player: Player,
        carShuffle: Boolean
    ) {
        if (!isShuffleRow(firstItem)) return
        Log.d(TAG, "shuffle row added: shuffle -> $carShuffle")
        player.shuffleModeEnabled = carShuffle
    }

    private fun resolveQueueForItem(
        firstItem: MediaItem,
        mediaItems: List<MediaItem>,
        carShuffle: Boolean
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "Resolve queue for item")

        val extras = firstItem.requestMetadata.extras ?: firstItem.mediaMetadata.extras
        val parentId = extras?.getString(PlexMediaMapper.EXTRA_PARENT_ID)

        // Resolved once: this issues the request.
        val shuffleTracks = shuffleTracksFor(firstItem)

        val futureQueue: ListenableFuture<List<MediaItem>> = when {
            // Before the parent-tag branches: a shuffle row carries no parent tag
            // and is not in any cache, so it would otherwise fall through to the
            // fallback below and "play" itself -- a row with no stream.
            //
            // The one place the artist row and the playlist row meet, which is
            // why the shuffle lives here rather than in either repository call.
            // With the setting off the player is not shuffling, so the order
            // handed over is the order heard.
            shuffleTracks != null -> Futures.transform(
                shuffleTracks,
                { result ->
                    val tracks = result?.value ?: emptyList()
                    if (carShuffle) tracks else tracks.shuffled()
                },
                MoreExecutors.directExecutor()
            )

            parentId?.startsWith(ConstantsAA.QUEUE_CACHED_SOURCE) == true -> {
                Log.d(TAG, "Fetching AA list source tracks for $parentId")
                val cachedItems = queueSourceCache[ConstantsAA.QUEUE_CACHED_SOURCE] ?: emptyList()
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
                    val sessionItem = item.localConfiguration?.uri?.let { item }
                        ?: sessionMediaItemRepository.get(item.mediaId)?.let { session ->
                            sessionMediaItemRepository.getSiblings(session.timestamp!!)
                        }
                    sessionItem?.let { resolved ->
                        when (resolved) {
                            is List<*> -> resolvedItems.addAll(resolved.filterIsInstance<MediaItem>())
                            is MediaItem -> resolvedItems.add(resolved)
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
        params: MediaLibraryService.LibraryParams?
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
        params: MediaLibraryService.LibraryParams?
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
            MoreExecutors.directExecutor()
        )
    }
}
