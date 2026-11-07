package com.cappielloantonio.tempo.util

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A player for the code that walks the upcoming queue -- ReplayGainUtil's
 * prefetch window and QueuePreloader -- shared so a change to how that walk
 * reads the player is made once.
 *
 * Timeline.isEmpty() is final and therefore not stubbed; getWindowCount() is
 * what it reads.
 */
object QueuePlayerFixture {
    /** A player over [items] sitting on [currentIndex], advancing in index order and stopping at the end. */
    fun playerOver(
        items: List<MediaItem>,
        currentIndex: Int,
    ): Player {
        val timeline = mock<Timeline>()
        whenever(timeline.windowCount).thenReturn(items.size)
        whenever(timeline.getNextWindowIndex(any(), any(), any()))
            .thenAnswer { invocation ->
                val from = invocation.getArgument<Int>(0)
                if (from + 1 < items.size) from + 1 else C.INDEX_UNSET
            }

        val player = mock<Player>()
        whenever(player.currentTimeline).thenReturn(timeline)
        whenever(player.currentMediaItemIndex).thenReturn(currentIndex)
        whenever(player.repeatMode).thenReturn(Player.REPEAT_MODE_OFF)
        whenever(player.shuffleModeEnabled).thenReturn(false)
        items.forEachIndexed { index, item ->
            whenever(player.getMediaItemAt(index)).thenReturn(item)
        }
        return player
    }
}
