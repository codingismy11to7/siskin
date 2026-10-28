package com.cappielloantonio.tempo.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PlaybackFailureHandlerTest {
    private val logged = mutableListOf<FailedTrack>()
    private var reprepared = 0
    private var limit = 3

    private val handler =
        PlaybackFailureHandler(
            limit = { limit },
            log = { logged += it },
            server = { "abc123" },
            reprepare = { reprepared++ },
            now = { 1_000L },
        )

    private val item =
        MediaItem
            .Builder()
            .setMediaId("275680")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle("Mr Tuxedo Shop Tux Renter")
                    .setArtist("Pete Stacker & David Bickler")
                    .setAlbumTitle("Real Men of Genius, Volume 1")
                    .build(),
            ).build()

    private fun player(hasNext: Boolean = true): Player =
        mock {
            on { currentMediaItem } doReturn item
            on { hasNextMediaItem() } doReturn hasNext
        }

    private fun http(status: Int): PlaybackException =
        ExoPlaybackException.createForSource(
            HttpDataSource.InvalidResponseCodeException(
                status,
                null,
                null,
                emptyMap(),
                DataSpec(Uri.parse("https://example.invalid/file.mp3")),
                ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )

    private fun network(): PlaybackException =
        ExoPlaybackException.createForSource(
            IOException("down"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

    @Test
    fun `a 404 skips to the next item and logs it as skipped`() {
        val player = player()
        handler.onPlayerError(player, http(404))

        verify(player).seekToNextMediaItem()
        verify(player).prepare()
        assertEquals(
            listOf(
                FailedTrack(
                    ratingKey = "275680",
                    title = "Mr Tuxedo Shop Tux Renter",
                    artist = "Pete Stacker & David Bickler",
                    album = "Real Men of Genius, Volume 1",
                    reason = "HTTP 404",
                    skipped = true,
                    atMillis = 1_000L,
                    server = "abc123",
                ),
            ),
            logged,
        )
    }

    @Test
    fun `the limit-th failure in a row stops and logs it as stopped`() {
        val player = player()
        repeat(3) { handler.onPlayerError(player, http(404)) }

        verify(player, times(2)).seekToNextMediaItem()
        assertEquals(listOf(true, true, false), logged.map { it.skipped })
    }

    @Test
    fun `reaching READY resets the count`() {
        val player = player()
        repeat(2) { handler.onPlayerError(player, http(404)) }
        handler.onPlaybackStateChanged(Player.STATE_READY)
        handler.onPlayerError(player, http(404))

        verify(player, times(3)).seekToNextMediaItem()
    }

    @Test
    fun `buffering does not reset the count`() {
        val player = player()
        repeat(2) { handler.onPlayerError(player, http(404)) }
        handler.onPlaybackStateChanged(Player.STATE_BUFFERING)
        handler.onPlayerError(player, http(404))

        verify(player, times(2)).seekToNextMediaItem()
    }

    @Test
    fun `a new queue resets the count`() {
        val player = player()
        repeat(2) { handler.onPlayerError(player, http(404)) }
        handler.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        handler.onPlayerError(player, http(404))

        verify(player, times(3)).seekToNextMediaItem()
    }

    @Test
    fun `a seek transition does not reset the count`() {
        val player = player()
        repeat(2) { handler.onPlayerError(player, http(404)) }
        handler.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        handler.onPlayerError(player, http(404))

        verify(player, times(2)).seekToNextMediaItem()
    }

    @Test
    fun `a dead last track stops on the first failure`() {
        val player = player(hasNext = false)
        handler.onPlayerError(player, http(404))

        verify(player, never()).seekToNextMediaItem()
        verify(player, never()).prepare()
        assertEquals(listOf(false), logged.map { it.skipped })
    }

    @Test
    fun `a 401 neither skips nor logs`() {
        val player = player()
        handler.onPlayerError(player, http(401))

        verify(player, never()).seekToNextMediaItem()
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `a network error reprepares and does not count`() {
        val player = player()
        handler.onPlayerError(player, network())
        repeat(2) { handler.onPlayerError(player, http(404)) }

        assertEquals(1, reprepared)
        verify(player, times(2)).seekToNextMediaItem()
    }

    @Test
    fun `a decoder error is logged by its code name`() {
        val player = player()
        handler.onPlayerError(
            player,
            ExoPlaybackException.createForSource(IOException("bad"), PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        assertEquals("ERROR_CODE_DECODING_FAILED", logged.single().reason)
    }
}
