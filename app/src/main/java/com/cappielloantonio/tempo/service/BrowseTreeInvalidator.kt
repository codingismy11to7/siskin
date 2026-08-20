package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors

/**
 * The process's handle on the live [MediaLibrarySession]: it lets components
 * outside the media service tell the car the browse tree is worth asking for
 * again, and -- because the player lives behind that session -- stop playback.
 *
 * Sign-in happens in an activity and the library picker in a repository; the
 * session lives in the media service. They are in the same process, so a
 * nullable session handle owned by the service is enough -- no binding, no
 * broadcast. New callers belong here rather than in a second singleton holding
 * the same session.
 */
@UnstableApi
object BrowseTreeInvalidator {
    private const val TAG = "BrowseTreeInvalidator"

    @Volatile
    private var session: MediaLibrarySession? = null

    fun attach(mediaLibrarySession: MediaLibrarySession) {
        session = mediaLibrarySession
    }

    fun detach() {
        session = null
    }

    /**
     * Rebuilds the tree and tells every browser currently *subscribed* to the root
     * that its children changed. Verified end-to-end against the AAOS emulator's
     * `com.android.car.media`: with a live root subscription in place (see
     * [MediaLibrarySessionCallback.onGetItem], which lets `onSubscribe` succeed),
     * this call makes the car re-fetch and redisplay the root without backing out.
     *
     * Delivery depends on a subscription actually existing at call time -- there are
     * two distinct no-op cases, both silent by design because there is then nobody to
     * tell: no live session (service not running; logged below), or a live session
     * with no controller currently subscribed to root (e.g. media3 dropped the
     * subscription, or the car never subscribed in the first place). Either way the
     * car will pick up the current tree the next time it connects or re-subscribes.
     *
     * Must be called on the main thread. This is a process-wide singleton with callers
     * in different components (currently [com.cappielloantonio.tempo.ui.activity.CarHostActivity]),
     * and [MoreExecutors.directExecutor] below runs `notifyChildrenChanged` on whatever
     * thread completes [MediaBrowserTree.getChildren]'s future -- correct today only
     * because that resolves synchronously on the calling thread, which callers keep on
     * the main thread (e.g. `onLoginSuccess()` is an Activity callback).
     */
    fun invalidateRoot() {
        val current = session
        if (current == null) {
            Log.d(TAG, "no live session; nothing to invalidate")
            return
        }

        MediaBrowserTree.buildTree()

        // getChildren(ROOT_ID) completes synchronously -- the root node's children
        // are already in memory -- but resolve it through a callback rather than
        // blocking the main thread on Future.get(). directExecutor keeps
        // notifyChildrenChanged on the caller's thread, which is where the session
        // expects it.
        Futures.addCallback(
            MediaBrowserTree.getChildren(Constants.ROOT_ID),
            object : FutureCallback<LibraryResult<ImmutableList<MediaItem>>> {
                override fun onSuccess(result: LibraryResult<ImmutableList<MediaItem>>?) {
                    val childCount = result?.value?.size ?: 0
                    current.notifyChildrenChanged(Constants.ROOT_ID, childCount, null)
                    // This confirms the call was made, not that any subscribed controller
                    // received it -- notifyChildrenChanged doesn't report delivery.
                    Log.d(TAG, "called notifyChildrenChanged(rootID) with $childCount children")
                }

                override fun onFailure(t: Throwable) {
                    Log.d(TAG, "could not count root children; calling notifyChildrenChanged anyway", t)
                    current.notifyChildrenChanged(Constants.ROOT_ID, 0, null)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * Tells the car that one node's children changed.
     *
     * Needed because the car caches a browse list and does not re-fetch it when
     * the user navigates back into it -- without this, the tick marking the
     * selected library is drawn from whatever was current when that screen was
     * first loaded, and silently lies.
     *
     * Posts to the main thread rather than requiring callers to be on it: unlike
     * [invalidateRoot], whose callers are Activity callbacks, this one is called
     * from PlexBrowseRepository's IO scope.
     */
    fun invalidateNode(
        nodeId: String,
        childCount: Int,
    ) {
        val current =
            session ?: run {
                Log.d(TAG, "no live session; nothing to invalidate for $nodeId")
                return
            }
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "notifyChildrenChanged($nodeId, $childCount)")
            current.notifyChildrenChanged(nodeId, childCount, null)
        }
    }

    /**
     * Invalidates the root plus each of the current root tabs and More.
     *
     * Which ids those are is not fixed: since the browse tabs became
     * user-orderable (see
     * docs/decisions/2026-08-14-customizable-browse-tabs-design.md), the
     * three root tabs are whatever [BrowseTabOrder.resolve] returns for the
     * saved order, not a hardcoded Playlists/Artists/Albums -- a promoted
     * destination like Discover or Decades has to be invalidated exactly
     * like the tabs it displaced, or it keeps showing the signed-out info
     * row after a sign-in until the user navigates away and back.
     *
     * [invalidateRoot] alone stopped being enough for sign-in and sign-out
     * once [MediaBrowserTree.buildTree] made the root return the same tabs
     * signed in or out: `notifyChildrenChanged(ROOT_ID, ...)` then sees
     * byte-identical children on both transitions and gives the car nothing
     * to act on. The info row that used to live at the root now lives one
     * level down, inside each tab, so both transitions have to reach that
     * level to get the car to redraw it -- the same hole [invalidateNode] was
     * written to close for the library-switch case (see its KDoc), needed
     * here for the identical reason at the root tabs plus More instead of
     * one. Sign-in and sign-out both need this same sequence, which is why it
     * lives here once instead of as separate [invalidateNode] calls
     * duplicated at each call site.
     *
     * childCount is 0 for every tab: the same placeholder [invalidateRoot]
     * itself falls back to when it cannot count the root's children, and the
     * same value `com.cappielloantonio.tempo.repository.LibraryPickerRepository.selectLibrary`
     * already passes for a node whose real count isn't in hand. The car
     * re-queries `onGetChildren` for the actual list regardless of what this
     * reports, so a precise count buys nothing worth a network round trip per
     * tab.
     *
     * Same threading contract as its parts, because it is only those parts:
     * must be called on the main thread -- [invalidateRoot] requires it and
     * runs synchronously -- and each [invalidateNode] call posts its own work
     * to the main thread rather than requiring it.
     */
    fun invalidateTree() {
        invalidateRoot()
        val resolved = BrowseTabOrder.resolve(Preferences.getBrowseTabOrder())
        BrowseTabOrder.rootTabs(resolved).forEach { invalidateNode(it, 0) }
        invalidateNode(Constants.MORE_ID, 0)
    }

    /**
     * Stops playback and empties the player's timeline.
     *
     * Called when the saved queue has just been discarded because the user
     * switched Plex servers. Deleting the Room queue is only half of it: the
     * timeline ExoPlayer is actually playing from is in memory and still holds
     * the old server's stream URLs, so the current track would play on, the next
     * one would 404, and [BaseMediaService]'s onPlayerError recovery would
     * re-prepare that dead URL every five seconds.
     *
     * Posts to the main thread for the same reason [invalidateNode] does, with
     * one extra: Player calls *must* be made on the thread the player was built
     * on, and this is called from whatever thread the car's browse callback runs
     * on.
     */
    fun stopPlayback() {
        val current =
            session ?: run {
                Log.d(TAG, "no live session; nothing to stop")
                return
            }
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "stopping playback and clearing the player's items")
            current.player.stop()
            current.player.clearMediaItems()
        }
    }
}
