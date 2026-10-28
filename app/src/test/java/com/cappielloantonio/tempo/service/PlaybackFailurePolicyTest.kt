package com.cappielloantonio.tempo.service

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailurePolicyTest {
    private fun decide(
        code: Int,
        status: Int? = null,
        before: Int = 0,
        limit: Int = 3,
        hasNext: Boolean = true,
        canExtend: Boolean = false,
    ) = PlaybackFailurePolicy.decide(code, status, before, limit, hasNext, canExtend)

    private val badHttp = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    @Test
    fun `network codes reprepare and leave the count alone`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )) {
            assertEquals(FailureDecision(FailureOutcome.REPREPARE, trackFailure = false, failuresInARow = 2), decide(code, before = 2))
        }
    }

    @Test
    fun `401 and 403 stop and are never track failures`() {
        for (status in listOf(401, 403)) {
            assertFalse(PlaybackFailurePolicy.isTrackFailure(badHttp, status))
            assertEquals(FailureDecision(FailureOutcome.STOP, trackFailure = false, failuresInARow = 0), decide(badHttp, status))
        }
    }

    @Test
    fun `404 and 410 are track failures`() {
        assertTrue(PlaybackFailurePolicy.isTrackFailure(badHttp, 404))
        assertTrue(PlaybackFailurePolicy.isTrackFailure(badHttp, 410))
    }

    @Test
    fun `5xx and other 4xx stop without counting`() {
        for (status in listOf(500, 503, 400, null)) {
            assertEquals(
                FailureDecision(FailureOutcome.STOP, trackFailure = false, failuresInARow = 1),
                decide(badHttp, status, before = 1),
            )
        }
    }

    @Test
    fun `container and decoding errors are track failures`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )) {
            assertTrue("code $code", PlaybackFailurePolicy.isTrackFailure(code, null))
        }
    }

    @Test
    fun `a decoder that cannot start is the head unit's fault, not the track's`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        )) {
            assertFalse("code $code", PlaybackFailurePolicy.isTrackFailure(code, null))
            assertEquals(FailureDecision(FailureOutcome.STOP, trackFailure = false, failuresInARow = 0), decide(code))
        }
    }

    @Test
    fun `unrelated codes stop without counting`() {
        assertEquals(
            FailureDecision(FailureOutcome.STOP, trackFailure = false, failuresInARow = 0),
            decide(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED),
        )
    }

    @Test
    fun `a track failure counts itself, skips below the limit and stops at it`() {
        for (limit in listOf(2, 3, 5)) {
            assertEquals(
                "limit $limit, before ${limit - 2}",
                FailureDecision(FailureOutcome.SKIP, trackFailure = true, failuresInARow = limit - 1),
                decide(badHttp, 404, before = limit - 2, limit = limit),
            )
            assertEquals(
                "limit $limit, before ${limit - 1}",
                FailureDecision(FailureOutcome.STOP, trackFailure = true, failuresInARow = limit),
                decide(badHttp, 404, before = limit - 1, limit = limit),
            )
        }
    }

    @Test
    fun `a track failure with nothing next stops even on the first`() {
        assertEquals(
            FailureDecision(FailureOutcome.STOP, trackFailure = true, failuresInARow = 1),
            decide(badHttp, 404, hasNext = false),
        )
    }

    @Test
    fun `never keeps skipping while there is a next item`() {
        assertEquals(
            FailureDecision(FailureOutcome.SKIP, trackFailure = true, failuresInARow = 10_001),
            decide(badHttp, 404, before = 10_000, limit = PlaybackFailurePolicy.NEVER),
        )
        assertEquals(
            FailureDecision(FailureOutcome.STOP, trackFailure = true, failuresInARow = 10_001),
            decide(badHttp, 404, before = 10_000, limit = PlaybackFailurePolicy.NEVER, hasNext = false),
        )
    }

    @Test
    fun `a track failure with nothing next extends when it can`() {
        assertEquals(
            FailureDecision(FailureOutcome.EXTEND, trackFailure = true, failuresInARow = 1),
            decide(badHttp, 404, hasNext = false, canExtend = true),
        )
    }

    @Test
    fun `the limit-th failure stops even when it could extend`() {
        assertEquals(
            FailureDecision(FailureOutcome.STOP, trackFailure = true, failuresInARow = 3),
            decide(badHttp, 404, before = 2, hasNext = false, canExtend = true),
        )
    }

    @Test
    fun `a next item skips rather than extends`() {
        assertEquals(
            FailureDecision(FailureOutcome.SKIP, trackFailure = true, failuresInARow = 1),
            decide(badHttp, 404, hasNext = true, canExtend = true),
        )
    }

    @Test
    fun `a failure that is not the track's never extends`() {
        assertEquals(
            FailureDecision(FailureOutcome.STOP, trackFailure = false, failuresInARow = 0),
            decide(badHttp, 401, hasNext = false, canExtend = true),
        )
    }
}
