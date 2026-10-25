package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import arrow.core.right
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.repository.HomeUsersRepository
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.PlexResourceFixture.aMediaServer
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigationrail.NavigationRailView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The debug screen's entry point and its rows.
 *
 * Driven through CarHostActivity rather than a FragmentScenario because the
 * thing being tested is a back-stack push from another fragment, and because
 * Task 4's routing assertion needs the activity's router in play.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CarDebugFragmentTest {
    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across methods, and
        // the session is what routes the activity to Settings in the first
        // place.
        PlexApi().session =
            PlexSession(
                accountToken = "t",
                serverUri = "https://example.invalid",
                musicSectionKey = SectionKey("1"),
                serverToken = null,
                owned = true,
            )
    }

    @Before
    fun stubTheAccountsServers() {
        HomeUsersRepository.fetch = { null }
        CarHostActivity.viewModelFactoryForTest =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val authClient =
                        mock<AuthClient>().stub {
                            on { getResources() } doReturn listOf(aMediaServer()).right()
                        }
                    @Suppress("UNCHECKED_CAST")
                    return PlexSignInViewModel(
                        RuntimeEnvironment.getApplication(),
                        authClient = authClient,
                        authClientFor = { authClient },
                    ) as T
                }
            }
    }

    @After
    fun clearTheStub() {
        // Static and Robolectric keeps statics between classes, so leaving this
        // set hands the next suite a stubbed AuthClient.
        CarHostActivity.viewModelFactoryForTest = null
        HomeUsersRepository.resetForTest()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ActivityController<CarHostActivity> =
        Robolectric.buildActivity(CarHostActivity::class.java).setup().also { idle() }

    private fun screenOf(controller: ActivityController<CarHostActivity>) =
        controller.get().supportFragmentManager.findFragmentById(R.id.car_host_container)

    private fun viewsIn(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { viewsIn(view.getChildAt(it)) }
            } else {
                emptyList()
            }

    /** The row carrying [label], found by its text rather than its position. */
    private fun rowLabelled(
        controller: ActivityController<CarHostActivity>,
        label: String,
    ) = viewsIn(controller.get().findViewById(R.id.car_host_container))
        .filterIsInstance<MaterialButton>()
        .single { it.text.toString() == label }

    private fun openDebugScreen(controller: ActivityController<CarHostActivity>) {
        controller.get().findViewById<View>(R.id.version_text).performClick()
        idle()
    }

    @Test
    fun `tapping the version line opens the debug screen`() {
        val controller = launch()

        openDebugScreen(controller)

        assertTrue(screenOf(controller) is CarDebugFragment)
    }

    @Test
    fun `the debug screen offers a re-probe row`() {
        val controller = launch()
        openDebugScreen(controller)

        val label = controller.get().getString(R.string.debug_addresses_reprobe)

        // single() throws if it is missing or duplicated, so reaching the
        // assertion is most of the test.
        assertTrue(rowLabelled(controller, label).isEnabled)
    }

    private fun chooseServer(controller: ActivityController<CarHostActivity>) {
        val label = controller.get().getString(R.string.debug_choose_server)
        rowLabelled(controller, label).performClick()
        idle()
    }

    @Test
    fun `choosing a server opens the picker`() {
        val controller = launch()
        openDebugScreen(controller)

        chooseServer(controller)

        assertTrue(screenOf(controller) is PlexSignInFragment)
    }

    /**
     * The reason the picker is pushed rather than routed to.
     *
     * Back out of a picker opened from here means "return to the debug
     * screen", and a back stack is the thing that knows how to do that. The
     * fragment declines to claim the press for ChoosingServer when it was
     * pushed (see PlexSignInFragment.claimsBackPress), so the press falls
     * through to the FragmentManager's own pop.
     *
     * This fails if that exception is removed: PlexSignInViewModel.backPressed
     * takes the press instead, publishes Disconnected, and the router replaces
     * the picker with the Connect screen -- abandoning a sign-in the user never
     * started, and never returning here.
     */
    @Test
    fun `back out of the picker returns to the debug screen`() {
        val controller = launch()
        openDebugScreen(controller)
        chooseServer(controller)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()

        assertTrue(screenOf(controller) is CarDebugFragment)
        assertEquals(DebugTab.SERVER.ordinal, rail(controller).selectedItemId)
        assertTrue(tabFragment(controller, DebugTab.SERVER)!!.isVisible)
    }

    private fun debugScreen(controller: ActivityController<CarHostActivity>) = screenOf(controller) as CarDebugFragment

    private fun rail(controller: ActivityController<CarHostActivity>) = controller.get().findViewById<NavigationRailView>(R.id.debug_rail)

    private fun select(
        controller: ActivityController<CarHostActivity>,
        tab: DebugTab,
    ) {
        rail(controller).selectedItemId = tab.ordinal
        idle()
    }

    private fun tabFragment(
        controller: ActivityController<CarHostActivity>,
        tab: DebugTab,
    ) = debugScreen(controller).childFragmentManager.findFragmentByTag(tab.name)

    @Test
    fun `the rail lists every tab in enum order`() {
        val controller = launch()
        openDebugScreen(controller)

        val menu = rail(controller).menu
        val activity = controller.get()
        assertEquals(DebugTab.entries.size, menu.size())
        DebugTab.entries.forEachIndexed { i, tab ->
            assertEquals(activity.getString(tab.label), menu.getItem(i).title.toString())
        }
    }

    @Test
    fun `the debug screen opens on the first tab`() {
        val controller = launch()
        openDebugScreen(controller)

        assertEquals(DebugTab.entries.first().ordinal, rail(controller).selectedItemId)
        assertTrue(tabFragment(controller, DebugTab.entries.first())!!.isVisible)
    }

    @Test
    fun `selecting Car shows the Car tab and hides Server`() {
        val controller = launch()
        openDebugScreen(controller)

        select(controller, DebugTab.CAR)

        assertTrue(tabFragment(controller, DebugTab.CAR) is DebugCarFragment)
        assertTrue(tabFragment(controller, DebugTab.CAR)!!.isVisible)
        assertFalse(tabFragment(controller, DebugTab.SERVER)!!.isVisible)
    }

    /**
     * Tabs are shown and hidden, not replaced: a re-probe runs in the Server
     * tab's view lifecycle for up to tens of seconds, and replacing the tab
     * would cancel it. This fails if selection is changed to replace().
     */
    @Test
    fun `returning to a tab brings back the same fragment`() {
        val controller = launch()
        openDebugScreen(controller)
        val server = tabFragment(controller, DebugTab.SERVER)

        select(controller, DebugTab.CAR)
        select(controller, DebugTab.SERVER)

        assertSame(server, tabFragment(controller, DebugTab.SERVER))
        assertTrue(server!!.isVisible)
    }

    @Test
    fun `one back press from any tab leaves the debug screen`() {
        val controller = launch()
        openDebugScreen(controller)
        select(controller, DebugTab.CAR)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()

        assertTrue(screenOf(controller) is CarSettingsFragment)
    }
}
