package com.cappielloantonio.tempo.provider

import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which covers a decade's composite is built from, asserted on the response
 * rather than through the network, so the fetch around it can stay thin.
 */
class DecadeCompositeArtSelectionTest {

    private fun album(ratingKey: String?, thumb: String?) = Metadata().apply {
        this.type = "album"
        this.ratingKey = ratingKey
        this.thumb = thumb
    }

    private fun response(vararg items: Metadata) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply { metadata = items.toList() }
    }

    @Test
    fun takesTheFirstFourThumbs() {
        val body = response(
            album("1", "/library/metadata/1/thumb/1"),
            album("2", "/library/metadata/2/thumb/1"),
            album("3", "/library/metadata/3/thumb/1"),
            album("4", "/library/metadata/4/thumb/1"),
            album("5", "/library/metadata/5/thumb/1")
        )

        assertEquals(
            listOf(
                "/library/metadata/1/thumb/1",
                "/library/metadata/2/thumb/1",
                "/library/metadata/3/thumb/1",
                "/library/metadata/4/thumb/1"
            ),
            DecadeCompositeArt.coverThumbs(body, want = 4)
        )
    }

    @Test
    fun skipsAlbumsWithNoThumbSoTheOverFetchEarnsItsKeep() {
        // The request asks for OVER_FETCH albums precisely so a thumb-less one
        // does not leave a hole in the grid.
        val body = response(
            album("1", null),
            album("2", "  "),
            album("3", "/library/metadata/3/thumb/1"),
            album("4", "/library/metadata/4/thumb/1")
        )

        assertEquals(
            listOf("/library/metadata/3/thumb/1", "/library/metadata/4/thumb/1"),
            DecadeCompositeArt.coverThumbs(body, want = 4)
        )
    }

    @Test
    fun anAbsentContainerIsNoCoversRatherThanACrash() {
        // A section with nothing matching answers 200 with the wrapper present
        // and the list absent -- the same shape PlexBrowseRepository.itemsOf
        // already treats as an empty result rather than a failure.
        assertTrue(DecadeCompositeArt.coverThumbs(null, want = 4).isEmpty())
        assertTrue(DecadeCompositeArt.coverThumbs(PlexResponse(), want = 4).isEmpty())
    }

    @Test
    fun ignoresEntriesThatAreNotAlbums() {
        val body = response(
            album("1", "/library/metadata/1/thumb/1"),
            Metadata().apply {
                type = "track"
                ratingKey = "2"
                thumb = "/library/metadata/2/thumb/1"
            }
        )

        assertEquals(
            listOf("/library/metadata/1/thumb/1"),
            DecadeCompositeArt.coverThumbs(body, want = 4)
        )
    }
}
