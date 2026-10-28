package com.cappielloantonio.tempo.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Carries out [PlaybackFailurePolicy]'s decision on the player and keeps the
 * count of track failures in a row. A stop does nothing to the player: it stays
 * in its error state (#270 covers the wording).
 *
 * An extension resumes from [onTimelineChanged] rather than from the
 * extension's own completion: continuous play appends through a MediaBrowser,
 * so its items reach the player after it reports done. See
 * docs/decisions/2026-10-27-dead-last-track-design.md.
 */
@UnstableApi
class PlaybackFailureHandler(
    private val limit: () -> Int,
    private val log: (FailedTrack) -> Unit,
    private val markSkipped: (String) -> Unit,
    private val server: () -> String?,
    private val reprepare: (Player) -> Unit,
    private val canExtend: (Player) -> Boolean,
    private val extend: (Player) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var failuresInARow = 0

    /** The dead last track an extension was started for, until the queue grows past it. */
    private var awaitingExtension: Awaited? = null

    /** [itemsAfter] counts in queue order, which shuffle does not change. */
    private data class Awaited(
        val mediaId: String,
        val itemsAfter: Int,
    )

    fun onPlayerError(
        player: Player,
        error: PlaybackException,
    ) {
        awaitingExtension = null
        val httpStatus = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val hasNext = player.hasNextMediaItem()
        val decision =
            PlaybackFailurePolicy.decide(
                errorCode = error.errorCode,
                httpStatus = httpStatus,
                failuresBefore = failuresInARow,
                limit = limit(),
                hasNext = hasNext,
                canExtend = !hasNext && canExtend(player),
            )
        failuresInARow = decision.failuresInARow
        if (decision.trackFailure) {
            record(player, httpStatus?.let { "HTTP $it" } ?: error.errorCodeName, decision.outcome == FailureOutcome.SKIP)
        }

        when (decision.outcome) {
            FailureOutcome.REPREPARE -> {
                reprepare(player)
            }

            FailureOutcome.SKIP -> {
                skip(player)
            }

            FailureOutcome.EXTEND -> {
                val dead = player.currentMediaItem?.mediaId ?: return
                awaitingExtension = Awaited(dead, player.mediaItemCount - player.currentMediaItemIndex - 1)
                extend(player)
            }

            FailureOutcome.STOP -> {}
        }
    }

    fun onTimelineChanged(player: Player) {
        val awaited = awaitingExtension ?: return
        val ratingKey = awaited.mediaId
        // ExoPlayer reports the timeline before the PLAYLIST_CHANGED
        // transition, and a new queue does not clear the player's error.
        if (player.currentMediaItem?.mediaId != ratingKey) {
            awaitingExtension = null
            return
        }
        if (player.playerError == null) return
        val itemsAfter = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (itemsAfter <= awaited.itemsAfter) return
        awaitingExtension = null
        markSkipped(ratingKey)
        if (player.hasNextMediaItem()) {
            skip(player)
        } else {
            // Shuffle placed every new item earlier in play order.
            player.seekTo(player.currentMediaItemIndex + 1 + awaited.itemsAfter, 0)
            player.prepare()
        }
    }

    fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY) {
            failuresInARow = 0
            awaitingExtension = null
        }
    }

    fun onMediaItemTransition(reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            failuresInARow = 0
            awaitingExtension = null
        }
    }

    private fun skip(player: Player) {
        player.seekToNextMediaItem()
        player.prepare()
    }

    private fun record(
        player: Player,
        reason: String,
        skipped: Boolean,
    ) {
        val item = player.currentMediaItem ?: return
        val metadata = item.mediaMetadata
        log(
            FailedTrack(
                ratingKey = item.mediaId,
                title = metadata.title?.toString(),
                artist = metadata.artist?.toString(),
                album = metadata.albumTitle?.toString(),
                reason = reason,
                skipped = skipped,
                atMillis = now(),
                server = server(),
            ),
        )
    }
}
