package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
        PlexApi().session = PlexSession(
            accountToken = "t",
            serverUri = "https://example.invalid",
            musicSectionKey = SectionKey("1"),
            serverToken = null
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ActivityController<CarHostActivity> =
        Robolectric.buildActivity(CarHostActivity::class.java).setup().also { idle() }

    private fun screenOf(controller: ActivityController<CarHostActivity>) =
        controller.get().supportFragmentManager.findFragmentById(R.id.car_host_container)

    private fun viewsIn(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { viewsIn(view.getChildAt(it)) }
        } else {
            emptyList()
        }

    /** The row carrying [label], found by its text rather than its position. */
    private fun rowLabelled(controller: ActivityController<CarHostActivity>, label: String) =
        viewsIn(controller.get().findViewById(R.id.car_host_container))
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
}
