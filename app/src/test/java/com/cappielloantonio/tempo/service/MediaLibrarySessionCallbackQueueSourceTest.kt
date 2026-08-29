package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.Constants
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * That a tapped playlist track queues the whole playlist, not the browse
 * page the car happened to cache.
 *
 * `queueSourceCache` is one slot under the constant `QUEUE_CACHED_SOURCE`,
 * which is all a tapped track's parent tag ever carries -- it cannot say
 * which node produced the cached list. `queueSource` is what lets a playlist
 * tap re-fetch its own tracks instead of replaying whatever browse page was
 * cached last, which the car's own IPC ceiling already cut short -- and,
 * since it can itself be stale, what a re-fetch is trusted against before it
 * is issued. See `docs/decisions/2026-08-28-mix-paging-design.md`.
 *
 * Robolectric and `mockConstruction(QueueRepository::class.java)` for the
 * same reasons as `MediaLibrarySessionCallbackShuffleTest`, which this is
 * modeled on.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaLibrarySessionCallbackQueueSourceTest {
    private val browseRepository = mock<PlexBrowseRepository>()
    private val sessionMediaItemRepository = mock<SessionMediaItemRepository>()
    private val session = mock<MediaSession>()
    private val controller = mock<MediaSession.ControllerInfo>()

    private lateinit var callback: MediaLibrarySessionCallback

    @Before
    fun setUp() {
        // Robolectric keeps these preferences in a static field between methods,
        // so every field the gate reads is written here rather than assumed
        // absent.
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = SERVER
            musicSectionKey = "1"
        }

        callback =
            MediaLibrarySessionCallback(
                RuntimeEnvironment.getApplication(),
                mock<BaseMediaService>(),
                browseRepository,
                sessionMediaItemRepository,
            )
    }

    @Test
    fun `tapping a track in a playlist queues the whole playlist`() {
        // The browse list the car rendered was cut by its own IPC ceiling, so
        // replaying it stops playback a couple of hundred tracks in. The tap
        // re-fetches the node instead. See the 2026-08-28 mix paging design.
        //
        // The tapped track ("2") sits at a different index in each list --
        // 1 in `browsed`, 2 in `whole` -- so the assertion below only passes
        // if the start index is recomputed against the re-fetched list rather
        // than carried over from the browsed one.
        val browsed = playlistTracks("1", "2")
        val whole = playlistTracks("0", "1", "2", "3", "4")
        whenever(browseRepository.getPlaylistTracksForQueue("9")).thenReturn(itemList(whole))

        browseNode(Constants.PLAYLIST_ID + "9", browsed)
        val queue = setMediaItems(browsed[1])

        assertEquals(5, queue.mediaItems.size)
        assertEquals(2, queue.startIndex)
    }

    @Test
    fun `tapping a row in the playlists tab does not re-fetch`() {
        // "[playlistID]" bare is the listing node, not a playlist's tracks --
        // removePrefix would leave an empty id and query playlist "".
        browseNode(Constants.PLAYLIST_ID, playlistTracks("1", "2"))
        setMediaItems(playlistTracks("1", "2")[0])

        verify(browseRepository, never()).getPlaylistTracksForQueue(any())
    }

    @Test
    fun `tapping a stale row from a since-superseded playlist does not re-fetch`() {
        // Browsing B after A leaves the recorded node pointing at B while a
        // still-displayed row from A gets tapped -- two browses finishing out
        // of order, or a screen the car re-renders without re-subscribing,
        // reach the same state. The tapped row is not a member of B's
        // recorded items, so the guard must refuse the re-fetch rather than
        // hand back all of B. See finding 1 of the 2026-08-28 mix paging
        // design fix-pass.
        val playlistA = playlistTracks("1", "2")
        browseNode(Constants.PLAYLIST_ID + "9", playlistA)

        val playlistB = playlistTracks("5", "6")
        val wholeB = playlistTracks("5", "6", "7", "8")
        whenever(browseRepository.getPlaylistTracksForQueue("10")).thenReturn(itemList(wholeB))
        browseNode(Constants.PLAYLIST_ID + "10", playlistB)

        val queue = setMediaItems(playlistA[0])

        verify(browseRepository, never()).getPlaylistTracksForQueue(any())
        // "7" and "8" exist only in the re-fetched playlist B -- their
        // absence is what shows the substitution never happened.
        assertTrue(queue.mediaItems.none { it.mediaId == "7" || it.mediaId == "8" })
    }

    @Test
    fun `a failed re-fetch falls back to the recorded playlist page`() {
        val browsed = playlistTracks("1", "2")
        whenever(browseRepository.getPlaylistTracksForQueue("9"))
            .thenReturn(Futures.immediateFailedFuture(IOException("boom")))

        browseNode(Constants.PLAYLIST_ID + "9", browsed)
        val queue = setMediaItems(browsed[1])

        assertEquals(browsed.map { it.mediaId }, queue.mediaItems.map { it.mediaId })
        assertEquals(1, queue.startIndex)
    }

    // ─────────────────────────────────────────────────────────────

    private fun setMediaItems(tapped: MediaItem): MediaSession.MediaItemsWithStartPosition =
        mockConstruction(QueueRepository::class.java).use {
            callback
                .onSetMediaItems(
                    session,
                    controller,
                    listOf(tapped),
                    // What the car sends for a browse tap: it names the track and
                    // says nothing about where to start.
                    C.INDEX_UNSET,
                    C.TIME_UNSET,
                ).get()
        }

    /**
     * Drives a node through `onGetChildren` the way a real browse would, so
     * the callback records both the cached list and the node it came from --
     * rather than reaching into the private top-level cache directly.
     */
    private fun browseNode(
        id: String,
        items: List<MediaItem>,
    ) {
        if (id == Constants.PLAYLIST_ID) {
            whenever(browseRepository.getPlaylists(Constants.PLAYLIST_ID)).thenReturn(itemList(items))
        } else if (id.startsWith(Constants.PLAYLIST_ID)) {
            whenever(browseRepository.getPlaylistTracks(id.removePrefix(Constants.PLAYLIST_ID))).thenReturn(itemList(items))
        }

        val children =
            callback
                .onGetChildren(
                    mock<MediaLibraryService.MediaLibrarySession>(),
                    controller,
                    id,
                    0,
                    Constants.MAX_ITEMS,
                    null,
                ).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, children.resultCode)
    }

    private fun itemList(tracks: List<MediaItem>) = Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(tracks), null))

    private fun playlistTracks(vararg ratingKeys: String) =
        ratingKeys.map { key ->
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
                grandparentRatingKey = "7",
                isHearted = false,
                parentId = Constants.QUEUE_CACHED_SOURCE,
                serverUri = SERVER,
                token = "server-token",
            )
        }

    private companion object {
        const val SERVER = "https://plex.example"
    }
}
