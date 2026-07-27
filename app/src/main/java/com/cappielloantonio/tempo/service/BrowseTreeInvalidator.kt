package com.cappielloantonio.tempo.service

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.util.ConstantsAA
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors

/**
 * Lets the car sign-in screen tell the media session that the browse tree is
 * worth asking for again.
 *
 * Sign-in happens in an activity; the browse tree lives in the media service. They
 * are in the same process, so a nullable session handle owned by the service is
 * enough -- no binding, no broadcast.
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
     * [MediaLibraryServiceCallback.onGetItem], which lets `onSubscribe` succeed),
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
     * in different components (currently [com.cappielloantonio.tempo.ui.activity.CarSignInActivity]),
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
            MediaBrowserTree.getChildren(ConstantsAA.ROOT_ID),
            object : FutureCallback<LibraryResult<ImmutableList<MediaItem>>> {
                override fun onSuccess(result: LibraryResult<ImmutableList<MediaItem>>?) {
                    val childCount = result?.value?.size ?: 0
                    current.notifyChildrenChanged(ConstantsAA.ROOT_ID, childCount, null)
                    // This confirms the call was made, not that any subscribed controller
                    // received it -- notifyChildrenChanged doesn't report delivery.
                    Log.d(TAG, "called notifyChildrenChanged(rootID) with $childCount children")
                }

                override fun onFailure(t: Throwable) {
                    Log.d(TAG, "could not count root children; calling notifyChildrenChanged anyway", t)
                    current.notifyChildrenChanged(ConstantsAA.ROOT_ID, 0, null)
                }
            },
            MoreExecutors.directExecutor()
        )
    }
}
