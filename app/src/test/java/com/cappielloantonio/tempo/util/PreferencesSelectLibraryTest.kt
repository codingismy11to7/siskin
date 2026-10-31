package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Off by default, existing installs included: most accounts have one library,
 * and for them the row has nothing to offer. See
 * docs/decisions/2026-10-30-select-library-switch-design.md.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesSelectLibraryTest {
    @Before
    fun setUp() {
        App
            .getInstance()
            .preferences
            .edit()
            .remove("select_library")
            .commit()
    }

    @Test
    fun `select library is hidden when nothing has said otherwise`() {
        assertFalse(Preferences.isSelectLibraryEnabled())
    }

    @Test
    fun `the setter round-trips in both directions`() {
        Preferences.setSelectLibraryEnabled(true)
        assertTrue(Preferences.isSelectLibraryEnabled())

        Preferences.setSelectLibraryEnabled(false)
        assertFalse(Preferences.isSelectLibraryEnabled())
    }
}
