package com.cappielloantonio.tempo.model

import android.content.Context
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MediaItem -> Room entity -> MediaItem, for both persisted tables.
 *
 * Robolectric rather than plain JUnit because the whole contract lives in a
 * Bundle and a Uri: under this module's unitTests.returnDefaultValues a Bundle
 * discards every write and hands back null, so these assertions would pass
 * against an entity that persisted nothing at all.
 *
 * A field that silently drops out here does not crash anything -- it produces a
 * restored queue of tracks with no artist, or worse no part key, which is
 * unplayable. That is the failure this pins down.
 */
@RunWith(RobolectricTestRunner::class)
class RoomEntityRoundTripTest {

    private val server = "https://plex.example"
    private val token = "tok123"

    @Before
    fun signIn() = writeCredentials(token)

    private fun writeCredentials(accountToken: String) {
        val context = App.getContext()
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("plex_server_uri", server)
            .putString("plex_token", accountToken)
            .remove("plex_server_token")
            .commit()
    }

    private fun track(hearted: Boolean = true) = PlexMediaMapper.buildTrackMediaItem(
        ratingKey = "1234",
        title = "Song Title",
        albumTitle = "Album Title",
        artist = "Artist Name",
        thumb = "/library/metadata/1234/thumb/1699999999",
        partKey = "/library/parts/9/file.flac",
        durationMs = 250_000L,
        trackIndex = 3,
        year = 2021,
        grandparentRatingKey = "77",
        isHearted = hearted,
        parentId = Constants.QUEUE_CACHED_SOURCE,
        serverUri = server,
        token = token
    )

    private fun assertRestoresEverythingTheCarShows(restored: MediaItem) {
        assertEquals("1234", restored.mediaId)
        assertEquals("Song Title", restored.mediaMetadata.title)
        assertEquals("Album Title", restored.mediaMetadata.albumTitle)
        assertEquals("Artist Name", restored.mediaMetadata.artist)
        assertEquals(3, restored.mediaMetadata.trackNumber)
        assertEquals(2021, restored.mediaMetadata.releaseYear)
        assertEquals(250_000L, restored.mediaMetadata.durationMs)
        assertEquals(HeartRating(true), restored.mediaMetadata.userRating)
        assertEquals(
            "/library/metadata/1234/thumb/1699999999",
            restored.mediaMetadata.artworkUri!!.lastPathSegment
        )

        val extras = restored.mediaMetadata.extras!!
        assertEquals("1234", extras.getString(PlexMediaMapper.EXTRA_ID))
        assertEquals("77", extras.getString(PlexMediaMapper.EXTRA_ARTIST_ID))
        assertEquals("/library/parts/9/file.flac", extras.getString(PlexMediaMapper.EXTRA_PART_KEY))
        assertEquals(Constants.MEDIA_TYPE_MUSIC, extras.getString(PlexMediaMapper.EXTRA_TYPE))

        val uri = restored.localConfiguration!!.uri.toString()
        assertTrue(uri, uri.startsWith("$server/library/parts/9/file.flac"))
        assertTrue(uri, uri.contains("X-Plex-Token=$token"))
    }

    @Test
    fun queueRoundTripPreservesEverythingThePlayerAndTheCarRead() {
        val restored = Queue.fromMediaItem(track())!!.toMediaItem()
        assertRestoresEverythingTheCarShows(restored)
    }

    @Test
    fun sessionMediaItemRoundTripPreservesEverythingThePlayerAndTheCarRead() {
        val restored = SessionMediaItem.fromMediaItem(track())!!.toMediaItem()
        assertRestoresEverythingTheCarShows(restored)
    }

    @Test
    fun aRestoredEntryDropsTheBrowseParentTag() {
        // Deliberate: parent_id names the browse node an item was tapped in, and a
        // restored queue was not tapped in one. Keeping it would send
        // MediaLibraryServiceCallback.resolveQueueForItem back to the in-memory
        // browse cache for a queue that is already resolved.
        val restored = Queue.fromMediaItem(track())!!.toMediaItem()

        assertNull(restored.mediaMetadata.extras!!.getString(PlexMediaMapper.EXTRA_PARENT_ID))
    }

    @Test
    fun anUnheartedTrackStaysUnhearted() {
        val restored = SessionMediaItem.fromMediaItem(track(hearted = false))!!.toMediaItem()

        assertEquals(HeartRating(false), restored.mediaMetadata.userRating)
    }

    @Test
    fun aRestoredEntryRebuildsItsStreamUrlAgainstTheCurrentToken() {
        // The reason these tables store a part key and not a stream URL. A Plex
        // stream URL carries X-Plex-Token, so a persisted one stops playing the
        // moment the token rotates; rebuilding at restore time is what prevents
        // a whole saved queue from going silently dead.
        val row = Queue.fromMediaItem(track())!!
        writeCredentials("rotated-token")

        val uri = row.toMediaItem().localConfiguration!!.uri.toString()

        assertTrue(uri, uri.contains("X-Plex-Token=rotated-token"))
        assertTrue(uri, !uri.contains(token))
    }

    @Test
    fun anItemWithNoRatingKeyProducesNoRow() {
        // toMediaItem() dereferences id with !!, so a row must never be built from
        // an item that has no id to store -- the browsable nodes of the browse tree
        // reach cache() through the same path.
        val unusable = MediaItem.Builder().build()

        assertNull(Queue.fromMediaItem(unusable))
        assertNull(SessionMediaItem.fromMediaItem(unusable))
    }
}
