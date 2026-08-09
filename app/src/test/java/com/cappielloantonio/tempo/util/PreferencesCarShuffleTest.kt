package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
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
 * The default is the decision here rather than an implementation detail. Issue
 * #31 asked whether a shuffle row should defer to the car's global toggle or
 * hand over a queue we shuffled; true is the answer that leaves an existing
 * install behaving as it did, and that keeps the car's shuffle button telling
 * the truth about what the player is doing.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesCarShuffleTest {

    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, so without this an earlier method's write decides this one.
        App.getInstance().preferences.edit().remove("car_shuffle").commit()
    }

    @Test
    fun `the car's shuffle is deferred to when nothing has said otherwise`() {
        assertTrue(Preferences.isCarShuffleEnabled())
    }

    @Test
    fun `the setter round-trips in both directions`() {
        Preferences.setCarShuffleEnabled(false)
        assertFalse(Preferences.isCarShuffleEnabled())

        Preferences.setCarShuffleEnabled(true)
        assertTrue(Preferences.isCarShuffleEnabled())
    }
}
