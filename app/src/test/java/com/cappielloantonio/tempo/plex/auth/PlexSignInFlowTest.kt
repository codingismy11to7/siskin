package com.cappielloantonio.tempo.plex.auth

import com.cappielloantonio.tempo.plex.api.auth.CreatedPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexSignInFlowTest {

    private fun createdPin(
        id: Long = 1L,
        code: String = "ABCD",
        qrUrl: String? = null,
        expiresAtEpochSeconds: Long? = null
    ) = CreatedPin(id, code, qrUrl, expiresAtEpochSeconds)

    @Test
    fun awaitsApprovalOnceAPinExists() {
        val awaiting = PlexSignInFlow.afterPinCreated(
            createdPin(
                id = 1L,
                code = "ABCD",
                qrUrl = "https://plex.tv/qr",
                expiresAtEpochSeconds = 1785153600L
            )
        )
        assertEquals("ABCD", awaiting.code)
        assertEquals("https://plex.tv/qr", awaiting.qrUrl)
        assertEquals(1785153600L, awaiting.expiresAtEpochSeconds)
    }

    @Test
    fun survivesAPinWithNoQrUrl() {
        // The code-plus-instructions text carries the screen on its own, so a
        // missing qr is not a failure -- the screen just hides the image.
        val awaiting = PlexSignInFlow.afterPinCreated(createdPin(qrUrl = null))
        assertNull(awaiting.qrUrl)
        assertNull(awaiting.expiresAtEpochSeconds)
    }
}
