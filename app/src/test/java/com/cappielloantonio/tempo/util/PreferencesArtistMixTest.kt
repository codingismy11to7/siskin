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
 * On by default because every install so far has shown the row; turning it off
 * is the opt-out for an artist page that would otherwise grow another row above
 * its albums.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesArtistMixTest {
    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, so without this an earlier method's write decides this one.
        App
            .getInstance()
            .preferences
            .edit()
            .remove("artist_mix")
            .commit()
    }

    @Test
    fun `an artist offers its mix when nothing has said otherwise`() {
        assertTrue(Preferences.isArtistMixEnabled())
    }

    @Test
    fun `the setter round-trips in both directions`() {
        Preferences.setArtistMixEnabled(false)
        assertFalse(Preferences.isArtistMixEnabled())

        Preferences.setArtistMixEnabled(true)
        assertTrue(Preferences.isArtistMixEnabled())
    }
}
