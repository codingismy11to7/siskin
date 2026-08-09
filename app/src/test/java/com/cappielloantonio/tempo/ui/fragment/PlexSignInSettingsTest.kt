package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.ui.activity.CarSignInActivity
import com.cappielloantonio.tempo.util.Preferences
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Settings screen is the Connected branch of PlexSignInFragment.render(),
 * reached by CarSignInActivity.onCreate calling open(forceSignIn = false) while a
 * session exists in preferences -- which is what setUp() arranges.
 *
 * This is the test #72 is actually about. The complaint was not that continuous
 * play defaulted on; it was that nothing could turn it off. A default flip alone
 * would satisfy PreferencesContinuousPlayTest and still leave that true.
 */
@RunWith(RobolectricTestRunner::class)
class PlexSignInSettingsTest {

    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, and both the session and the toggle are read out of it.
        App.getInstance().preferences.edit().remove("continuous_play").commit()
        PlexApi().session = PlexSession(
            accountToken = "t",
            serverUri = "https://example.invalid",
            musicSectionKey = SectionKey("1"),
            serverToken = null
        )
    }

    private fun settingsScreen(): View {
        val controller = Robolectric.buildActivity(CarSignInActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get().findViewById(R.id.car_sign_in_container)
    }

    private fun switchesIn(view: View): List<MaterialSwitch> = when (view) {
        is MaterialSwitch -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switchesIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `settings offers one switch, off, when nothing has asked for continuous play`() {
        val switches = switchesIn(settingsScreen())

        assertEquals(1, switches.size)
        assertFalse(switches.single().isChecked)
    }

    @Test
    fun `the switch reflects a preference that is already on`() {
        Preferences.setContinuousPlayEnabled(true)

        assertTrue(switchesIn(settingsScreen()).single().isChecked)
    }

    @Test
    fun `tapping the row turns continuous play on`() {
        val toggle = switchesIn(settingsScreen()).single()

        // The row owns the click, not the thumb -- a thumb is a phone-sized
        // target and this is a head unit. performClick() runs the row's listener
        // directly and never goes through touch dispatch, so what the two
        // assertions pin is that the one listener both writes the preference and
        // moves the switch to match: the switch can never show one thing while
        // the preference says another.
        //
        // Deliberately not toggle.performClick(): CompoundButton.performClick()
        // calls toggle() whatever isClickable says, so it would flip the switch
        // without writing anything and pin the opposite of the truth. That a tap
        // on the thumb reaches the row is a touch-dispatch property of a
        // non-clickable switch, and performClick() is not dispatch.
        (toggle.parent as View).performClick()

        assertTrue(Preferences.isContinuousPlayEnabled())
        assertTrue(toggle.isChecked)

        // Guards the desync a clickable switch would let through: a tap
        // landing on the thumb is consumed by CompoundButton.performClick,
        // which flips the switch without running the row's listener, so the
        // preference is never written and the switch snaps back at the next
        // render(). If addToggle ever drops isClickable = false, this fails
        // while the tap still "looks" like it worked.
        assertFalse(toggle.isClickable)
    }

    @Test
    fun `tapping the row again turns it back off`() {
        Preferences.setContinuousPlayEnabled(true)
        val toggle = switchesIn(settingsScreen()).single()

        (toggle.parent as View).performClick()

        assertFalse(Preferences.isContinuousPlayEnabled())
        assertFalse(toggle.isChecked)
    }
}
