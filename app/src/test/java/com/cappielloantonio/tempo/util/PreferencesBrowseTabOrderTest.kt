package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because Preferences reads App.getInstance().preferences, which
 * needs a live Context.
 *
 * Kotlin compiles with -Werror here, so drop any import the fixture made
 * unnecessary rather than leaving it.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesBrowseTabOrderTest {

    @Before
    fun setUp() = BrowseTabOrderFixture.clearSavedOrder()

    /**
     * Empty, not the default order. Resolving absence into a default is
     * BrowseTabOrder's job, and doing it in both places would mean two
     * definitions of the default that could drift.
     */
    @Test
    fun `an unset order reads as empty`() {
        assertEquals(emptyList<String>(), Preferences.getBrowseTabOrder())
    }

    @Test
    fun `the setter round-trips order faithfully`() {
        val order = listOf(
            Constants.DECADES_ID,
            Constants.ALBUMS_ID,
            Constants.ARTISTS_ID,
            Constants.PLAYLIST_ID
        )

        Preferences.setBrowseTabOrder(order)

        assertEquals(order, Preferences.getBrowseTabOrder())
    }

    /**
     * Order is the entire content of this setting, so it must survive a
     * write-read cycle in the exact sequence given -- a string *set* would
     * pass a one-element test and shuffle here.
     */
    @Test
    fun `reversing the order round-trips as the reverse`() {
        Preferences.setBrowseTabOrder(listOf(Constants.ALBUMS_ID, Constants.ARTISTS_ID))
        assertEquals(
            listOf(Constants.ALBUMS_ID, Constants.ARTISTS_ID),
            Preferences.getBrowseTabOrder()
        )

        Preferences.setBrowseTabOrder(listOf(Constants.ARTISTS_ID, Constants.ALBUMS_ID))
        assertEquals(
            listOf(Constants.ARTISTS_ID, Constants.ALBUMS_ID),
            Preferences.getBrowseTabOrder()
        )
    }

    /**
     * setBrowseTabOrder(emptyList()) writes an empty string, not an absent
     * key -- it does not clear the preference. What matters is that the
     * empty string round-trips back to an empty list rather than to
     * listOf("") (`"".split(",")` produces a one-element list of a blank
     * string, which is the failure mode this pins against).
     */
    @Test
    fun `an empty order round-trips as an empty list rather than a blank entry`() {
        Preferences.setBrowseTabOrder(listOf(Constants.ALBUMS_ID))
        Preferences.setBrowseTabOrder(emptyList())

        assertEquals(emptyList<String>(), Preferences.getBrowseTabOrder())
    }
}
