package com.cappielloantonio.tempo.ui.activity

import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.ui.fragment.BrowseTabOrderFragment
import com.cappielloantonio.tempo.ui.fragment.CarSettingsFragment
import com.cappielloantonio.tempo.ui.fragment.PlexSignInFragment
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Which screen the activity puts in car_host_container, and when it leaves one
 * alone.
 *
 * The routing replaced a fragment that rendered both screens off one state
 * observer, so what needs pinning is not that either screen draws -- their own
 * suites do that -- but that the *choice* between them is made from the state
 * and made once. The two recreation tests are the half that has no equivalent
 * in the old shape at all: a single fragment could not clobber itself on a
 * uiMode flip, and a router can.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CarHostActivityRoutingTest {
    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across test methods,
        // and the session is read out of them by CredentialGate on the way to
        // choosing a screen -- so a session left behind by another test would
        // decide this one's routing.
        PlexApi().session = null
    }

    private fun signedIn() {
        PlexApi().session =
            PlexSession(
                accountToken = "t",
                serverUri = "https://example.invalid",
                musicSectionKey = SectionKey("1"),
                serverToken = null,
            )
    }

    /**
     * `setup()`, never `create()`: the activity observes with itself as the
     * LifecycleOwner, so LiveData delivers nothing until STARTED and a
     * created-only activity would show an empty container no matter what the
     * session says.
     */
    private fun launch(): ActivityController<CarHostActivity> =
        Robolectric.buildActivity(CarHostActivity::class.java).setup().also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screenOf(controller: ActivityController<CarHostActivity>): Fragment? =
        controller.get().supportFragmentManager.findFragmentById(R.id.car_host_container)

    @Test
    fun `a session on record opens settings`() {
        signedIn()

        assertTrue(screenOf(launch()) is CarSettingsFragment)
    }

    @Test
    fun `no session opens the sign-in screen`() {
        assertTrue(screenOf(launch()) is PlexSignInFragment)
    }

    /**
     * Signing out is a state change, not a navigation: nothing in
     * CarSettingsFragment replaces itself, the ViewModel publishes Disconnected
     * and the activity is what moves off the screen. Driven through the
     * ViewModel rather than the Sign out row because the row's own wiring is
     * CarSettingsFragmentTest's subject; what is being pinned here is that the
     * state alone is enough.
     */
    @Test
    fun `losing the session swaps settings for the sign-in screen`() {
        signedIn()
        val controller = launch()

        ViewModelProvider(controller.get())[PlexSignInViewModel::class.java].signOut()
        idle()

        assertTrue(screenOf(controller) is PlexSignInFragment)
    }

    /**
     * A uiMode flip re-creates the activity, which re-runs onCreate and
     * re-delivers the same Connected the ViewModel still holds.
     *
     * Pins the outcome only -- that the flip lands back on settings. It does
     * *not* pin the `instanceof` check in route(), because a router that
     * committed a second settings fragment over the restored one would satisfy
     * this assertion too; `re-publishing the same state replaces nothing` below
     * is the test that has teeth there.
     */
    @Test
    fun `a recreation lands back on settings`() {
        signedIn()
        val controller = launch()

        controller.recreate()
        idle()

        assertTrue(screenOf(controller) is CarSettingsFragment)
    }

    /**
     * The `instanceof` half of route(): the same state arriving twice must not
     * cost a transaction.
     *
     * `open(false)` is how that happens -- LiveData does not dedupe, so
     * re-publishing Connected re-runs the observer with a settings screen
     * already on show. Reachable today only at onCreate, but the activity is
     * `launchMode="singleTop"`, so a second trip through the car's gear arrives
     * as onNewIntent on the same instance; PlexSignInViewModel.open's own KDoc
     * anticipates exactly that caller. Without the check the user would watch
     * the screen they are already on be rebuilt underneath them.
     */
    @Test
    fun `re-publishing the same state replaces nothing`() {
        signedIn()
        val controller = launch()
        val before = screenOf(controller)

        ViewModelProvider(controller.get())[PlexSignInViewModel::class.java].open(false)
        idle()

        assertSame(before, screenOf(controller))
    }

    /**
     * The back-stack guard, and the reason it is not merely defensive: the
     * tab-order screen is pushed *over* settings, so the state is still
     * Connected the whole time it is up. Without the guard a recreation would
     * route on that Connected, replace the restored tab-order screen with
     * settings, and drop the user a level up mid-drag.
     */
    @Test
    fun `a recreation leaves a pushed screen alone`() {
        signedIn()
        val controller = launch()

        controller
            .get()
            .supportFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, BrowseTabOrderFragment())
            .addToBackStack(null)
            .commit()
        idle()

        controller.recreate()
        idle()

        assertTrue(screenOf(controller) is BrowseTabOrderFragment)
    }
}
