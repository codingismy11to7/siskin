package com.cappielloantonio.tempo.ui.activity

import android.os.Looper
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaBrowserTree
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers CarHostActivity's two LoginHost callbacks directly, on a bare
 * `CarHostActivity()` instance rather than one built through Robolectric's
 * full Activity lifecycle (`buildActivity(...).create()`): onLoginSuccess()
 * and onSignedOut() only touch BrowseTreeInvalidator's static session and
 * (for onLoginSuccess) call finish(), none of which needs onCreate() to have
 * run -- onCreate() pulls in ViewModelProvider, a fragment transaction and
 * PlexSignInViewModel.open()'s network calls, none of which this test cares
 * about, so skipping it keeps the test scoped to what it is actually
 * checking. See BrowseTreeInvalidatorTest for direct coverage of
 * invalidateTree() itself.
 *
 * The back button test below is the one exception and does use
 * `buildActivity(...).create()`: the button only exists once onCreate() has
 * inflated activity_car_host.xml, and the whole point of that test is that
 * clicking it reaches the activity through the real click listener onCreate()
 * wires up, not a hand-called method. open(false)'s network-free path
 * (CredentialGate.isSignedIn() is false with no session on record) and the
 * fragment transaction staying uncommitted at CREATED are what keep this
 * safe without a MockWebServer -- see the test's own comment.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CarHostActivityTest {
    private lateinit var session: MediaLibrarySession
    private lateinit var player: Player

    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across test
        // methods (see BrowseTabOrderFixture's KDoc), so a saved order left
        // by another test -- including one in another class -- would
        // otherwise leak into the tab ids this test asserts on.
        BrowseTabOrderFixture.clearSavedOrder()
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
        val activity = CarHostActivity()
        activity.onLoginSuccess()
        idleMainLooper()

        verify(session).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.PLAYLIST_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ARTISTS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ALBUMS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.MORE_ID), any(), eq(null))
    }

    @Test
    fun `onSignedOut stops playback and invalidates the root and all four tabs`() {
        val activity = CarHostActivity()
        activity.onSignedOut()
        idleMainLooper()

        verify(player).stop()
        verify(player).clearMediaItems()
        verify(session).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.PLAYLIST_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ARTISTS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.ALBUMS_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.MORE_ID), any(), eq(null))
    }

    /**
     * The activity draws no toolbar and has no back affordance of its own, so
     * this button is the only way out of any state it hosts -- settings and
     * every step of the sign-in flow alike. It is wired through
     * getOnBackPressedDispatcher().onBackPressed() rather than a direct
     * finish() call (see CarHostActivity.onCreate's comment on why).
     *
     * PlexSignInFragment does now register an OnBackPressedCallback -- the
     * back handling this button's KDoc anticipated -- but it stays disabled
     * here for the same reason `render()` never runs (see this class's own
     * KDoc): the callback is armed from the same `state.observe(...)` block,
     * which needs viewLifecycleOwner at STARTED to deliver anything, and
     * `.create()` never gets there. So the dispatcher still falls through to
     * its default (finish) in this test, matching what it did before the
     * callback existed -- but that is this test's own stopping point, not a
     * guarantee that no callback is registered; PlexSignInViewModelTest
     * covers what the callback actually does once armed.
     */
    @Test
    fun `back button routes through the back dispatcher and finishes the activity`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).create()
        idleMainLooper()
        val activity = controller.get()

        activity.findViewById<View>(R.id.back_button).performClick()
        idleMainLooper()

        assertTrue(activity.isFinishing)
    }
}
