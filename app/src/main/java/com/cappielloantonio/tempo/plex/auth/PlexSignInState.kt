package com.cappielloantonio.tempo.plex.auth

import androidx.annotation.StringRes
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Resource

/**
 * What the sign-in screen is showing.
 *
 * Holds data only -- every transition into one of these lives in [PlexSignInFlow],
 * so the flow can be tested without a network or an Android framework class.
 */
sealed interface PlexSignInState {

    /**
     * Creating the pin, or discovering servers or libraries. One state rather
     * than three because all three render the same spinner.
     */
    data object Working : PlexSignInState

    /** The QR code and short code are up; the poll loop is running. */
    data class AwaitingApproval(
        val code: String,
        /** Null when Plex omitted it; the screen then shows the code alone. */
        val qrUrl: String?,
        val expiresAtEpochSeconds: Long?
    ) : PlexSignInState

    data class ChoosingServer(val servers: List<Resource>) : PlexSignInState

    data class ChoosingLibrary(val sections: List<Directory>) : PlexSignInState

    data class Failed(@StringRes val messageRes: Int) : PlexSignInState

    data object Done : PlexSignInState
}
