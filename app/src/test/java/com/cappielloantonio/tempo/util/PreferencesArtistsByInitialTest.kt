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
 * The default is the decision here rather than an implementation detail. #87 is
 * unreleased, so no install has ever seen a window row and there is no existing
 * behaviour to preserve -- letters ship as the Artists experience and windows
 * become the opt-out.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesArtistsByInitialTest {

    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, so without this an earlier method's write decides this one.
        App.getInstance().preferences.edit().remove("artists_by_initial").commit()
    }

    @Test
    fun `artists are grouped by initial when nothing has said otherwise`() {
        assertTrue(Preferences.isArtistsByInitialEnabled())
    }

    @Test
    fun `the setter round-trips in both directions`() {
        Preferences.setArtistsByInitialEnabled(false)
        assertFalse(Preferences.isArtistsByInitialEnabled())

        Preferences.setArtistsByInitialEnabled(true)
        assertTrue(Preferences.isArtistsByInitialEnabled())
    }
}
