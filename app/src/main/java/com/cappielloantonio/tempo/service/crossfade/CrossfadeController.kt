package com.cappielloantonio.tempo.service.crossfade

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos

private const val TAG = "Crossfade"

/**
 * Cross-fades by letting a second, unattached "tail" player play the outgoing
 * track's last seconds while the session's player moves to the next item early.
 * The session only ever sees a seek to the next item. See
 * docs/decisions/2026-11-07-cross-fade-design.md.
 */
@UnstableApi
class CrossfadeController(
    private val main: ExoPlayer,
    private val ramps: CrossfadeRamps,
    private val enabled: () -> Boolean,
    private val newTail: (gainDb: Float) -> ExoPlayer,
    private val currentGainDb: () -> Float,
) : Player.Listener {
    private enum class State { IDLE, ARMED, FADING }

    private val handler = Handler(Looper.getMainLooper())
    private val sync = TailSync()
    private var state = State.IDLE
    private var tail: ExoPlayer? = null
    private var tailItem: MediaItem? = null
    private var overlapMs = 0L
    private var swapAtMs = 0L
    private var tailEndMs = 0L
    private var ownSeek = false
    private var gaveUpOn: String? = null

    private val tick =
        object : Runnable {
            override fun run() {
                step()
                handler.postDelayed(this, TICK_MS)
            }
        }

    fun start() {
        main.addListener(this)
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacks(tick)
        main.removeListener(this)
        cut("released")
    }

    private fun step() {
        if (state != State.IDLE && !enabled()) return cut("setting off")
        when (state) {
            State.IDLE -> maybeArm()
            State.ARMED -> lineUp()
            State.FADING -> fade()
        }
    }

    private fun requestRamps() {
        if (!enabled()) return
        main.currentMediaItem?.mediaId?.let(ramps::request)
        nextItem()?.mediaId?.let(ramps::request)
    }

    private fun nextItem(): MediaItem? {
        val next = main.nextMediaItemIndex
        return if (next == C.INDEX_UNSET) null else main.getMediaItemAt(next)
    }

    private fun currentOverlap(outgoing: MediaItem): Long? =
        CrossfadePolicy.overlapFor(
            enabled = enabled(),
            hasNext = main.hasNextMediaItem(),
            repeatMode = main.repeatMode,
            durationMs = main.duration,
            outgoing = ramps.infoFor(outgoing.mediaId),
            incoming = nextItem()?.let { ramps.infoFor(it.mediaId) },
        )

    private fun maybeArm() {
        // Also covers the setting being turned on mid-track; request() dedups.
        requestRamps()
        if (!main.isPlaying) return
        val item = main.currentMediaItem ?: return
        if (gaveUpOn == item.mediaId) return
        val overlap = currentOverlap(item) ?: return
        val position = main.currentPosition
        if (main.duration - position > overlap + CrossfadePolicy.LEAD_MS) return

        overlapMs = overlap
        swapAtMs = main.duration - overlap
        tailEndMs = main.duration
        tailItem = item
        sync.reset()
        tail =
            newTail(currentGainDb()).apply {
                volume = 0f
                setMediaItem(item, position)
                playWhenReady = main.playWhenReady
                prepare()
            }
        state = State.ARMED
        Log.d(TAG, "armed ${item.mediaId} overlap=$overlap")
    }

    private fun lineUp() {
        val tail = tail ?: return cut("no tail")
        if (main.currentMediaItem?.mediaId != tailItem?.mediaId) return cut("main moved on")
        val mainPosition = main.currentPosition
        if (mainPosition > swapAtMs + overlapMs / 2) {
            gaveUpOn = tailItem?.mediaId
            return cut("never lined up")
        }
        if (!tail.isPlaying) return

        when (val action = sync.reading(tail.currentPosition - mainPosition, mainPosition, SystemClock.elapsedRealtime())) {
            SyncAction.Wait -> Unit
            is SyncAction.SeekTo -> tail.seekTo(action.positionMs)
            SyncAction.Aligned -> if (mainPosition >= swapAtMs) swap(mainPosition)
        }
    }

    private fun swap(mainPosition: Long) {
        val tail = tail ?: return
        // A shuffle or repeat change moves the next item without a timeline change.
        val outgoing = main.currentMediaItem
        if (outgoing == null || currentOverlap(outgoing) == null) return cut("next item changed")
        Log.d(TAG, "swap at $mainPosition, fading ${tailEndMs - mainPosition} ms")
        swapAtMs = mainPosition
        tail.volume = 1f
        main.volume = 0f
        ownSeek = true
        main.seekToNextMediaItem()
        ownSeek = false
        state = State.FADING
    }

    private fun fade() {
        val tail = tail ?: return finish()
        if (tail.playbackState == Player.STATE_ENDED ||
            tail.playbackState == Player.STATE_IDLE ||
            tail.playerError != null ||
            main.currentPosition > tailEndMs - swapAtMs + FADE_SLACK_MS
        ) {
            return finish()
        }
        val t = ((tail.currentPosition - swapAtMs).toFloat() / (tailEndMs - swapAtMs)).coerceIn(0f, 1f)
        tail.volume = cos(t * PI / 2).toFloat()
        // Timed by the tail, not main: an incoming track shorter than the fade
        // hands main to the next one with its position back at 0.
        main.volume = ((tail.currentPosition - swapAtMs) / FADE_IN_MS).coerceIn(0f, 1f)
        if (t >= 1f) finish()
    }

    private fun finish() {
        releaseTail()
        main.volume = 1f
        state = State.IDLE
    }

    private fun cut(why: String) {
        if (state == State.IDLE) return
        Log.d(TAG, "cut: $why")
        finish()
    }

    private fun releaseTail() {
        tail?.release()
        tail = null
        tailItem = null
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        gaveUpOn = null
        requestRamps()
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        requestRamps()
        if (state == State.ARMED) {
            val outgoing = main.currentMediaItem
            if (outgoing == null || currentOverlap(outgoing) == null) cut("next item changed")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cut("main stopped")
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) return cut("audio focus lost")
        tail?.playWhenReady = playWhenReady
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) cut("playback suppressed")
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // ownSeek only works because seekToNextMediaItem() dispatches its listener callbacks
        // synchronously when called from the tick, outside any listener callback.
        val userMove =
            (reason == Player.DISCONTINUITY_REASON_SEEK && !ownSeek) ||
                reason == Player.DISCONTINUITY_REASON_REMOVE
        if (userMove) cut("seek or removal")
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        if (repeatMode == Player.REPEAT_MODE_ONE) cut("repeat one")
    }

    override fun onPlayerError(error: PlaybackException) = cut("player error")

    private companion object {
        const val TICK_MS = 50L
        const val FADE_IN_MS = 500f
        const val FADE_SLACK_MS = 2000L
    }
}
