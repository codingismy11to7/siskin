package com.cappielloantonio.tempo.service.crossfade

sealed interface SyncAction {
    data object Wait : SyncAction

    data object Aligned : SyncAction

    data class SeekTo(
        val positionMs: Long,
    ) : SyncAction
}

/**
 * Decides how to line the tail player up with the session's player.
 *
 * Single position readings jump by ~250 ms, so it judges a median of five.
 * It corrects only by seeking: changing the tail's speed re-baselines its
 * position estimate and makes the readings worse.
 */
class TailSync {
    private val gaps = ArrayList<Long>(SAMPLES)
    private var offsetMs = FIRST_OFFSET_MS
    private var corrected = false
    private var settleUntilMs = 0L

    fun reset() {
        gaps.clear()
        offsetMs = FIRST_OFFSET_MS
        corrected = false
        settleUntilMs = 0L
    }

    fun reading(
        gapMs: Long,
        mainPositionMs: Long,
        nowMs: Long,
    ): SyncAction {
        if (nowMs < settleUntilMs) return SyncAction.Wait
        gaps.add(gapMs)
        if (gaps.size < SAMPLES) return SyncAction.Wait
        val median = gaps.sorted()[SAMPLES / 2]
        gaps.clear()
        if (kotlin.math.abs(median) <= ALIGNED_MS) return SyncAction.Aligned
        if (corrected) offsetMs -= median
        corrected = true
        settleUntilMs = nowMs + SETTLE_MS
        return SyncAction.SeekTo(mainPositionMs + offsetMs)
    }

    private companion object {
        const val SAMPLES = 5
        const val ALIGNED_MS = 25L
        const val FIRST_OFFSET_MS = 100L
        const val SETTLE_MS = 400L
    }
}
