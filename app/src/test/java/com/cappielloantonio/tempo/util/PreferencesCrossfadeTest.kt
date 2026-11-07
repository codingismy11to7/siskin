package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesCrossfadeTest {
    @Before
    fun setUp() {
        // Robolectric keeps SharedPreferences across test methods.
        App
            .getInstance()
            .preferences
            .edit()
            .remove("crossfade")
            .commit()
    }

    @Test
    fun onByDefault() = assertTrue(Preferences.isCrossfadeEnabled())

    @Test
    fun remembersBeingTurnedOff() {
        Preferences.setCrossfadeEnabled(true)
        assertTrue(Preferences.isCrossfadeEnabled())
        Preferences.setCrossfadeEnabled(false)
        assertFalse(Preferences.isCrossfadeEnabled())
    }
}
