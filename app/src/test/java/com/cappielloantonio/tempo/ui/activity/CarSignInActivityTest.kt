package com.cappielloantonio.tempo.ui.activity

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaBrowserTree
import com.cappielloantonio.tempo.util.ConstantsAA
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers CarSignInActivity's two LoginHost callbacks directly, on a bare
 * `CarSignInActivity()` instance rather than one built through Robolectric's
 * full Activity lifecycle (`buildActivity(...).create()`): onLoginSuccess()
 * and onSignedOut() only touch BrowseTreeInvalidator's static session and
 * (for onLoginSuccess) call finish(), none of which needs onCreate() to have
 * run -- onCreate() pulls in ViewModelProvider, a fragment transaction and
 * PlexSignInViewModel.open()'s network calls, none of which this test cares
 * about, so skipping it keeps the test scoped to what it is actually
 * checking. See BrowseTreeInvalidatorTest for direct coverage of
 * invalidateTree() itself.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CarSignInActivityTest {

    private lateinit var session: MediaLibrarySession
    private lateinit var player: Player

    @Before
    fun setUp() {
        player = mock()
        session = mock()
        whenever(session.player).thenReturn(player)
        MediaBrowserTree.initialize(App.getContext(), mock())
        BrowseTreeInvalidator.attach(session)
    }

    @After
    fun tearDown() {
        BrowseTreeInvalidator.detach()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `onLoginSuccess invalidates the root and all four tabs`() {
        val activity = CarSignInActivity()
        activity.onLoginSuccess()
        idleMainLooper()

        verify(session).notifyChildrenChanged(eq(ConstantsAA.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.PLAYLIST_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.ARTISTS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.ALBUMS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.MORE_ID), any(), eq(null))
    }

    @Test
    fun `onSignedOut stops playback and invalidates the root and all four tabs`() {
        val activity = CarSignInActivity()
        activity.onSignedOut()
        idleMainLooper()

        verify(player).stop()
        verify(player).clearMediaItems()
        verify(session).notifyChildrenChanged(eq(ConstantsAA.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.PLAYLIST_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.ARTISTS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.ALBUMS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(ConstantsAA.MORE_ID), any(), eq(null))
    }
}
