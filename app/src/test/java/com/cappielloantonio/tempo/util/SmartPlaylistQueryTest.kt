package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure functions, so no Robolectric: nothing here touches a Context.
 *
 * The strings are real, measured off PMS 1.43.3 -- see the 2026-08-28 mix
 * paging design.
 */
class SmartPlaylistQueryTest {
    private val allMusic =
        "library://x/directory/%2Flibrary%2Fsections%2F7%2Fall%3Ftype%3D10"

    private val hearted =
        "library://x/directory/%2Flibrary%2Fsections%2F7%2Fall%3Ftype%3D10" +
            "%26userRating%253E%253E%3D4%26group%3Dguid%26sort%3DlastRatedAt"

    @Test
    fun `a simple content value decodes to its relative path`() {
        assertEquals("/library/sections/7/all?type=10", SmartPlaylistQuery.pathIn(allMusic))
    }

    @Test
    fun `a comparison operator survives the single decode still encoded`() {
        // %253E%253E decodes exactly once, to %3E%3E. Decoding twice would send
        // a literal ">>" and address a filter that does not exist.
        val path = SmartPlaylistQuery.pathIn(hearted)!!
        assertEquals(
            "/library/sections/7/all?type=10&userRating%3E%3E=4&group=guid&sort=lastRatedAt",
            path,
        )
    }

    @Test
    fun `an absent or blank content value has no path`() {
        assertNull(SmartPlaylistQuery.pathIn(null))
        assertNull(SmartPlaylistQuery.pathIn(""))
        assertNull(SmartPlaylistQuery.pathIn("   "))
    }

    @Test
    fun `a content value naming another host is refused`() {
        // The token rides on every request this client makes, so a path that
        // resolves off-host would hand a full account credential to whoever
        // named it. Same rule as LibraryClient.isSafeHubKey.
        assertNull(SmartPlaylistQuery.pathIn("library://x/directory/https%3A%2F%2Fevil.example%2Fx"))
        assertNull(SmartPlaylistQuery.pathIn("library://x/directory/%2F%2Fevil.example%2Fx"))
        assertNull(SmartPlaylistQuery.pathIn("library://x/directory/%2F%5Cevil.example%2Fx"))
    }

    @Test
    fun `a content value that is not a directory reference is refused`() {
        assertNull(SmartPlaylistQuery.pathIn("library://x/item/%2Flibrary%2Fmetadata%2F1"))
    }

    @Test
    fun `an existing sort is replaced rather than appended`() {
        assertEquals(
            "/library/sections/7/all?type=10&group=guid&sort=random",
            SmartPlaylistQuery.randomised(
                "/library/sections/7/all?type=10&sort=lastRatedAt&group=guid",
            ),
        )
    }

    @Test
    fun `a query with no sort gains one`() {
        assertEquals(
            "/library/sections/7/all?type=10&sort=random",
            SmartPlaylistQuery.randomised("/library/sections/7/all?type=10"),
        )
    }

    @Test
    fun `a path with no query string gains a query string`() {
        assertEquals(
            "/library/sections/7/all?sort=random",
            SmartPlaylistQuery.randomised("/library/sections/7/all"),
        )
    }
}
