package com.cappielloantonio.tempo.service

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.api.search.SearchClient

/**
 * Everything Siskin tells the Plex server about playback: a track arriving,
 * a heartbeat while it plays, a pause, and a track being left.
 * See docs/decisions/2026-10-23-playback-reporting-design.md.
 */
@UnstableApi
class PlaybackReporter(
    private val player: Player,
    private val report: (MediaItem, String, Long) -> Unit,
    private val intervalMs: Long = HEARTBEAT_MS,
) : Player.Listener {
    private val handler = Handler(player.applicationLooper)

    private val heartbeat =
        object : Runnable {
            override fun run() {
                reportCurrent(SearchClient.STATE_PLAYING)
                handler.postDelayed(this, intervalMs)
            }
        }

    /**
     * Paused only when it is ready and silent. Not playWhenReady alone: an
     * audio-focus loss can silence the car with it still true. While loading,
     * though, playWhenReady is all there is to go on.
     */
    val state: String
        get() =
            if (player.isPlaying || (player.playbackState != Player.STATE_READY && player.playWhenReady)) {
                SearchClient.STATE_PLAYING
            } else {
                SearchClient.STATE_PAUSED
            }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // The station top-up reports its own tracks, and has to finish doing so
        // before it reads the play queue back.
        if (mediaItem == null || PlexMediaMapper.stationTagOf(mediaItem) != null) return
        // A paused track still loading is reported once it is ready.
        if (state == SearchClient.STATE_PAUSED && player.playbackState != Player.STATE_READY) return
        report(mediaItem, state, player.currentPosition)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(heartbeat)
        if (isPlaying) {
            reportCurrent(SearchClient.STATE_PLAYING)
            handler.postDelayed(heartbeat, intervalMs)
        } else if (player.playbackState == Player.STATE_READY) {
            reportCurrent(SearchClient.STATE_PAUSED)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            // A pause made while stalled changes nothing isPlaying reports.
            Player.STATE_READY -> if (!player.isPlaying) reportCurrent(SearchClient.STATE_PAUSED)

            Player.STATE_ENDED -> reportCurrent(SearchClient.STATE_STOPPED)

            Player.STATE_BUFFERING, Player.STATE_IDLE -> Unit
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val left = oldPosition.mediaItem ?: return
        val leaving =
            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    true
                }

                // Replacing the queue, as choosing something else to play does.
                Player.DISCONTINUITY_REASON_REMOVE -> {
                    true
                }

                Player.DISCONTINUITY_REASON_SEEK -> {
                    oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
                        // Back to the start of the same track: "previous", or "next"
                        // on a one-track queue that repeats.
                        (newPosition.positionMs == 0L && oldPosition.positionMs > 0L)
                }

                else -> {
                    false
                }
            }
        // oldPosition.positionMs, not currentPosition: the player has already moved on.
        if (leaving) report(left, SearchClient.STATE_STOPPED, oldPosition.positionMs)
    }

    fun release() {
        handler.removeCallbacks(heartbeat)
        player.removeListener(this)
    }

    private fun reportCurrent(state: String) {
        player.currentMediaItem?.let { report(it, state, player.currentPosition) }
    }

    companion object {
        const val HEARTBEAT_MS = 10_000L
    }
}
