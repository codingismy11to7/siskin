package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No Robolectric: this is the point of extracting the function. Every
 * decision about order is here, and none of it needs a Context.
 */
class BrowseTabOrderTest {
    @Test
    fun `nothing saved yields the shipped default`() {
        assertEquals(
            BrowseTabOrder.DEFAULT_ORDER,
            BrowseTabOrder.resolve(emptyList()),
        )
    }

    @Test
    fun `a complete saved order is returned unchanged`() {
        // Complete means every id the build knows, Discover included -- a save
        // missing one is the append case two tests below, not this one.
        val saved =
            listOf(
                Constants.DECADES_ID,
                Constants.ALBUMS_ID,
                Constants.DISCOVER_ID,
                Constants.ARTISTS_ID,
                Constants.PLAYLIST_ID,
            )

        assertEquals(saved, BrowseTabOrder.resolve(saved))
    }

    /**
     * A destination deleted in a later release. Dropping it on read is what
     * means there is never a migration to write.
     */
    @Test
    fun `an id that is no longer known is dropped`() {
        val saved = listOf(Constants.ALBUMS_ID, "[podcastID]", Constants.ARTISTS_ID)

        val resolved = BrowseTabOrder.resolve(saved)

        assertEquals(false, resolved.contains("[podcastID]"))
        assertEquals(Constants.ALBUMS_ID, resolved[0])
        assertEquals(Constants.ARTISTS_ID, resolved[1])
    }

    /**
     * A destination added in a later release -- Discover was the first one to
     * actually arrive this way. It lands at the end, so it falls into More and
     * can never displace a tab the user chose. See the spec's "Fresh installs
     * and upgrades will disagree about Discover".
     */
    @Test
    fun `a newly known id is appended rather than inserted`() {
        val saved = listOf(Constants.ARTISTS_ID, Constants.ALBUMS_ID)
        val known =
            listOf(
                Constants.PLAYLIST_ID,
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.DECADES_ID,
            )

        val resolved = BrowseTabOrder.resolve(saved, known)

        assertEquals(
            listOf(
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.PLAYLIST_ID,
                Constants.DECADES_ID,
            ),
            resolved,
        )
    }

    @Test
    fun `duplicates in a corrupt save collapse to the first occurrence`() {
        val saved = listOf(Constants.ALBUMS_ID, Constants.ALBUMS_ID, Constants.ARTISTS_ID)

        val resolved = BrowseTabOrder.resolve(saved)

        assertEquals(1, resolved.count { it == Constants.ALBUMS_ID })
        assertEquals(Constants.ALBUMS_ID, resolved[0])
        assertEquals(Constants.ARTISTS_ID, resolved[1])
    }

    @Test
    fun `the first three become tabs and the rest become More rows`() {
        val resolved = BrowseTabOrder.resolve(emptyList())

        assertEquals(
            listOf(Constants.PLAYLIST_ID, Constants.ARTISTS_ID, Constants.ALBUMS_ID),
            BrowseTabOrder.rootTabs(resolved),
        )
        assertEquals(
            listOf(Constants.DISCOVER_ID, Constants.DECADES_ID),
            BrowseTabOrder.moreRows(resolved),
        )
    }

    /**
     * Not reachable with today's five destinations, but the split must not
     * assume its own pool size -- a shorter root is correct, an exception is
     * not.
     */
    @Test
    fun `a pool smaller than three yields a shorter root and no More rows`() {
        val known = listOf(Constants.ALBUMS_ID, Constants.ARTISTS_ID)

        val resolved = BrowseTabOrder.resolve(emptyList(), known)

        assertEquals(known, BrowseTabOrder.rootTabs(resolved))
        assertEquals(emptyList<String>(), BrowseTabOrder.moreRows(resolved))
    }

    /**
     * The shipped default must reproduce today's browse root exactly, so an
     * install that never opens the screen sees no change at all.
     */
    @Test
    fun `the default reproduces todays root`() {
        assertEquals(
            listOf(
                Constants.PLAYLIST_ID,
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.DISCOVER_ID,
                Constants.DECADES_ID,
            ),
            BrowseTabOrder.DEFAULT_ORDER,
        )
    }

    /**
     * The asymmetry the spec accepts, pinned so it is not "fixed" later. A
     * fresh install gets Discover fourth, ahead of Decades; someone who saved
     * an order before Discover shipped gets it *last*, behind Decades. Both are
     * this one rule, and the alternative -- inserting it at its default rank --
     * would demote whichever tab the user had deliberately chosen third.
     */
    @Test
    fun `an order saved before Discover shipped gets it last, not fourth`() {
        val savedBeforeDiscover =
            listOf(
                Constants.PLAYLIST_ID,
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.DECADES_ID,
            )

        val resolved = BrowseTabOrder.resolve(savedBeforeDiscover)

        assertEquals(Constants.DISCOVER_ID, resolved.last())
        // The three tabs the user had are untouched, which is the whole point.
        assertEquals(
            listOf(Constants.PLAYLIST_ID, Constants.ARTISTS_ID, Constants.ALBUMS_ID),
            BrowseTabOrder.rootTabs(resolved),
        )
        assertEquals(
            listOf(Constants.DECADES_ID, Constants.DISCOVER_ID),
            BrowseTabOrder.moreRows(resolved),
        )
    }

    /**
     * Discover is an ordinary member of the pool, not a pinned row like Select
     * Library: promoting it makes it a tab like any other destination.
     */
    @Test
    fun `Discover can be promoted to a root tab`() {
        val resolved = BrowseTabOrder.resolve(listOf(Constants.DISCOVER_ID))

        assertEquals(Constants.DISCOVER_ID, resolved.first())
        assertEquals(Constants.DISCOVER_ID, BrowseTabOrder.rootTabs(resolved).first())
        assertEquals(false, BrowseTabOrder.moreRows(resolved).contains(Constants.DISCOVER_ID))
    }
}
