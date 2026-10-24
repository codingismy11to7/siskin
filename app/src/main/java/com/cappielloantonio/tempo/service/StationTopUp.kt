package com.cappielloantonio.tempo.service

import android.util.Log
import androidx.media3.common.MediaItem
import arrow.core.Either
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Keeps an artist station's queue growing. The server extends a station only
 * once it hears which of its items is playing, so a station track is reported
 * before the queue is read back -- in that order, or the read finds nothing
 * new. See docs/decisions/2026-10-23-artist-radio-design.md.
 *
 * Holds no station state: whether a station is playing, and which, is read off
 * the current track.
 */
class StationTopUp(
    private val extend: suspend (PlexMediaMapper.StationTag) -> Either<PlexTransportFailure, List<MediaItem>>,
    private val report: suspend (MediaItem, Long) -> Unit,
) {
    private val extending = AtomicBoolean(false)

    /**
     * Reports [current] if it is a station track and returns what to append,
     * which is empty unless the queue is running low. Deduplicated against
     * [queuedMediaIds] because QueueRepository drops a repeated mediaId, which
     * would leave the saved queue out of step with the player.
     */
    suspend fun onTrackStarted(
        current: MediaItem,
        positionMs: Long,
        remainingAfterCurrent: Int,
        queuedMediaIds: Set<String>,
    ): List<MediaItem> {
        val station = PlexMediaMapper.stationTagOf(current) ?: return emptyList()
        report(current, positionMs)
        if (remainingAfterCurrent > LOW_WATER) return emptyList()
        // Rapid skips fire a track change each; one read is enough for all of them.
        if (!extending.compareAndSet(false, true)) return emptyList()
        try {
            return extend(station).fold(
                { failure ->
                    Log.w(TAG, "could not extend station ${station.playQueueId}: $failure")
                    emptyList()
                },
                { tracks -> tracks.filter { it.mediaId !in queuedMediaIds } },
            )
        } finally {
            extending.set(false)
        }
    }

    companion object {
        private const val TAG = "StationTopUp"

        /** Items left after the current one at which the queue is read back. */
        const val LOW_WATER = 2

        /**
         * What may still be appended once the read has returned. The player can
         * change while the report and read were in flight: [livePlaying] may be
         * a different queue, and [liveItems] may now hold tracks that continuous
         * play added. The station is compared by [started]'s play queue, not the
         * returned tracks', which a 404 redraw legitimately changes.
         *
         * The read returns the server's whole window, played items included, and
         * those were trimmed from the player, so a mediaId check alone would
         * append them again. Only what follows the last queued item of the
         * returned play queue is kept; mediaId stays as a net for
         * QueueRepository.insertAll, which drops a repeated mediaId.
         */
        fun appendable(
            more: List<MediaItem>,
            started: MediaItem,
            livePlaying: MediaItem?,
            liveItems: List<MediaItem>,
        ): List<MediaItem> {
            val startedQueue = PlexMediaMapper.stationTagOf(started)?.playQueueId ?: return emptyList()
            if (PlexMediaMapper.stationTagOf(livePlaying)?.playQueueId != startedQueue) return emptyList()
            val liveIds = liveItems.mapTo(HashSet()) { it.mediaId }
            return afterLastQueued(more, liveItems).filter { it.mediaId !in liveIds }
        }

        private fun afterLastQueued(
            more: List<MediaItem>,
            liveItems: List<MediaItem>,
        ): List<MediaItem> {
            val returnedQueue = more.firstNotNullOfOrNull { PlexMediaMapper.stationTagOf(it)?.playQueueId } ?: return more
            val anchor =
                liveItems
                    .mapNotNull { PlexMediaMapper.stationTagOf(it) }
                    .lastOrNull { it.playQueueId == returnedQueue }
                    ?.playQueueItemId
                    ?: return more
            val at = more.indexOfFirst { PlexMediaMapper.stationTagOf(it)?.playQueueItemId == anchor }
            if (at >= 0) return more.drop(at + 1)
            // Item ids only grow as the server appends, so a window past the anchor is told by size.
            return more.filter { (PlexMediaMapper.stationTagOf(it)?.playQueueItemId ?: Long.MIN_VALUE) > anchor }
        }

        /** The first index to keep, so [keep] played tracks stay behind [currentIndex]. */
        fun firstToKeep(
            currentIndex: Int,
            keep: Int,
        ): Int = max(0, currentIndex - keep)
    }
}
