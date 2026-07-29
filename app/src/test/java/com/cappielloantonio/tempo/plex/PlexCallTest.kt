package com.cappielloantonio.tempo.plex

import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class PlexCallTest {

    private fun httpException(code: Int) = HttpException(
        Response.error<String>(code, "".toResponseBody("text/plain".toMediaTypeOrNull()))
    )

    @Test
    fun wrapsASuccessfulCallInRight() = runTest {
        val result = plexCall(PlexHost.Server) { "body" }

        assertEquals(Either.Right("body"), result)
    }

    @Test
    fun mapsAnIOExceptionToUnreachableCarryingTheHost() = runTest {
        val result = plexCall(PlexHost.Server) { throw IOException("no route") }

        assertEquals(Either.Left(PlexFailure.Unreachable(PlexHost.Server)), result)
    }

    @Test
    fun mapsAnHttpExceptionToHttpCarryingTheHostAndCode() = runTest {
        val result = plexCall(PlexHost.PlexTv) { throw httpException(401) }

        assertEquals(Either.Left(PlexFailure.Http(PlexHost.PlexTv, 401)), result)
    }

    @Test
    fun keepsTheHostsDistinctSoTheSameFailureReadsDifferently() = runTest {
        // The whole point of carrying the host: an identical transport failure
        // means "could not reach plex.tv" on one client and "could not reach
        // that Plex server" on another.
        val plexTv = plexCall(PlexHost.PlexTv) { throw IOException() }
        val server = plexCall(PlexHost.Server) { throw IOException() }

        assertTrue(plexTv != server)
    }

    @Test
    fun letsCancellationEscapeRatherThanTurningItIntoAFailure() = runTest {
        // A cancelled call has no result to report. Capturing it as a Left would
        // let a cancelled sign-in publish a Failed state over a finished one.
        try {
            plexCall(PlexHost.Server) { throw CancellationException("cancelled") }
            fail("expected the CancellationException to propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun letsAnUnexpectedThrowableEscape() = runTest {
        // plexCall models transport failure only. A bug in mapping code must not
        // be reported to the user as an unreachable server.
        try {
            plexCall(PlexHost.Server) { throw IllegalArgumentException("bug") }
            fail("expected the IllegalArgumentException to propagate")
        } catch (expected: IllegalArgumentException) {
            assertEquals("bug", expected.message)
        }
    }

    @Test
    fun noPinCodeIsAlwaysAPlexTvFailure() {
        assertEquals(PlexHost.PlexTv, PlexFailure.NoPinCode.host)
    }
}
