package com.cappielloantonio.tempo.ui.fragment

import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rows the screen shows come straight from the resolved order, so what is
 * worth pinning is the mapping from id to label -- a destination missing one
 * would render a blank row, and every id in DEFAULT_ORDER must have one.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BrowseTabOrderFragmentTest {
    @Before
    fun setUp() = BrowseTabOrderFixture.clearSavedOrder()

    @Test
    fun `every destination in the default order has a label`() {
        BrowseTabOrder.DEFAULT_ORDER.forEach { id ->
            assertEquals(
                "$id must have a label or it renders as a blank row",
                true,
                BrowseTabOrderFragment.labelFor(id) != 0,
            )
        }
    }

    @Test
    fun `the labels are the ones the browse tabs already use`() {
        assertEquals(R.string.browse_playlists, BrowseTabOrderFragment.labelFor(Constants.PLAYLIST_ID))
        assertEquals(R.string.browse_artists, BrowseTabOrderFragment.labelFor(Constants.ARTISTS_ID))
        assertEquals(R.string.browse_albums, BrowseTabOrderFragment.labelFor(Constants.ALBUMS_ID))
        assertEquals(R.string.browse_discover, BrowseTabOrderFragment.labelFor(Constants.DISCOVER_ID))
        assertEquals(R.string.browse_decades, BrowseTabOrderFragment.labelFor(Constants.DECADES_ID))
    }

    @Test
    fun `an unknown id has no label rather than throwing`() {
        assertEquals(0, BrowseTabOrderFragment.labelFor("[podcastID]"))
    }
}
