package com.cappielloantonio.tempo.plex.api.library

import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryClientTest {

    private fun response(vararg sections: Directory) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply { directory = sections.toList() }
    }

    private fun section(key: String, type: String, title: String) = Directory().apply {
        this.key = key
        this.type = type
        this.title = title
    }

    @Test
    fun keepsOnlyMusicSections() {
        // Plex reports a music library's type as "artist", not "music".
        val sections = LibraryClient.musicSections(
            response(
                section("1", "movie", "Films"),
                section("2", "artist", "Music"),
                section("3", "show", "TV")
            )
        )
        assertEquals(1, sections.size)
        assertEquals("Music", sections.single().title)
    }

    @Test
    fun keepsEveryMusicSectionWhenThereAreSeveral() {
        val sections = LibraryClient.musicSections(
            response(section("1", "artist", "Music"), section("2", "artist", "Podcasts"))
        )
        assertEquals(2, sections.size)
    }

    @Test
    fun returnsEmptyRatherThanNullForAnAbsentOrEmptyContainer() {
        assertTrue(LibraryClient.musicSections(null).isEmpty())
        assertTrue(LibraryClient.musicSections(PlexResponse()).isEmpty())
        assertTrue(LibraryClient.musicSections(response()).isEmpty())
    }

    @Test
    fun ignoresSectionsMissingAKey() {
        // A section we cannot address is not browsable.
        val sections = LibraryClient.musicSections(
            response(section("", "artist", "Broken"), section("2", "artist", "Music"))
        )
        assertEquals(1, sections.size)
        assertEquals("2", sections.single().key)
    }
}
