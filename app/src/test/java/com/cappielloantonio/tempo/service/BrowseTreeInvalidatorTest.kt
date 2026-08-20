package com.cappielloantonio.tempo.service

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers the fix for the stale-info-row bug: signing in or out used to call
 * only [BrowseTreeInvalidator.invalidateRoot], which stopped reaching the
 * info row once [MediaBrowserTree.buildTree] made the root's own children
 * (the four tabs) identical signed in or out -- see the design doc's
 * Consequences section. [BrowseTreeInvalidator.invalidateTree] is what
 * CarHostActivity's `onLoginSuccess()`/`onSignedOut()` call instead; this
 * pins that it actually reaches the root *and* every tab, not just the root.
 *
 * `MediaLibrarySession` is mockable (Mockito 5's default inline mock maker
 * handles the non-final `notifyChildrenChanged` methods; `LibraryPickerCommitTest`
 * already relies on the same setup for its `Player` mock), so this is real
 * coverage of the notification calls, not just of `unitTests.returnDefaultValues`
 * stubbing something out.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BrowseTreeInvalidatorTest {
    private lateinit var session: MediaLibrarySession

    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across test
        // methods (see BrowseTabOrderFixture's KDoc), so a saved order left
        // by another test would otherwise leak in here.
        BrowseTabOrderFixture.clearSavedOrder()
        session = mock()
        MediaBrowserTree.initialize(App.getContext(), mock())
        BrowseTreeInvalidator.attach(session)
    }

    @After
    fun tearDown() {
        // BrowseTreeInvalidator is a process-wide singleton; leaving a mock
        // attached would leak into whatever test class runs next.
        BrowseTreeInvalidator.detach()
        BrowseTabOrderFixture.clearSavedOrder()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `invalidateTree notifies the root and all four tabs`() {
        BrowseTreeInvalidator.invalidateTree()

        // invalidateRoot() resolves synchronously (see its own KDoc), but
        // invalidateNode() posts to the main looper -- Robolectric's is
        // paused by default, so the four tab calls need draining by hand,
        // the same pattern LibraryPickerCommitTest uses for stopPlayback().
        idleMainLooper()

        verify(session).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.PLAYLIST_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ARTISTS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ALBUMS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.MORE_ID), any(), eq(null))
    }

    @Test
    fun `invalidateRoot alone -- the pre-fix call -- never reaches the tabs`() {
        // Pins the bug this fix closes: the old call site's behavior, kept
        // here as a regression guard so a future refactor that quietly
        // swaps invalidateTree() back for invalidateRoot() fails a test
        // instead of shipping the stale info row again.
        BrowseTreeInvalidator.invalidateRoot()
        idleMainLooper()

        verify(session).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session, never()).notifyChildrenChanged(eq(Constants.PLAYLIST_ID), any(), eq(null))
        verify(session, never()).notifyChildrenChanged(eq(Constants.ARTISTS_ID), any(), eq(null))
        verify(session, never()).notifyChildrenChanged(eq(Constants.ALBUMS_ID), any(), eq(null))
        verify(session, never()).notifyChildrenChanged(eq(Constants.MORE_ID), any(), eq(null))
    }

    @Test
    fun `invalidateTree notifies a promoted destination instead of the tab it displaced`() {
        // Regression guard for the bug this fix closes: the old
        // implementation hardcoded Playlists/Artists/Albums/More, so a
        // destination promoted into the top three -- Decades here -- was
        // never in that list and never got notified. It would keep
        // rendering the signed-out info row after a sign-in until the user
        // navigated away and back. This must fail if the ids are hardcoded
        // again.
        Preferences.setBrowseTabOrder(
            listOf(Constants.DECADES_ID, Constants.ARTISTS_ID, Constants.ALBUMS_ID),
        )

        BrowseTreeInvalidator.invalidateTree()
        idleMainLooper()

        verify(session).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.DECADES_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ARTISTS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ALBUMS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.MORE_ID), any(), eq(null))
        // Playlists was demoted under More by this order and must not be
        // invalidated as if it were still a root tab.
        verify(session, never()).notifyChildrenChanged(eq(Constants.PLAYLIST_ID), any(), eq(null))
    }

    @Test
    fun `no live session makes invalidateTree a silent no-op`() {
        BrowseTreeInvalidator.detach()

        // Must not throw just because nothing is attached -- e.g. the media
        // service is not running when sign-in/out happens.
        BrowseTreeInvalidator.invalidateTree()
        idleMainLooper()

        verify(session, never()).notifyChildrenChanged(any(), any(), any())
    }
}
