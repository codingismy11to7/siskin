package com.cappielloantonio.tempo.service

import android.content.Context
import android.os.Bundle
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
import com.cappielloantonio.tempo.util.ConstantsAA
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
    browseRepository: PlexBrowseRepository,
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
        if (!CredentialGate.isSignedIn()) {
            Log.d(TAG, "onGetChildren blocked for $parentId: no usable credentials")
            return Futures.immediateFuture(
                CarSignInResolution.errorResult(context, R.string.car_sign_in_required)
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
        val tracks = items.filter { it.mediaMetadata.isPlayable == true }
        if (tracks.isNotEmpty()) sessionMediaItemRepository.cache(tracks)
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

        val futureQueue = resolveQueueForItem(firstItem, mediaItems)

        return Futures.transform(
            futureQueue,
            { resolvedItems ->
                if (!resolvedItems.isNullOrEmpty()) {
                    // No detagging needed here: Queue.fromMediaItem reads its fields
                    // through PlexMediaMapper.readTrackFields, which never reads
                    // EXTRA_PARENT_ID, and the `queue` table has no parent_id column.
                    // A left-over parent tag on a queued item is inert.
                    QueueRepository().insertAll(resolvedItems, true, 0)
                }
                MediaSession.MediaItemsWithStartPosition(
                    resolvedItems ?: emptyList(),
                    startIndex,
                    startPositionMs
                )
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

        return resolveQueueForItem(firstItem, mediaItems)
    }

    private fun resolveQueueForItem(
        firstItem: MediaItem,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "Resolve queue for item")

        val extras = firstItem.requestMetadata.extras ?: firstItem.mediaMetadata.extras
        val parentId = extras?.getString(PlexMediaMapper.EXTRA_PARENT_ID)

        val futureQueue: ListenableFuture<List<MediaItem>> = when {
            parentId?.startsWith(ConstantsAA.QUEUE_CACHED_SOURCE) == true -> {
                Log.d(TAG, "Fetching AA list source tracks for $parentId")
                val cachedItems = queueSourceCache[ConstantsAA.QUEUE_CACHED_SOURCE] ?: emptyList()
                Futures.immediateFuture(cachedItems)
            }

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

        return Futures.transform(
            futureQueue,
            { resolvedItems ->
                if (resolvedItems.isEmpty()) return@transform resolvedItems

                val startIndex = resolvedItems.indexOfFirst { it.mediaId == firstItem.mediaId }
                Log.d(TAG, "Start index for clicked item ${firstItem.mediaId} = $startIndex")
                if (startIndex <= 0) return@transform resolvedItems

                QueueRepository().insertAll(resolvedItems, true, 0)

                val firstResolved = resolvedItems[0]
                val extras = (firstResolved.mediaMetadata.extras ?: Bundle()).apply {
                    putInt(Constants.AA_START_INDEX, startIndex)
                }
                val newFirstResolved = firstResolved.buildUpon()
                    .setMediaMetadata(firstResolved.mediaMetadata.buildUpon().setExtras(extras).build())
                    .build()

                val updatedResolved = resolvedItems.toMutableList()
                updatedResolved[0] = newFirstResolved
                updatedResolved
            },
            MoreExecutors.directExecutor()
        )
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
