package com.cappielloantonio.tempo.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Carries out [PlaybackFailurePolicy]'s decision on the player and keeps the
 * count of track failures in a row. A stop does nothing to the player: it stays
 * in its error state (#270 covers the wording).
 */
@UnstableApi
class PlaybackFailureHandler(
    private val limit: () -> Int,
    private val log: (FailedTrack) -> Unit,
    private val server: () -> String?,
    private val reprepare: (Player) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var failuresInARow = 0

    fun onPlayerError(
        player: Player,
        error: PlaybackException,
    ) {
        val httpStatus = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val decision =
            PlaybackFailurePolicy.decide(
                errorCode = error.errorCode,
                httpStatus = httpStatus,
                failuresBefore = failuresInARow,
                limit = limit(),
                hasNext = player.hasNextMediaItem(),
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
                player.seekToNextMediaItem()
                player.prepare()
            }

            FailureOutcome.STOP -> {}
        }
    }

    fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY) failuresInARow = 0
    }

    fun onMediaItemTransition(reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) failuresInARow = 0
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
