package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Off by default: each row on an artist's page pushes the albums further down,
 * and radio is an addition no install has had.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesArtistRadioTest {
    @Before
    fun setUp() {
        App
            .getInstance()
            .preferences
            .edit()
            .remove("artist_radio")
            .commit()
    }

    @Test
    fun `an artist offers no radio unless asked to`() {
        assertFalse(Preferences.isArtistRadioEnabled())
    }

    @Test
    fun `the setter round-trips in both directions`() {
        Preferences.setArtistRadioEnabled(true)
        assertTrue(Preferences.isArtistRadioEnabled())

        Preferences.setArtistRadioEnabled(false)
        assertFalse(Preferences.isArtistRadioEnabled())
    }
}
