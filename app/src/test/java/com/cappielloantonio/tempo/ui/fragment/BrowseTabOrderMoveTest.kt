package com.cappielloantonio.tempo.ui.fragment

import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric only because the move persists through Preferences. The move
 * itself is a list operation, split out of the ItemTouchHelper callback so it
 * can be exercised without one.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BrowseTabOrderMoveTest {

    @Before
    fun setUp() = BrowseTabOrderFixture.clearSavedOrder()

    @Test
    fun `dragging a row down moves it and shifts the rest up`() {
        val order = mutableListOf(
            Constants.PLAYLIST_ID,
            Constants.ARTISTS_ID,
            Constants.ALBUMS_ID,
            Constants.DECADES_ID
        )

        BrowseTabOrderFragment.moveAndPersist(order, 0, 2)

        assertEquals(
            listOf(
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.PLAYLIST_ID,
                Constants.DECADES_ID
            ),
            order
        )
    }

    @Test
    fun `dragging a row up from More promotes it into the tabs`() {
        val order = mutableListOf(
            Constants.PLAYLIST_ID,
            Constants.ARTISTS_ID,
            Constants.ALBUMS_ID,
            Constants.DECADES_ID
        )

        BrowseTabOrderFragment.moveAndPersist(order, 3, 0)

        assertEquals(Constants.DECADES_ID, order[0])
        // Moving the last row to the front shifts everything else back by one,
        // so Albums -- previously inside ROOT_TAB_COUNT -- is what lands on
        // the wrong side of the line and becomes a More row.
        assertEquals(Constants.ALBUMS_ID, order[3])
    }

    /**
     * Written on drop rather than on the way out, so a force-quit mid-session
     * cannot lose it.
     */
    @Test
    fun `the move is persisted immediately`() {
        val order = mutableListOf(
            Constants.PLAYLIST_ID,
            Constants.ARTISTS_ID,
            Constants.ALBUMS_ID
        )

        BrowseTabOrderFragment.moveAndPersist(order, 2, 0)

        assertEquals(
            listOf(Constants.ALBUMS_ID, Constants.PLAYLIST_ID, Constants.ARTISTS_ID),
            Preferences.getBrowseTabOrder()
        )
    }

    /**
     * ItemTouchHelper.moveIfNecessary does not guard against NO_POSITION
     * itself before calling onMove -- a -1 can still reach here, and without
     * the guard order.removeAt(-1) would throw and crash the settings
     * screen. This proves the guard stops moveAndPersist from running at
     * all, rather than merely tolerating a bad index.
     */
    @Test
    fun `a NO_POSITION move is rejected and leaves the order untouched`() {
        val order = mutableListOf(
            Constants.PLAYLIST_ID,
            Constants.ARTISTS_ID,
            Constants.ALBUMS_ID
        )

        val moved = BrowseTabOrderFragment.moveIfValid(order, RecyclerView.NO_POSITION, 0)

        assertFalse(moved)
        assertEquals(
            listOf(Constants.PLAYLIST_ID, Constants.ARTISTS_ID, Constants.ALBUMS_ID),
            order
        )
    }

    @Test
    fun `a NO_POSITION to is rejected and leaves the order untouched`() {
        val order = mutableListOf(
            Constants.PLAYLIST_ID,
            Constants.ARTISTS_ID,
            Constants.ALBUMS_ID
        )

        val moved = BrowseTabOrderFragment.moveIfValid(order, 0, RecyclerView.NO_POSITION)

        assertFalse(moved)
        assertEquals(
            listOf(Constants.PLAYLIST_ID, Constants.ARTISTS_ID, Constants.ALBUMS_ID),
            order
        )
    }
}
