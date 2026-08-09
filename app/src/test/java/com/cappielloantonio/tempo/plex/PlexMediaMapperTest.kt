package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Media
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.plex.models.Part
import com.cappielloantonio.tempo.util.ConstantsAA
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

    private fun decade(key: String? = "1980", title: String? = "1980s") = Directory().apply {
        this.key = key
        this.title = title
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

    @Test
    fun creditsTheTrackArtistRatherThanTheAlbumArtist() {
        // Measured on a live library: a compilation track carries the album artist
        // in grandparentTitle and the real performer in originalTitle, so reading
        // grandparentTitle credited every compilation to "Various Artists".
        val compilationTrack = track().apply {
            grandparentTitle = "Various Artists"
            originalTitle = "The Hold Steady"
        }

        assertEquals("The Hold Steady", PlexMediaMapper.trackArtist(compilationTrack))
    }

    @Test
    fun fallsBackToTheAlbumArtistWhenPlexSendsNoTrackArtist() {
        // The common case: Plex populates originalTitle only when the two differ.
        assertEquals(
            "Fall Out Boy",
            PlexMediaMapper.trackArtist(track().apply { grandparentTitle = "Fall Out Boy" })
        )
        assertEquals(
            "Bob Dylan",
            PlexMediaMapper.trackArtist(
                track().apply { grandparentTitle = "Bob Dylan"; originalTitle = "  " }
            )
        )
    }

    // ── decade rows ───────────────────────────────────────────

    @Test
    fun aDecadeBecomesABrowsableRowCarryingItsKey() {
        val item = PlexMediaMapper.decadeToMediaItem(decade(), ConstantsAA.DECADE_ID)!!

        assertEquals(ConstantsAA.DECADE_ID + "1980", item.mediaId)
        assertEquals("1980s", item.mediaMetadata.title)
        assertTrue(item.mediaMetadata.isBrowsable!!)
        // Never playable: a playable row opens Now Playing on tap, and a decade
        // has no single track to point at.
        assertFalse(item.mediaMetadata.isPlayable!!)
    }

    @Test
    fun aDecadeCarriesNoArtworkSoTheCarSuppliesItsOwn() {
        // Plex offers no composite for a filter value -- see issue #84. Passing
        // no artworkUri hands the row to the car's own placeholder rather than
        // to a repeated glyph that would carry no more information.
        assertNull(PlexMediaMapper.decadeToMediaItem(decade(), ConstantsAA.DECADE_ID)!!
            .mediaMetadata.artworkUri)
    }

    @Test
    fun aDecadeWithoutAKeyOrTitleIsDropped() {
        // A row whose id carries no decade would fetch nothing on tap. Filtered
        // on key and title rather than on `type`, because a decade Directory has
        // no type field at all -- unlike the section Directory that
        // LibraryClient.musicSections narrows.
        assertNull(PlexMediaMapper.decadeToMediaItem(decade(key = null), ConstantsAA.DECADE_ID))
        assertNull(PlexMediaMapper.decadeToMediaItem(decade(key = "  "), ConstantsAA.DECADE_ID))
        assertNull(PlexMediaMapper.decadeToMediaItem(decade(title = null), ConstantsAA.DECADE_ID))
    }
}
