package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import arrow.core.nonEmptyListOf
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.models.HomeUser
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
            PlexSignInState.ChoosingServer(nonEmptyListOf(aMediaServer())),
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

    private fun twoMembers(): PlexSignInState.ChoosingUser =
        PlexSignInState.ChoosingUser(
            nonEmptyListOf(
                HomeUser().apply {
                    uuid = "a"
                    title = "a"
                },
                HomeUser().apply {
                    uuid = "b"
                    title = "b"
                },
            ),
        )

    @Test
    fun `back out of the Home picker while signing in abandons the attempt`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        idle()
        val activity = controller.get()
        val viewModel = ViewModelProvider(activity)[PlexSignInViewModel::class.java]

        viewModel.setStateForTest(twoMembers())
        idle()

        activity.onBackPressedDispatcher.onBackPressed()
        idle()

        assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        assertEquals(false, activity.isFinishing)
    }

    @Test
    fun `the PIN screen shows a field and its message, and back returns to the picker`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        idle()
        val activity = controller.get()
        val viewModel = ViewModelProvider(activity)[PlexSignInViewModel::class.java]
        val users = twoMembers().users

        viewModel.setStateForTest(
            PlexSignInState.EnteringPin(users.head, users, messageRes = R.string.plex_sign_in_error_wrong_pin),
        )
        idle()

        val root = activity.findViewById<android.view.ViewGroup>(R.id.choice_container)
        assertEquals(1, root.childCount)
        assertEquals(true, root.getChildAt(0) is android.widget.EditText)
        val error = activity.findViewById<android.widget.TextView>(R.id.error_text)
        assertEquals(android.view.View.VISIBLE, error.visibility)

        activity.onBackPressedDispatcher.onBackPressed()
        idle()

        assertEquals(true, viewModel.state.value is PlexSignInState.ChoosingUser)
    }

    @Test
    fun `back out of a pushed Home picker pops the back stack`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        idle()
        val activity = controller.get()
        val viewModel = ViewModelProvider(activity)[PlexSignInViewModel::class.java]

        activity.supportFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, PlexSignInFragment.pushed())
            .addToBackStack(null)
            .commit()
        idle()
        val state = twoMembers()
        viewModel.setStateForTest(state)
        idle()

        activity.onBackPressedDispatcher.onBackPressed()
        idle()

        // The flow did not take the press: its state is untouched and the
        // fragment back stack popped.
        assertEquals(state, viewModel.state.value)
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
    }
}
