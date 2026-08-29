package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because Preferences reads App.getInstance().preferences, which
 * needs a live Context.
 *
 * Nothing writes this key yet -- there is no Settings row -- so the default is
 * the effective value, and that is what the first test pins.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesMixTrackLimitTest {
    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, so without this an earlier method's write decides this one.
        App
            .getInstance()
            .preferences
            .edit()
            .remove("mix_track_limit")
            .commit()
    }

    @Test
    fun `the limit defaults to 2500 when nothing has set it`() {
        assertEquals(2500, Preferences.getMixTrackLimit())
    }

    @Test
    fun `a stored string value is read back as a number`() {
        App
            .getInstance()
            .preferences
            .edit()
            .putString("mix_track_limit", "750")
            .commit()

        assertEquals(750, Preferences.getMixTrackLimit())
    }
}
