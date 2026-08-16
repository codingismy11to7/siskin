package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import arrow.core.nonEmptyListOf
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.PlexResourceFixture.aMediaServer
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Back out of the server picker on the **sign-in** journey, where this
 * fragment was routed to rather than pushed.
 *
 * The counterpart to CarDebugFragmentTest's back test, and the reason both are
 * needed: this fragment declines the back press for
 * [PlexSignInState.ChoosingServer] when it was pushed, so that the fragment
 * back stack can return the user to the debug screen. That exception must not
 * leak into the ordinary case. Signing in with the wrong Plex account and
 * pressing back to try again is a real thing to do, and it depends on the flow
 * still claiming the press here.
 *
 * `CarHostActivity`'s router builds this fragment with a bare constructor, so
 * the `pushed` argument is absent and the exception does not apply -- that is
 * the property under test, and it is one construction site away from being
 * wrong.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlexSignInFragmentBackTest {

    @Before
    fun signedOut() {
        // Robolectric caches SharedPreferences statically across classes, so a
        // session left by another suite would route this to Settings instead.
        PlexApi().session = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `back out of the picker while signing in abandons the attempt`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        idle()
        val activity = controller.get()
        val viewModel = ViewModelProvider(activity)[PlexSignInViewModel::class.java]

        // The state a just-approved PIN lands on. Published directly rather
        // than driven through a real sign-in: the press, not the flow that
        // reached it, is what this test is about.
        viewModel.setStateForTest(
            PlexSignInState.ChoosingServer(nonEmptyListOf(aMediaServer()))
        )
        idle()

        activity.onBackPressedDispatcher.onBackPressed()
        idle()

        // Disconnected is the Connect screen -- back here means "I picked the
        // wrong account, let me start over", and starting over has to be
        // reachable. If this reads ChoosingServer the press was swallowed; if
        // the activity finished instead, the callback declined it and the user
        // was thrown out of the app mid-sign-in.
        assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        assertEquals(false, activity.isFinishing)
    }
}
