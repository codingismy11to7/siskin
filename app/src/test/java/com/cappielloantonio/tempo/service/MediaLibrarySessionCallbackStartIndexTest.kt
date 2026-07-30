package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cappielloantonio.tempo.model.SessionMediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.ConstantsAA
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Where playback opens when the car taps a row -- the start index
 * [MediaLibrarySessionCallback.onSetMediaItems] hands back to media3.
 *
 * The car names the tapped track and sends `C.INDEX_UNSET` for the position, so
 * the index returned here is the only thing that makes the tap mean anything:
 * media3 reads INDEX_UNSET as "open at the player's default position", which
 * under shuffle is the head of the shuffled order rather than the track pressed.
 *
 * Robolectric because the fixtures are real MediaItems: buildTrackMediaItem
 * builds a Uri and a Bundle, which `unitTests.returnDefaultValues` would
 * otherwise no-op, and the callback's constructor reads real strings for its
 * CommandButtons. QueueRepository is stubbed with mockConstruction rather than
 * left to open a database: onSetMediaItems writes the resolved queue to Room,
 * and none of that is what these tests are about.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaLibrarySessionCallbackStartIndexTest {

    private val browseRepository = mock<PlexBrowseRepository>()
    private val sessionMediaItemRepository = mock<SessionMediaItemRepository>()
    private val session = mock<MediaSession>()
    private val controller = mock<MediaSession.ControllerInfo>()

    private lateinit var callback: MediaLibrarySessionCallback

    @Before
    fun setUp() {
        // Robolectric keeps these preferences in a static field between methods,
        // so every field the gate reads is written here rather than assumed
        // absent. onGetChildren refuses to browse without a session.
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = SERVER
            musicSectionKey = "1"
        }
        whenever(session.player).thenReturn(mock<Player>())

        callback = MediaLibrarySessionCallback(
            RuntimeEnvironment.getApplication(),
            mock<BaseMediaService>(),
            browseRepository,
            sessionMediaItemRepository
        )
    }

    /**
     * The resolution path a real head unit takes: what comes back from a browse
     * tap is the media id and nothing else -- no stream, not even the row's
     * parent tag, which is why the callback logs "Fallback queue for item <id>"
     * on the emulator -- so the queue is rebuilt from the session cache the
     * browse list wrote and the tapped id has to be found in *that*.
     */
    @Test
    fun tappingATrackOpensOnThatTrack() {
        val tracks = albumTracks("1", "2", "3", "4")
        rememberAsSiblings(tracks)

        val result = setMediaItems(MediaItem.Builder().setMediaId("3").build())

        assertEquals(
            tracks.map { it.mediaId },
            result.mediaItems.map { it.mediaId }
        )
        assertEquals(2, result.startIndex)
    }

    /** The same rule through the other branch: a tap that kept its parent tag. */
    @Test
    fun tappingATaggedTrackOpensOnThatTrack() {
        val tracks = albumTracks("1", "2", "3", "4")
        browseAlbum(tracks)

        val result = setMediaItems(withParentTag(tracks[2]))

        assertEquals(2, result.startIndex)
    }

    /**
     * The shuffle row is the one tap whose opening track is ours to choose:
     * shuffle mode orders what comes *after* the current item, so a row that
     * opened at item 0 would shuffle the same artist from the same song every
     * time. Guards that opener against honouring the tap everywhere else.
     */
    @Test
    fun tappingAShuffleRowOpensSomewhereInsideTheList() {
        val tracks = albumTracks("1", "2", "3", "4")
        whenever(browseRepository.getArtistTracks(ARTIST)).thenReturn(itemList(tracks))

        val row = MediaItem.Builder()
            .setMediaId(ConstantsAA.SHUFFLE_ARTIST_ID + ARTIST)
            .build()
        val result = setMediaItems(row)

        assertEquals(tracks.size, result.mediaItems.size)
        assertTrue(
            "start index ${result.startIndex} is not a position in the queue",
            result.startIndex in tracks.indices
        )
    }

    /**
     * A tap that cannot be placed in the queue it resolved to -- a stale browse
     * cache is the way it happens -- leaves the opening position to the player
     * rather than inventing one.
     */
    @Test
    fun aTapThatIsNotInTheResolvedQueueLeavesThePositionToThePlayer() {
        browseAlbum(albumTracks("1", "2", "3", "4"))

        val strangerToTheCache = albumTracks("99").single()
        val result = setMediaItems(withParentTag(strangerToTheCache))

        assertEquals(C.INDEX_UNSET, result.startIndex)
    }

    // ─────────────────────────────────────────────────────────────

    private fun setMediaItems(tapped: MediaItem): MediaSession.MediaItemsWithStartPosition =
        mockConstruction(QueueRepository::class.java).use {
            callback.onSetMediaItems(
                session,
                controller,
                listOf(tapped),
                // What the car sends for a browse tap: it names the track and
                // says nothing about where to start.
                C.INDEX_UNSET,
                C.TIME_UNSET
            ).get()
        }

    /** What a browse list leaves in the session cache for a later tap. */
    private fun rememberAsSiblings(tracks: List<MediaItem>) {
        tracks.forEach { track ->
            whenever(sessionMediaItemRepository.get(track.mediaId)).thenReturn(
                SessionMediaItem().apply {
                    id = track.mediaId
                    timestamp = GROUP
                }
            )
        }
        whenever(sessionMediaItemRepository.getSiblings(GROUP)).thenReturn(tracks)
    }

    /** Browsing the album is what caches the list a tagged tap resolves against. */
    private fun browseAlbum(tracks: List<MediaItem>) {
        whenever(browseRepository.getAlbumTracks(ALBUM)).thenReturn(itemList(tracks))

        val children = callback.onGetChildren(
            mock<MediaLibraryService.MediaLibrarySession>(),
            controller,
            ConstantsAA.ALBUM_ID + ALBUM,
            0,
            ConstantsAA.MAX_ITEMS,
            null
        ).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, children.resultCode)
    }

    /**
     * A tapped row that kept the extras it was built with, minus its stream:
     * media3 drops localConfiguration when a MediaItem crosses the controller
     * boundary, and that absence is what stops resolveQueueForItem treating the
     * row as already resolved.
     */
    private fun withParentTag(item: MediaItem) = MediaItem.Builder()
        .setMediaId(item.mediaId)
        .setRequestMetadata(item.requestMetadata)
        .build()

    private fun itemList(tracks: List<MediaItem>) =
        Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(tracks), null))

    private fun albumTracks(vararg ratingKeys: String) = ratingKeys.map { key ->
        PlexMediaMapper.buildTrackMediaItem(
            ratingKey = key,
            title = "Track $key",
            albumTitle = "AMEN Remixes",
            artist = "Artist",
            thumb = null,
            partKey = "/library/parts/$key/file.flac",
            durationMs = null,
            trackIndex = null,
            year = null,
            grandparentRatingKey = ARTIST,
            isHearted = false,
            parentId = ConstantsAA.QUEUE_CACHED_SOURCE,
            serverUri = SERVER,
            token = "server-token"
        )
    }

    private companion object {
        const val SERVER = "https://plex.example"
        const val ALBUM = "42"
        const val ARTIST = "7"

        /** Sibling group the cached tracks share -- see SessionMediaItemRepository. */
        const val GROUP = 1_700_000_000_000L
    }
}
