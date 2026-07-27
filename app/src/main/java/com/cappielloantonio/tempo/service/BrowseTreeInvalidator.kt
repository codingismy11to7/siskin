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
     * Rebuilds the tree and tells every connected browser the root changed. A no-op
     * when the service is not running: there is then nobody to tell, and the car
     * will build a fresh tree when it next connects.
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
                    Log.d(TAG, "invalidated root with $childCount children")
                }

                override fun onFailure(t: Throwable) {
                    Log.d(TAG, "could not count root children; notifying anyway", t)
                    current.notifyChildrenChanged(ConstantsAA.ROOT_ID, 0, null)
                }
            },
            MoreExecutors.directExecutor()
        )
    }
}
