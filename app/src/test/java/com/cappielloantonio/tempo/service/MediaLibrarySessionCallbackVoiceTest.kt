package com.cappielloantonio.tempo.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.repository.VoiceSearchHits
import com.cappielloantonio.tempo.repository.VoiceTrackHit
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ExecutionException

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaLibrarySessionCallbackVoiceTest {
    private val browseRepository = mock<PlexBrowseRepository>()
    private val sessionMediaItemRepository = mock<SessionMediaItemRepository>()
    private val service = mock<BaseMediaService>()
    private val session = mock<MediaSession>()
    private val controller = mock<MediaSession.ControllerInfo>()
    private val player = mock<Player>()

    private lateinit var callback: MediaLibrarySessionCallback

    @Before
    fun setUp() {
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = "http://server"
            musicSectionKey = "1"
        }
        VoiceRequestLog.clear()
        whenever(session.player).thenReturn(player)
        callback =
            MediaLibrarySessionCallback(
                RuntimeEnvironment.getApplication(),
                service,
                browseRepository,
                sessionMediaItemRepository,
            )
    }

    private fun voiceItem(query: String) =
        MediaItem
            .Builder()
            .setRequestMetadata(
                MediaItem.RequestMetadata
                    .Builder()
                    .setSearchQuery(query)
                    .build(),
            ).build()

    private fun track(id: String) =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setUri("http://server/$id")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(id)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            ).build()

    private fun setItems(item: MediaItem) = callback.onSetMediaItems(session, controller, listOf(item), C.INDEX_UNSET, C.TIME_UNSET)

    @Test
    fun `a matched request queues the match and saves it`() {
        val album = VoiceTrackHit(track("1"), "b1")
        whenever(browseRepository.voiceSearch("1")).thenReturn(
            Futures.immediateFuture(VoiceSearchHits(emptyList(), emptyList(), listOf(album))),
        )
        whenever(browseRepository.getAlbumTracks("b1")).thenReturn(
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(track("0"), track("1")), null)),
        )

        mockConstruction(QueueRepository::class.java).use { construction ->
            val result = setItems(voiceItem("1")).get()

            assertEquals(listOf("0", "1"), result.mediaItems.map { it.mediaId })
            assertEquals(1, result.startIndex)
            verify(construction.constructed().single()).insertAll(result.mediaItems, true, 0)
        }
    }

    @Test
    fun `a failed request never touches the saved queue and reports the failure`() {
        whenever(browseRepository.voiceSearch(any())).thenReturn(
            Futures.immediateFuture(VoiceSearchHits(emptyList(), emptyList(), emptyList())),
        )

        mockConstruction(QueueRepository::class.java).use { construction ->
            val failure = assertThrows(ExecutionException::class.java) { setItems(voiceItem("nope")).get() }

            assertTrue(failure.cause is VoiceRequestRejected)
            assertTrue(construction.constructed().isEmpty())
        }

        shadowOf(Looper.getMainLooper()).idle()
        val sent = argumentCaptor<SessionError>()
        verify(session).sendError(sent.capture())
        assertEquals(SessionError.ERROR_SESSION_NOT_AVAILABLE_IN_REGION, sent.firstValue.code)
    }

    @Test
    fun `an empty query resumes the player's queue where it is without rewriting it`() {
        val held = listOf(track("1"), track("2"))
        whenever(player.mediaItemCount).thenReturn(2)
        whenever(player.currentMediaItemIndex).thenReturn(1)
        whenever(player.currentPosition).thenReturn(5_000L)
        whenever(service.getQueueFromPlayer(player)).thenReturn(held)

        mockConstruction(QueueRepository::class.java).use { construction ->
            val result = setItems(voiceItem("")).get()

            assertEquals(held, result.mediaItems)
            assertEquals(1, result.startIndex)
            assertEquals(5_000L, result.startPositionMs)
            assertTrue(construction.constructed().isEmpty())
        }
    }

    @Test
    fun `an empty query does not resume a queue that has played to its end`() {
        whenever(player.mediaItemCount).thenReturn(2)
        whenever(player.playbackState).thenReturn(Player.STATE_ENDED)
        whenever(player.currentMediaItemIndex).thenReturn(1)
        whenever(player.currentPosition).thenReturn(200_000L)
        whenever(service.getQueueFromPlayer(player)).thenReturn(listOf(track("1"), track("2")))
        whenever(browseRepository.getLibraryShuffle()).thenReturn(
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(track("8"), track("9")), null)),
        )

        mockConstruction(QueueRepository::class.java).use {
            val result = setItems(voiceItem("")).get()

            assertEquals(setOf("8", "9"), result.mediaItems.map { it.mediaId }.toSet())
        }
    }

    @Test
    fun `a request is logged on arrival and finished with its outcome`() {
        whenever(browseRepository.voiceSearch(any())).thenReturn(
            Futures.immediateFuture(VoiceSearchHits(emptyList(), emptyList(), emptyList())),
        )

        assertThrows(ExecutionException::class.java) { setItems(voiceItem("nope")).get() }

        val entry = VoiceRequestLog.all().single()
        assertEquals("nope", entry.query)
        assertEquals("NotFound", entry.outcome)
    }

    @Test
    fun `an id-carrying item never reaches the resolver`() {
        whenever(sessionMediaItemRepository.get("1")).thenReturn(null)

        mockConstruction(QueueRepository::class.java).use {
            setItems(track("1")).get()
        }

        verify(browseRepository, never()).voiceSearch(any())
    }

    @Test
    fun `adding a matched request returns its tracks`() {
        val hit = VoiceTrackHit(track("1"), null)
        whenever(browseRepository.voiceSearch(any())).thenReturn(
            Futures.immediateFuture(VoiceSearchHits(emptyList(), emptyList(), listOf(hit))),
        )

        val added = callback.onAddMediaItems(session, controller, listOf(voiceItem("1"))).get()

        assertEquals(listOf("1"), added.map { it.mediaId })
    }

    @Test
    fun `adding an empty query adds nothing to what is already queued`() {
        whenever(player.mediaItemCount).thenReturn(1)
        whenever(service.getQueueFromPlayer(player)).thenReturn(listOf(track("1")))

        val added = callback.onAddMediaItems(session, controller, listOf(voiceItem(""))).get()

        assertTrue(added.isEmpty())
    }
}
