package com.cappielloantonio.tempo.service.crossfade

import org.junit.Assert.assertEquals
import org.junit.Test

class TailSyncTest {
    private val sync = TailSync()

    /** Feeds five readings 50 ms apart starting at [startMs]; returns the last action. */
    private fun batch(
        gaps: List<Long>,
        mainPositionMs: Long = 100_000L,
        startMs: Long = 0L,
    ): SyncAction =
        gaps
            .mapIndexed { i, gap -> sync.reading(gap, mainPositionMs, startMs + i * 50L) }
            .last()

    @Test
    fun waitsUntilItHasFiveReadings() {
        assertEquals(SyncAction.Wait, sync.reading(0L, 100_000L, 0L))
    }

    @Test
    fun aMedianWithin25MsIsAligned() {
        // One reading jumps ~250 ms in any 250 ms window; the median ignores it.
        assertEquals(SyncAction.Aligned, batch(listOf(10L, -5L, 260L, 20L, 0L)))
    }

    @Test
    fun theFirstCorrectionSeeksAFixedStepAhead() {
        assertEquals(SyncAction.SeekTo(100_100L), batch(listOf(-112L, -113L, 140L, -114L, -113L)))
    }

    @Test
    fun laterCorrectionsSubtractTheMeasuredGap() {
        batch(listOf(-112L, -113L, 140L, -114L, -113L))
        // Settling: readings inside 400 ms of the seek are ignored.
        assertEquals(SyncAction.Wait, sync.reading(-70L, 100_500L, 300L))
        assertEquals(SyncAction.SeekTo(100_570L + 170L), batch(listOf(-70L, -70L, -70L, -70L, -70L), 100_570L, 700L))
    }

    @Test
    fun resetForgetsTheLearnedOffset() {
        batch(listOf(-112L, -113L, 140L, -114L, -113L))
        sync.reset()
        assertEquals(SyncAction.SeekTo(100_100L), batch(listOf(-50L, -50L, -50L, -50L, -50L)))
    }
}
