package com.cappielloantonio.tempo.plex.auth

import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInErrorTest {
    @Test
    fun aPlexTvTransportFailureReadsAsAPlexTvProblem() {
        assertEquals(
            R.string.plex_sign_in_error_network,
            PlexSignInFlow.messageFor(SignInError.Api(PlexTransportFailure.Unreachable(PlexHost.PlexTv))),
        )
        assertEquals(
            R.string.plex_sign_in_error_network,
            PlexSignInFlow.messageFor(SignInError.Api(PlexTransportFailure.Http(PlexHost.PlexTv, 500))),
        )
    }

    @Test
    fun aServerTransportFailureReadsAsAServerProblem() {
        // The distinction the host exists to carry: an identical failure means
        // something different depending on which side did not answer.
        assertEquals(
            R.string.plex_sign_in_error_server_unreachable,
            PlexSignInFlow.messageFor(SignInError.Api(PlexTransportFailure.Unreachable(PlexHost.Server))),
        )
        assertEquals(
            R.string.plex_sign_in_error_server_unreachable,
            PlexSignInFlow.messageFor(SignInError.Api(PlexTransportFailure.Http(PlexHost.Server, 401))),
        )
    }

    @Test
    fun aMissingPinCodeReadsAsAPinProblemRatherThanANetworkOne() {
        // plex.tv answered; it just did not send what we asked for. NoPinCode is
        // its own SignInError case now, not an Api(PlexTransportFailure) --
        // creating a PIN is the one call with a failure of its own.
        assertEquals(
            R.string.plex_sign_in_error_pin,
            PlexSignInFlow.messageFor(SignInError.NoPinCode),
        )
    }

    @Test
    fun theSignInSpecificFailuresKeepTheirOwnMessages() {
        assertEquals(
            R.string.plex_sign_in_error_expired,
            PlexSignInFlow.messageFor(SignInError.PinExpired),
        )
        assertEquals(
            R.string.plex_sign_in_error_no_servers,
            PlexSignInFlow.messageFor(SignInError.NoServers),
        )
        assertEquals(
            R.string.plex_sign_in_error_no_libraries,
            PlexSignInFlow.messageFor(SignInError.NoLibraries),
        )
        assertEquals(
            R.string.plex_sign_in_error_lost_candidate,
            PlexSignInFlow.messageFor(SignInError.NoCandidate),
        )
    }

    @Test
    fun everyErrorMapsToARealAndDistinctString() {
        // Guards the failure the exhaustive `when` cannot: two cases pointing at
        // the same resource, or at 0.
        val errors =
            listOf(
                SignInError.Api(PlexTransportFailure.Unreachable(PlexHost.PlexTv)),
                SignInError.Api(PlexTransportFailure.Unreachable(PlexHost.Server)),
                SignInError.NoPinCode,
                SignInError.PinExpired,
                SignInError.NoServers,
                SignInError.NoLibraries,
                SignInError.NoCandidate,
            )

        val messages = errors.map { PlexSignInFlow.messageFor(it) }

        messages.forEach { assertNotEquals(0, it) }
        assertEquals(
            "every SignInError should reach a distinct string",
            errors.size,
            messages.toSet().size,
        )
        assertTrue(messages.size == 7)
    }
}
