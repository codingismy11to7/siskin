package com.cappielloantonio.tempo.plex

import androidx.media3.common.HeartRating
import com.cappielloantonio.tempo.plex.models.Media
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.plex.models.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mapper's actual output. Robolectric rather than plain JUnit because
 * returnDefaultValues makes Bundle discard every write and hand back null, under
 * which these assertions would pass against any implementation at all.
 */
@RunWith(RobolectricTestRunner::class)
class PlexMediaMapperAssemblyTest {

    private val serverUri = "https://plex.example"
    private val token = "tok123"

    private fun track() = Metadata().apply {
        ratingKey = "1234"
        type = "track"
        title = "Song Title"
        parentTitle = "Album Title"
        grandparentTitle = "Artist Name"
        parentRatingKey = "55"
        grandparentRatingKey = "77"
        thumb = "/library/metadata/1234/thumb/1699999999"
        duration = 250_000L
        index = 3
        year = 2021
        media = listOf(Media().apply { part = listOf(Part().apply { key = "/library/parts/9/file.flac" }) })
    }

    @Test
    fun carriesEveryBundleKeyLiveCodeReads() {
        val item = PlexMediaMapper.trackToMediaItem(track(), "parent-42", serverUri, token)!!
        val extras = item.mediaMetadata.extras!!

        assertEquals("1234", extras.getString(PlexMediaMapper.EXTRA_ID))
        assertEquals("77", extras.getString(PlexMediaMapper.EXTRA_ARTIST_ID))
        assertEquals("parent-42", extras.getString(PlexMediaMapper.EXTRA_PARENT_ID))
        assertEquals("/library/parts/9/file.flac", extras.getString(PlexMediaMapper.EXTRA_PART_KEY))
        assertEquals("/library/metadata/1234/thumb/1699999999", extras.getString(PlexMediaMapper.EXTRA_THUMB))
        assertNotNull(extras.getString(PlexMediaMapper.EXTRA_TYPE))
    }

    @Test
    fun buildsAPlayableStreamUrlFromThePartKey() {
        val item = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, token)!!
        val uri = item.localConfiguration!!.uri.toString()

        assertTrue(uri, uri.startsWith("https://plex.example/library/parts/9/file.flac"))
        assertTrue(uri, uri.contains("X-Plex-Token=tok123"))
        assertEquals(uri, extrasUri(item))
    }

    private fun extrasUri(item: androidx.media3.common.MediaItem) =
        item.mediaMetadata.extras!!.getString(PlexMediaMapper.EXTRA_URI)

    @Test
    fun surfacesTitleArtistAndAlbumTheCarDisplays() {
        val item = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, token)!!

        assertEquals("1234", item.mediaId)
        assertEquals("Song Title", item.mediaMetadata.title)
        assertEquals("Artist Name", item.mediaMetadata.artist)
        assertEquals("Album Title", item.mediaMetadata.albumTitle)
        assertEquals(3, item.mediaMetadata.trackNumber)
        assertEquals(2021, item.mediaMetadata.releaseYear)
        assertTrue(item.mediaMetadata.isPlayable!!)
    }

    @Test
    fun artworkUriSurvivesTheMultiSegmentThumbPath() {
        // Uri.Builder.appendPath escapes the separators and getLastPathSegment
        // decodes them, so the whole Plex thumb path comes back intact. Asserted
        // here because the round trip is not obvious from reading either side.
        val item = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, token)!!
        val artwork = item.mediaMetadata.artworkUri!!

        assertEquals("/library/metadata/1234/thumb/1699999999", artwork.lastPathSegment)
    }

    @Test
    fun rebuildsTheStreamUrlAgainstCurrentCredentialsRatherThanStoredOnes() {
        // The bug partKey exists to prevent: a Plex stream URL carries the token,
        // so a persisted URL breaks when the token rotates. Same part key, new
        // token, must yield a URL with the new token.
        val first = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, "old-token")!!
        val second = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, "new-token")!!

        assertTrue(first.localConfiguration!!.uri.toString().contains("old-token"))
        assertTrue(second.localConfiguration!!.uri.toString().contains("new-token"))
    }

    @Test
    fun aTrackWithNoPlayablePartStillMapsWithoutCrashing() {
        // A queue restore that hits one unplayable entry must not abort the batch;
        // the Subsonic original crashed the app on launch this way (issue #705).
        val noPart = track().apply { media = null }
        val item = PlexMediaMapper.trackToMediaItem(noPart, null, serverUri, token)

        assertNotNull(item)
        assertEquals("1234", item!!.mediaId)
    }

    @Test
    fun returnsNullForMetadataWithNoRatingKey() {
        val unusable = Metadata().apply { type = "track"; title = "Nameless" }
        assertEquals(null, PlexMediaMapper.trackToMediaItem(unusable, null, serverUri, token))
    }

    @Test
    fun browsableItemsAreBrowsableAndNotPlayable() {
        val album = PlexMediaMapper.albumToMediaItem(
            Metadata().apply { ratingKey = "55"; type = "album"; title = "Album"; parentTitle = "Artist" },
            "[albumID]", serverUri, token
        )!!

        assertEquals("[albumID]55", album.mediaId)
        assertTrue(album.mediaMetadata.isBrowsable!!)
        assertEquals(false, album.mediaMetadata.isPlayable)
    }

    @Test
    fun heartStateRidesOnUserRating() {
        val hearted = PlexMediaMapper.buildTrackMediaItem(
            ratingKey = "1234", title = "T", albumTitle = "A", artist = "R",
            thumb = null, partKey = "/p", durationMs = 1L, trackIndex = 1, year = 2020,
            parentRatingKey = "55", grandparentRatingKey = "77", isHearted = true,
            parentId = null, serverUri = serverUri, token = token
        )

        assertTrue((hearted.mediaMetadata.userRating as HeartRating).isHeart)
    }

    @Test
    fun aPlexRatingOfTenArrivesAsAFilledHeart() {
        val rated = PlexMediaMapper.trackToMediaItem(
            track().apply { userRating = 10.0 }, null, serverUri, token
        )!!
        assertTrue((rated.mediaMetadata.userRating as HeartRating).isHeart)
    }

    @Test
    fun readTrackFieldsRecoversEverythingTheEntitiesPersist() {
        // The shared reader both Room entities use. If a field drops out here,
        // a restored queue loses it silently -- no crash, just a track with no
        // artist or no part key, which is unplayable.
        val item = PlexMediaMapper.trackToMediaItem(
            track().apply { userRating = 10.0 }, "parent-42", serverUri, token
        )!!
        val fields = PlexMediaMapper.readTrackFields(item)!!

        assertEquals("1234", fields.ratingKey)
        assertEquals("Song Title", fields.title)
        assertEquals("Album Title", fields.albumTitle)
        assertEquals("Artist Name", fields.artist)
        assertEquals("/library/metadata/1234/thumb/1699999999", fields.thumb)
        assertEquals("/library/parts/9/file.flac", fields.partKey)
        assertEquals(250_000L, fields.durationMs)
        assertEquals(3, fields.trackIndex)
        assertEquals(2021, fields.year)
        assertEquals("55", fields.parentRatingKey)
        assertEquals("77", fields.grandparentRatingKey)
        assertTrue(fields.isHearted)
    }

    @Test
    fun readTrackFieldsReturnsNullForAnItemWithNoId() {
        assertEquals(null, PlexMediaMapper.readTrackFields(null))
    }
}
