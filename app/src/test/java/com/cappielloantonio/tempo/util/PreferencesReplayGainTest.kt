package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because Preferences reads App.getInstance().preferences, which
 * needs a live Context.
 *
 * The mode had no writer anywhere in app/src/main, so "disabled" was not a
 * default but the permanent value and the whole ReplayGain pipeline was
 * unreachable. These pin both halves of the fix -- off unless asked for, and
 * askable -- plus the literal the UI writes, which is what couples this to
 * ReplayGainUtil's switch.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesReplayGainTest {

    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, so without this an earlier method's write decides this one.
        App.getInstance().preferences.edit().remove("replay_gain_mode").commit()
    }

    @Test
    fun `replay gain is off when nothing has asked for it`() {
        assertFalse(Preferences.isReplayGainEnabled())
        assertEquals("disabled", Preferences.getReplayGainMode())
    }

    @Test
    fun `the setter round-trips in both directions`() {
        Preferences.setReplayGainEnabled(true)
        assertTrue(Preferences.isReplayGainEnabled())

        Preferences.setReplayGainEnabled(false)
        assertFalse(Preferences.isReplayGainEnabled())
    }

    @Test
    fun `on means auto, which is the literal ReplayGainUtil switches on`() {
        Preferences.setReplayGainEnabled(true)

        // Not an implementation detail: ReplayGainUtil.resolveGain switches on
        // this exact string. A refactor that wrote "enabled" or "on" here would
        // leave isReplayGainEnabled() true and every gain resolving to 0f, which
        // is silent and looks like a tagging problem in the user's library.
        assertEquals("auto", Preferences.getReplayGainMode())
    }

    @Test
    fun `an explicit track or album mode still reads as enabled`() {
        // The key stays four-way even though the UI is boolean, so a value this
        // app cannot yet write must not read as off.
        App.getInstance().preferences.edit().putString("replay_gain_mode", "album").commit()

        assertTrue(Preferences.isReplayGainEnabled())
    }
}
