package com.cappielloantonio.tempo.util

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * The window the gain prefetch opens retrievers for.
 *
 * It used to be the entire queue -- a 60-track playlist meant 60 retrievers
 * through a two-thread pool with a 20-second timeout each, at the moment the
 * queue was set. Three is enough for the pending-gain handoff at a gapless
 * boundary plus a skip or two.
 *
 * The current item is deliberately absent: setReplayGain already reads its gain
 * out of the metadata the player itself parsed, on onTracksChanged, at no
 * network cost. Walking the timeline rather than counting up from the index is
 * what keeps this right under shuffle, which is the same reason
 * QueuePreloader.collectUpcomingStreamUris does it.
 *
 * Robolectric only because unitTests.returnDefaultValues stubs android.jar: a
 * plain JUnit run can pass while a framework call it depends on quietly returns
 * null. Timeline.isEmpty() is final and therefore not stubbed here --
 * getWindowCount() is what it reads, and an unstubbed mock returning 0 is
 * already an empty timeline.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ReplayGainPrefetchWindowTest {
    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    /**
     * A player over [ids] sitting on [currentIndex], whose timeline advances in
     * plain index order and stops at the end.
     */
    private fun playerOver(
        ids: List<String>,
        currentIndex: Int,
    ): Player {
        val timeline = mock<Timeline>()
        whenever(timeline.windowCount).thenReturn(ids.size)
        whenever(timeline.getNextWindowIndex(any(), any(), any()))
            .thenAnswer { invocation ->
                val from = invocation.getArgument<Int>(0)
                if (from + 1 < ids.size) from + 1 else C.INDEX_UNSET
            }

        val player = mock<Player>()
        whenever(player.currentTimeline).thenReturn(timeline)
        whenever(player.currentMediaItemIndex).thenReturn(currentIndex)
        whenever(player.repeatMode).thenReturn(Player.REPEAT_MODE_OFF)
        whenever(player.shuffleModeEnabled).thenReturn(false)
        ids.forEachIndexed { index, id ->
            whenever(player.getMediaItemAt(index)).thenReturn(item(id))
        }
        return player
    }

    @Test
    fun `it takes the next three, and not the current one`() {
        val player = playerOver(listOf("a", "b", "c", "d", "e", "f"), currentIndex = 0)

        val targets = ReplayGainUtil.upcomingPrefetchTargets(player, 3)

        assertEquals(listOf("b", "c", "d"), targets.map { it.mediaId })
    }

    @Test
    fun `it stops at the end of a queue shorter than the window`() {
        val player = playerOver(listOf("a", "b"), currentIndex = 0)

        val targets = ReplayGainUtil.upcomingPrefetchTargets(player, 3)

        assertEquals(listOf("b"), targets.map { it.mediaId })
    }

    @Test
    fun `the last track of a queue has nothing ahead of it`() {
        val player = playerOver(listOf("a", "b", "c"), currentIndex = 2)

        val targets = ReplayGainUtil.upcomingPrefetchTargets(player, 3)

        assertEquals(emptyList<String>(), targets.map { it.mediaId })
    }

    @Test
    fun `an empty timeline yields nothing`() {
        val player = mock<Player>()
        // An unstubbed Timeline mock reports windowCount = 0, which is what the
        // final isEmpty() reads.
        whenever(player.currentTimeline).thenReturn(mock<Timeline>())
        whenever(player.currentMediaItemIndex).thenReturn(0)

        assertEquals(0, ReplayGainUtil.upcomingPrefetchTargets(player, 3).size)
    }

    @Test
    fun `it follows the timeline's order rather than the index's`() {
        // What shuffle looks like from here: the timeline hands back an order
        // that is not index + 1, and the window must follow it. Counting up from
        // currentMediaItemIndex would return c, d, e and prefetch three tracks
        // the player is not about to reach.
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val shuffled = mapOf(1 to 4, 4 to 0, 0 to 3)

        val timeline = mock<Timeline>()
        whenever(timeline.windowCount).thenReturn(ids.size)
        whenever(timeline.getNextWindowIndex(any(), any(), any()))
            .thenAnswer { invocation ->
                shuffled[invocation.getArgument<Int>(0)] ?: C.INDEX_UNSET
            }

        val player = mock<Player>()
        whenever(player.currentTimeline).thenReturn(timeline)
        whenever(player.currentMediaItemIndex).thenReturn(1)
        whenever(player.repeatMode).thenReturn(Player.REPEAT_MODE_OFF)
        whenever(player.shuffleModeEnabled).thenReturn(true)
        ids.forEachIndexed { index, id ->
            whenever(player.getMediaItemAt(index)).thenReturn(item(id))
        }

        val targets = ReplayGainUtil.upcomingPrefetchTargets(player, 3)

        assertEquals(listOf("e", "a", "d"), targets.map { it.mediaId })
    }

    @Test
    fun `repeat-one does not prefetch the track it is repeating`() {
        // Timeline.getNextWindowIndex returns the same index under REPEAT_MODE_ONE,
        // so without the currentIndex guard this loops until it has three copies
        // of the playing track.
        val timeline = mock<Timeline>()
        whenever(timeline.windowCount).thenReturn(3)
        whenever(timeline.getNextWindowIndex(any(), any(), any()))
            .thenAnswer { invocation -> invocation.getArgument<Int>(0) }

        val player = mock<Player>()
        whenever(player.currentTimeline).thenReturn(timeline)
        whenever(player.currentMediaItemIndex).thenReturn(1)
        whenever(player.repeatMode).thenReturn(Player.REPEAT_MODE_ONE)
        whenever(player.shuffleModeEnabled).thenReturn(false)

        assertEquals(0, ReplayGainUtil.upcomingPrefetchTargets(player, 3).size)
    }
}
