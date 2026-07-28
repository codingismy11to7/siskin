package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.plex.models.Media
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.plex.models.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexMediaMapperTest {

    private fun track(
        ratingKey: String = "1",
        partKey: String? = "/library/parts/9/file.flac",
        thumb: String? = null,
        parentThumb: String? = null,
        grandparentThumb: String? = null
    ) = Metadata().apply {
        this.ratingKey = ratingKey
        this.type = "track"
        this.title = "Song"
        this.thumb = thumb
        this.parentThumb = parentThumb
        this.grandparentThumb = grandparentThumb
        if (partKey != null) {
            media = listOf(Media().apply { part = listOf(Part().apply { key = partKey }) })
        }
    }

    // ── part key ──────────────────────────────────────────────

    @Test
    fun readsThePartKeyFromTheFirstMediaAndPart() {
        assertEquals("/library/parts/9/file.flac", PlexMediaMapper.partKey(track()))
    }

    @Test
    fun returnsNullPartKeyWhenTheTrackHasNoMedia() {
        assertNull(PlexMediaMapper.partKey(track(partKey = null)))
    }

    @Test
    fun returnsNullPartKeyWhenMediaCarriesNoParts() {
        val noParts = Metadata().apply {
            ratingKey = "1"
            media = listOf(Media().apply { part = emptyList() })
        }
        assertNull(PlexMediaMapper.partKey(noParts))
    }

    @Test
    fun picksTheFirstPartThatActuallyHasAKey() {
        // Plex can return a part with no key alongside a usable one; taking
        // media[0].part[0] blindly would yield an unplayable track.
        val mixed = Metadata().apply {
            ratingKey = "1"
            media = listOf(
                Media().apply { part = listOf(Part(), Part().apply { key = "/good" }) }
            )
        }
        assertEquals("/good", PlexMediaMapper.partKey(mixed))
    }

    // ── artwork thumb ─────────────────────────────────────────

    @Test
    fun prefersTheItemsOwnThumb() {
        assertEquals("/own", PlexMediaMapper.artworkThumb(track(thumb = "/own", parentThumb = "/parent")))
    }

    @Test
    fun fallsBackToTheAlbumThumbThenTheArtistThumb() {
        // Individual tracks frequently carry no thumb of their own; without the
        // fallback a whole album renders as placeholder icons.
        assertEquals("/parent", PlexMediaMapper.artworkThumb(track(parentThumb = "/parent", grandparentThumb = "/gp")))
        assertEquals("/gp", PlexMediaMapper.artworkThumb(track(grandparentThumb = "/gp")))
    }

    @Test
    fun returnsNullWhenNoThumbIsAvailableAtAnyLevel() {
        assertNull(PlexMediaMapper.artworkThumb(track()))
    }

    @Test
    fun artworkThumbIgnoresCompositeEvenWhenSet() {
        // composite is a playlist-only field (the mosaic Plex substitutes for a
        // missing thumb). Tracks, albums and artists never populate it, so the
        // general chain must not start reading it -- that's PlexMediaMapper
        // .playlistToMediaItem's job specifically.
        val withComposite = track().apply { composite = "/playlists/1/composite/123" }
        assertNull(PlexMediaMapper.artworkThumb(withComposite))
    }

    // ── search merge ──────────────────────────────────────────

    @Test
    fun mergesSearchResultsAsArtistsThenAlbumsThenTracks() {
        // Plex rejects a multi-type search with HTTP 400, so the browse layer
        // issues three and merges. This ordering matches what the Subsonic
        // implementation presented.
        val merged = PlexMediaMapper.mergeSearchResults(
            artists = listOf(Metadata().apply { ratingKey = "ar"; type = "artist" }),
            albums = listOf(Metadata().apply { ratingKey = "al"; type = "album" }),
            tracks = listOf(Metadata().apply { ratingKey = "tr"; type = "track" })
        )
        assertEquals(listOf("ar", "al", "tr"), merged.map { it.ratingKey })
    }

    @Test
    fun mergeSkipsEntriesWithNoRatingKey() {
        val merged = PlexMediaMapper.mergeSearchResults(
            artists = listOf(Metadata().apply { type = "artist" }),
            albums = listOf(Metadata().apply { ratingKey = ""; type = "album" }),
            tracks = listOf(Metadata().apply { ratingKey = "tr"; type = "track" })
        )
        assertEquals(listOf("tr"), merged.map { it.ratingKey })
    }

    @Test
    fun mergeToleratesEmptyTiers() {
        assertEquals(emptyList<String>(), PlexMediaMapper.mergeSearchResults(emptyList(), emptyList(), emptyList()).map { it.ratingKey })
    }

    // ── heart state ───────────────────────────────────────────

    @Test
    fun readsHeartStateBackFromPlexUserRating() {
        // The toggle writes 10, so 10 must read back as hearted or the car
        // shows an empty heart on a track this app itself rated.
        assertTrue(PlexMediaMapper.isHearted(track().apply { userRating = 10.0 }))
    }

    @Test
    fun treatsAnAbsentOrLowerRatingAsNotHearted() {
        assertFalse(PlexMediaMapper.isHearted(track()))
        assertFalse(PlexMediaMapper.isHearted(track().apply { userRating = 0.0 }))
        assertFalse(PlexMediaMapper.isHearted(track().apply { userRating = 8.0 }))
    }
}
