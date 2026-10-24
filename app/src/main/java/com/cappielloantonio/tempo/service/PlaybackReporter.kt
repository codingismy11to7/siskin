package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.plex.api.search.SearchClient

/**
 * Tells the Plex server about playback between the reports BaseMediaService
 * sends at a track's edges: a heartbeat while playing, a pause, and a skip.
 * See docs/decisions/2026-10-23-playback-reporting-design.md.
 */
@UnstableApi
class PlaybackReporter(
    private val player: Player,
    private val report: (MediaItem, String, Long) -> Unit,
    private val intervalMs: Long = HEARTBEAT_MS,
) : Player.Listener {
    // The main looper because it is the player's thread, where currentPosition must be read.
    private val handler = Handler(Looper.getMainLooper())

    private val heartbeat =
        object : Runnable {
            override fun run() {
                player.currentMediaItem?.let { report(it, SearchClient.STATE_PLAYING, player.currentPosition) }
                handler.postDelayed(this, intervalMs)
            }
        }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(heartbeat)
        if (isPlaying) {
            handler.postDelayed(heartbeat, intervalMs)
        } else if (player.playbackState == Player.STATE_READY) {
            // Not playWhenReady: an audio-focus loss can silence the car with it still true.
            player.currentMediaItem?.let { report(it, SearchClient.STATE_PAUSED, player.currentPosition) }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
        val left = oldPosition.mediaItem ?: return
        report(left, SearchClient.STATE_STOPPED, oldPosition.positionMs)
    }

    fun release() {
        handler.removeCallbacks(heartbeat)
    }

    companion object {
        const val HEARTBEAT_MS = 10_000L
    }
}
