package com.cappielloantonio.tempo.service

import androidx.media3.common.PlaybackException

enum class FailureOutcome { REPREPARE, SKIP, STOP }

/** [failuresInARow] is the count after this error: one more for a track failure, unchanged otherwise. */
data class FailureDecision(
    val outcome: FailureOutcome,
    val trackFailure: Boolean,
    val failuresInARow: Int,
)

/**
 * What a playback error means for the queue. See
 * docs/decisions/2026-10-27-skip-dead-tracks-design.md.
 *
 * Of the HTTP statuses, only 404 and 410 count as track failures, so an
 * expired credential (401/403) can never be skipped past: that would burn the
 * queue while signing in again is what is needed. A decoder that will not
 * start or answer is the head unit's problem rather than the file's, so those
 * codes are not track failures either.
 */
object PlaybackFailurePolicy {
    const val NEVER: Int = Int.MAX_VALUE

    private val TRACK_FAILURE_CODES =
        setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )

    fun isTrackFailure(
        errorCode: Int,
        httpStatus: Int?,
    ): Boolean =
        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus == 404 || httpStatus == 410
            else -> errorCode in TRACK_FAILURE_CODES
        }

    /** [failuresBefore] is the count of track failures in a row before this error. */
    fun decide(
        errorCode: Int,
        httpStatus: Int?,
        failuresBefore: Int,
        limit: Int,
        hasNext: Boolean,
    ): FailureDecision {
        if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        ) {
            return FailureDecision(FailureOutcome.REPREPARE, trackFailure = false, failuresInARow = failuresBefore)
        }
        if (!isTrackFailure(errorCode, httpStatus)) {
            return FailureDecision(FailureOutcome.STOP, trackFailure = false, failuresInARow = failuresBefore)
        }
        val count = failuresBefore + 1
        val outcome = if (hasNext && count < limit) FailureOutcome.SKIP else FailureOutcome.STOP
        return FailureDecision(outcome, trackFailure = true, failuresInARow = count)
    }
}
