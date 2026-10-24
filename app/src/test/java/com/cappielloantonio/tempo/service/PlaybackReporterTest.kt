package com.cappielloantonio.tempo.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Robolectric for the main looper the heartbeat is posted to. */
@RunWith(RobolectricTestRunner::class)
class PlaybackReporterTest {
    private val reports = mutableListOf<String>()
    private val track = MediaItem.Builder().setMediaId("42").build()
    private val player = mock<Player>()
    private lateinit var reporter: PlaybackReporter

    @Before
    fun setUp() {
        whenever(player.currentMediaItem).thenReturn(track)
        whenever(player.currentPosition).thenReturn(5_000L)
        whenever(player.playbackState).thenReturn(Player.STATE_READY)
        reporter = PlaybackReporter(player, { item, state, ms -> reports += "${item.mediaId} $state $ms" })
    }

    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun positionInfo(
        index: Int,
        item: MediaItem?,
        positionMs: Long,
    ) = Player.PositionInfo(null, index, item, null, index, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET)

    @Test
    fun aPlayingTrackIsReportedEveryTenSeconds() {
        reporter.onIsPlayingChanged(true)
        advance(9)
        assertEquals(emptyList<String>(), reports)

        advance(1)
        whenever(player.currentPosition).thenReturn(15_000L)
        advance(10)

        assertEquals(listOf("42 playing 5000", "42 playing 15000"), reports)
    }

    @Test
    fun aPauseIsReportedAndStopsTheHeartbeat() {
        reporter.onIsPlayingChanged(true)
        reporter.onIsPlayingChanged(false)
        advance(30)

        assertEquals(listOf("42 paused 5000"), reports)
    }

    @Test
    fun aStallIsQuiet() {
        reporter.onIsPlayingChanged(true)
        whenever(player.playbackState).thenReturn(Player.STATE_BUFFERING)
        reporter.onIsPlayingChanged(false)
        advance(30)

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun theEndOfTheQueueIsNotAPause() {
        reporter.onIsPlayingChanged(true)
        whenever(player.playbackState).thenReturn(Player.STATE_ENDED)
        reporter.onIsPlayingChanged(false)

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun resumingDoesNotStackHeartbeats() {
        reporter.onIsPlayingChanged(true)
        whenever(player.playbackState).thenReturn(Player.STATE_BUFFERING)
        reporter.onIsPlayingChanged(false)
        reporter.onIsPlayingChanged(true)
        reporter.onIsPlayingChanged(true)
        advance(10)

        assertEquals(listOf("42 playing 5000"), reports)
    }

    @Test
    fun skippingAwayStopsTheOutgoingTrackWhereItWasLeft() {
        val next = MediaItem.Builder().setMediaId("43").build()

        reporter.onPositionDiscontinuity(
            positionInfo(0, track, 230_000L),
            positionInfo(1, next, 0L),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        assertEquals(listOf("42 stopped 230000"), reports)
    }

    @Test
    fun aSeekWithinATrackIsNotASkip() {
        reporter.onPositionDiscontinuity(
            positionInfo(0, track, 30_000L),
            positionInfo(0, track, 90_000L),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun anAutomaticTransitionIsLeftToTheService() {
        reporter.onPositionDiscontinuity(
            positionInfo(0, track, 240_000L),
            positionInfo(1, MediaItem.Builder().setMediaId("43").build(), 0L),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun nothingIsReportedAfterRelease() {
        reporter.onIsPlayingChanged(true)
        reporter.release()
        advance(30)

        assertEquals(emptyList<String>(), reports)
    }
}
