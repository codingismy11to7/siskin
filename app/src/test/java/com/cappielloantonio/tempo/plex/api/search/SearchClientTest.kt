package com.cappielloantonio.tempo.plex.api.search

import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchClientTest {

    private fun response(vararg items: Metadata) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply { metadata = items.toList() }
    }

    private fun item(ratingKey: String, type: String) = Metadata().apply {
        this.ratingKey = ratingKey
        this.type = type
    }

    @Test
    fun keepsTracksAlbumsAndArtists() {
        val results = SearchClient.playableResults(
            response(item("1", "track"), item("2", "album"), item("3", "artist"))
        )
        assertEquals(3, results.size)
    }

    @Test
    fun dropsResultTypesTheAppCannotHandle() {
        // Even a section-scoped search can return clips or unknown types.
        val results = SearchClient.playableResults(
            response(item("1", "track"), item("2", "clip"), item("3", "movie"))
        )
        assertEquals(1, results.size)
        assertEquals("1", results.single().ratingKey)
    }

    @Test
    fun dropsResultsMissingARatingKey() {
        // Without a ratingKey there is nothing to browse or play.
        val results = SearchClient.playableResults(response(item("", "track"), item("2", "track")))
        assertEquals(1, results.size)
        assertEquals("2", results.single().ratingKey)
    }

    @Test
    fun returnsEmptyRatherThanNullForAnAbsentContainer() {
        assertTrue(SearchClient.playableResults(null).isEmpty())
        assertTrue(SearchClient.playableResults(PlexResponse()).isEmpty())
    }
}
