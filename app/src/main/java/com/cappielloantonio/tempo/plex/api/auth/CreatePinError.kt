package com.cappielloantonio.tempo.plex.api.auth

import com.cappielloantonio.tempo.plex.PlexTransportFailure

/**
 * How creating a sign-in PIN can fail. The only Plex operation with a
 * failure mode of its own -- every other call fails only in transport.
 */
sealed interface CreatePinError {
    data class Transport(
        val failure: PlexTransportFailure,
    ) : CreatePinError

    /** plex.tv answered 2xx with a PIN carrying no id or no code. */
    data object NoPinCode : CreatePinError
}
