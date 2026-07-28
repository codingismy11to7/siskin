package com.cappielloantonio.tempo.plex.auth

import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource

/**
 * Every "what happens next" decision in the sign-in flow, as pure functions over
 * API results. PlexSignInViewModel does the I/O and the scheduling; it makes no
 * decisions of its own, which is what keeps the flow testable.
 */
object PlexSignInFlow {

    fun afterPinCreated(pin: Pin?): PlexSignInState {
        val code = pin?.code
        if (pin?.id == null || code.isNullOrBlank()) {
            return PlexSignInState.Failed(R.string.plex_sign_in_error_pin)
        }

        return PlexSignInState.AwaitingApproval(
            code = code,
            qrUrl = pin.qr?.takeIf { it.isNotBlank() },
            expiresAtEpochSeconds = AuthClient.expiresAtEpochSeconds(pin)
        )
    }

    /**
     * Returns [current] unchanged while the pin is pending, so a poll that
     * changes nothing does not re-emit and make the screen reload its QR image.
     */
    fun afterPinPoll(pinState: PlexPinState, current: PlexSignInState): PlexSignInState =
        when (pinState) {
            is PlexPinState.Authorized -> PlexSignInState.Working
            PlexPinState.Expired -> PlexSignInState.Failed(R.string.plex_sign_in_error_expired)
            PlexPinState.Pending -> current
        }

    fun afterResources(resources: List<Resource>?): PlexSignInState {
        val servers = AuthClient.mediaServers(resources)
        return if (servers.isEmpty()) {
            PlexSignInState.Failed(R.string.plex_sign_in_error_no_servers)
        } else {
            PlexSignInState.ChoosingServer(servers)
        }
    }

    fun afterSections(response: PlexResponse?): PlexSignInState {
        val sections = LibraryClient.musicSections(response)
        return if (sections.isEmpty()) {
            PlexSignInState.Failed(R.string.plex_sign_in_error_no_libraries)
        } else {
            PlexSignInState.ChoosingLibrary(sections)
        }
    }
}
