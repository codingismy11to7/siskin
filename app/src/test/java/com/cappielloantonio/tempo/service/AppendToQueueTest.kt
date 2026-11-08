package com.cappielloantonio.tempo.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.QueueRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@UnstableApi
class AppendToQueueTest {
    private val more = listOf(MediaItem.Builder().setMediaId("new").build())
    private val player = mock<Player>()
    private val queue = mock<QueueRepository>()

    /**
     * Repeat-all turned on while the mix request was in flight, so the insert
     * lands at 0, before the current track. Trimming after it would measure from
     * the shifted index and remove the tracks just added.
     */
    @Test
    fun trimsBeforeInsertingAndReadsTheIndexAfterTheTrim() {
        whenever(player.currentMediaItemIndex).thenReturn(9)
        var trimmedFirst = false

        BaseMediaService.appendToQueue(player, more, keep = 5, queue = queue) {
            trimmedFirst = runCatching { verify(player).removeMediaItems(0, 4) }.isSuccess
            0
        }

        assertTrue("the insert index was read before the trim", trimmedFirst)
        inOrder(player, queue) {
            verify(player).removeMediaItems(0, 4)
            verify(queue).deleteRange(0, 4)
            verify(player).addMediaItems(0, more)
            verify(queue).insertAll(more, false, 0)
        }
    }

    @Test
    fun nothingPlayedBeyondTheKeepCountIsLeftAlone() {
        whenever(player.currentMediaItemIndex).thenReturn(3)

        BaseMediaService.appendToQueue(player, more, keep = 5, queue = queue) { 4 }

        verify(player, never()).removeMediaItems(any(), any())
        verify(player).addMediaItems(4, more)
        verify(queue).insertAll(more, false, 4)
    }
}
