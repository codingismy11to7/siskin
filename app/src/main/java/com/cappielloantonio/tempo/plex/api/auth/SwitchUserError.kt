package com.cappielloantonio.tempo.plex.api.auth

import com.cappielloantonio.tempo.plex.PlexTransportFailure

/** How switching to a Plex Home member can fail. */
sealed interface SwitchUserError {
    /** 403 with plex.tv error code 1041. */
    data object WrongPin : SwitchUserError

    /** 429. Plex limits switches to roughly ten in a couple of minutes. */
    data object RateLimited : SwitchUserError

    data class Transport(
        val failure: PlexTransportFailure,
    ) : SwitchUserError
}
