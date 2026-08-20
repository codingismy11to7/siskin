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
 * Issue #72: the key had no writer anywhere in app/src/main, so `true` was not a
 * default but the permanent value, and an eight-track album queue quietly became
 * a ~56-track one. These pin both halves of the fix -- off unless asked for, and
 * askable.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesContinuousPlayTest {
    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, so without this an earlier method's write decides this one.
        App
            .getInstance()
            .preferences
            .edit()
            .remove("continuous_play")
            .commit()
    }

    @Test
    fun `continuous play is off when nothing has asked for it`() {
        assertFalse(Preferences.isContinuousPlayEnabled())
    }

    @Test
    fun `the setter round-trips in both directions`() {
        Preferences.setContinuousPlayEnabled(true)
        assertTrue(Preferences.isContinuousPlayEnabled())

        Preferences.setContinuousPlayEnabled(false)
        assertFalse(Preferences.isContinuousPlayEnabled())
    }
}
