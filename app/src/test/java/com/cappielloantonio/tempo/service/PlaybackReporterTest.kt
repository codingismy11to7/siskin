package com.cappielloantonio.tempo.service

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.api.search.TimelineState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Robolectric for the main looper the heartbeat is posted to. */
@RunWith(RobolectricTestRunner::class)
class PlaybackReporterTest {
    private val reports = mutableListOf<String>()
    private val track = MediaItem.Builder().setMediaId("42").build()
    private val next = MediaItem.Builder().setMediaId("43").build()
    private val player = mock<Player>()
    private lateinit var reporter: PlaybackReporter

    @Before
    fun setUp() {
        whenever(player.applicationLooper).thenReturn(Looper.getMainLooper())
        whenever(player.currentMediaItem).thenReturn(track)
        whenever(player.currentPosition).thenReturn(5_000L)
        playing()
        reporter = PlaybackReporter(player, { item, state, ms -> reports += "${item.mediaId} ${state.wire} $ms" })
    }

    private fun playing() = given(Player.STATE_READY, playWhenReady = true, isPlaying = true)

    private fun paused() = given(Player.STATE_READY, playWhenReady = false, isPlaying = false)

    private fun given(
        playbackState: Int,
        playWhenReady: Boolean,
        isPlaying: Boolean,
    ) {
        whenever(player.playbackState).thenReturn(playbackState)
        whenever(player.playWhenReady).thenReturn(playWhenReady)
        whenever(player.isPlaying).thenReturn(isPlaying)
    }

    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun positionInfo(
        index: Int,
        item: MediaItem?,
        positionMs: Long,
    ) = Player.PositionInfo(null, index, item, null, index, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET)

    private fun leave(
        reason: Int,
        from: Player.PositionInfo,
        to: Player.PositionInfo,
    ) = reporter.onPositionDiscontinuity(from, to, reason)

    @Test
    fun playingIsReportedAtOnceAndThenEveryTenSeconds() {
        reporter.onIsPlayingChanged(true)
        advance(9)
        assertEquals(listOf("42 playing 5000"), reports)

        whenever(player.currentPosition).thenReturn(15_000L)
        advance(1)

        assertEquals(listOf("42 playing 5000", "42 playing 15000"), reports)
    }

    @Test
    fun aPauseIsReportedAndStopsTheHeartbeat() {
        reporter.onIsPlayingChanged(true)
        paused()
        reporter.onIsPlayingChanged(false)
        advance(30)

        assertEquals(listOf("42 playing 5000", "42 paused 5000"), reports)
    }

    @Test
    fun aStallIsQuiet() {
        given(Player.STATE_BUFFERING, playWhenReady = true, isPlaying = false)
        reporter.onIsPlayingChanged(false)
        advance(30)

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aPauseMadeDuringAStallIsReportedOnceItIsReady() {
        given(Player.STATE_BUFFERING, playWhenReady = false, isPlaying = false)
        reporter.onIsPlayingChanged(false)
        paused()
        reporter.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(listOf("42 paused 5000"), reports)
    }

    @Test
    fun becomingReadyWhilePlayingIsLeftToIsPlaying() {
        reporter.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun theEndOfTheQueueIsStoppedNotPaused() {
        given(Player.STATE_ENDED, playWhenReady = true, isPlaying = false)
        reporter.onIsPlayingChanged(false)
        reporter.onPlaybackStateChanged(Player.STATE_ENDED)

        assertEquals(listOf("42 stopped 5000"), reports)
    }

    @Test
    fun resumingDoesNotStackHeartbeats() {
        reporter.onIsPlayingChanged(true)
        reporter.onIsPlayingChanged(true)
        reports.clear()
        advance(10)

        assertEquals(listOf("42 playing 5000"), reports)
    }

    @Test
    fun aTrackArrivingWhilePlayingIsReportedPlaying() {
        reporter.onMediaItemTransition(track, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals(listOf("42 playing 5000"), reports)
    }

    @Test
    fun aTrackArrivingWhilePausedIsReportedPaused() {
        paused()
        reporter.onMediaItemTransition(track, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals(listOf("42 paused 5000"), reports)
    }

    @Test
    fun aPausedTrackStillLoadingWaitsUntilItIsReady() {
        given(Player.STATE_BUFFERING, playWhenReady = false, isPlaying = false)
        reporter.onMediaItemTransition(track, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals(emptyList<String>(), reports)
        assertEquals(TimelineState.PAUSED, reporter.state)
    }

    @Test
    fun aTrackLoadingToPlayIsReportedPlaying() {
        given(Player.STATE_BUFFERING, playWhenReady = true, isPlaying = false)
        reporter.onMediaItemTransition(track, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)

        assertEquals(listOf("42 playing 5000"), reports)
    }

    @Test
    fun aStationTrackArrivingIsLeftToTheTopUp() {
        val station =
            MediaItem
                .Builder()
                .setMediaId("44")
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setExtras(
                            Bundle().apply {
                                putLong(PlexMediaMapper.EXTRA_PLAY_QUEUE_ID, 32194L)
                                putLong(PlexMediaMapper.EXTRA_PLAY_QUEUE_ITEM_ID, 1381657L)
                                putString(PlexMediaMapper.EXTRA_STATION_ARTIST_ID, "270597")
                            },
                        ).build(),
                ).build()

        reporter.onMediaItemTransition(station, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun skippingAwayStopsTheOutgoingTrackWhereItWasLeft() {
        leave(Player.DISCONTINUITY_REASON_SEEK, positionInfo(0, track, 230_000L), positionInfo(1, next, 0L))

        assertEquals(listOf("42 stopped 230000"), reports)
    }

    @Test
    fun aSeekWithinATrackIsNotASkip() {
        leave(Player.DISCONTINUITY_REASON_SEEK, positionInfo(0, track, 30_000L), positionInfo(0, track, 90_000L))

        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun restartingATrackStopsItWhereItWasLeft() {
        leave(Player.DISCONTINUITY_REASON_SEEK, positionInfo(0, track, 235_000L), positionInfo(0, track, 0L))

        assertEquals(listOf("42 stopped 235000"), reports)
    }

    /** By then currentPosition belongs to the new track; only oldPosition is where the old one ended. */
    @Test
    fun anAutomaticTransitionStopsTheOldTrackAtItsOwnPosition() {
        whenever(player.currentPosition).thenReturn(999_000L)

        leave(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, positionInfo(0, track, 240_000L), positionInfo(1, next, 0L))

        assertEquals(listOf("42 stopped 240000"), reports)
    }

    @Test
    fun replacingTheQueueStopsTheTrackThatWasPlaying() {
        leave(Player.DISCONTINUITY_REASON_REMOVE, positionInfo(3, track, 230_000L), positionInfo(0, next, 0L))

        assertEquals(listOf("42 stopped 230000"), reports)
    }

    @Test
    fun releaseStopsTheHeartbeatAndDetaches() {
        reporter.onIsPlayingChanged(true)
        reports.clear()
        reporter.release()
        advance(30)

        assertEquals(emptyList<String>(), reports)
        verify(player).removeListener(reporter)
    }
}
