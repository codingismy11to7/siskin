package com.cappielloantonio.tempo.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.CredentialGate
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Proves the installed APK's [MediaService] binds and serves its signed-out
 * browse tree on a real Android Automotive image.
 *
 * Assertions stay thin: MediaLibrarySessionCallbackSignedOutTest already covers
 * the tree's *content* on the JVM. This tier exists for what Robolectric cannot
 * reach -- that the APK installs, the manifest resolves, and the service starts
 * under a real Android runtime.
 *
 * It is not proof of IPC: instrumentation shares the app's process, so
 * bindService returns the local Binder and the AIDL calls short-circuit. See
 * the 2026-09-07 emulator smoke test design.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MediaServiceBindTest {
    private lateinit var browser: MediaBrowser

    @Before
    fun connectToService() {
        // Never signs the device out itself: the AVD is reused for manual QA
        // against real Plex servers, and clearing that session as a side
        // effect of a test is not this suite's call to make.
        assertFalse(
            "MediaServiceBindTest requires a signed-out device; sign out in " +
                "the car's settings, or run against a fresh AVD.",
            CredentialGate.isSignedIn(),
        )

        val context: Context = ApplicationProvider.getApplicationContext()

        // MediaBrowser must be built on a Looper thread and every later call
        // made on that same thread, so each call hops to main and the test
        // thread blocks on the returned future. Blocking on main would
        // deadlock.
        lateinit var pending: ListenableFuture<MediaBrowser>
        onMain {
            val token = SessionToken(context, ComponentName(context, MediaService::class.java))
            pending = MediaBrowser.Builder(context, token).buildAsync()
        }
        browser = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Mirrors the real media3 lifecycle: the root is always requested
        // before anything subscribes to it, and that request is what populates
        // MediaBrowserTree's treeNodes.
        lateinit var root: ListenableFuture<LibraryResult<MediaItem>>
        onMain { root = browser.getLibraryRoot(null) }
        assertEquals(
            LibraryResult.RESULT_SUCCESS,
            root.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).resultCode,
        )
    }

    @After
    fun releaseBrowser() {
        if (::browser.isInitialized) onMain { browser.release() }
    }

    @Test
    fun rootReturnsFourTabsEndingInMore() {
        val children = childrenOf(Constants.ROOT_ID)

        assertEquals(4, children.size)
        assertEquals(Constants.MORE_ID, children.last().mediaId)
    }

    @Test
    fun nonRootParentReturnsASingleBrowsableRow() {
        listOf(
            Constants.PLAYLIST_ID,
            Constants.ARTISTS_ID,
            Constants.ALBUMS_ID,
            Constants.MORE_ID,
        ).forEach { parentId ->
            val row = childrenOf(parentId).single()

            assertEquals(MediaBrowserTree.SIGNED_OUT_ROW_ID, row.mediaId)
            assertEquals(true, row.mediaMetadata.isBrowsable)
            assertEquals(false, row.mediaMetadata.isPlayable)
        }
    }

    private fun childrenOf(parentId: String): ImmutableList<MediaItem> {
        lateinit var pending: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
        onMain { pending = browser.getChildren(parentId, 0, PAGE_SIZE, null) }

        val result = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)

        val value = result.value
        assertTrue("no children for $parentId", value != null)
        return value!!
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val PAGE_SIZE = 100
    }
}
