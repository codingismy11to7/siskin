package com.cappielloantonio.tempo.plex.auth

import androidx.annotation.StringRes
import arrow.core.NonEmptyList
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

    /**
     * Non-empty by construction: an empty picker is [SignInError.NoServers].
     *
     * [messageRes] is set when the user arrives here by rejection rather than
     * by progress -- the server they picked had no music library, or did not
     * answer. That is a statement about that server, not about the account, so
     * the account token is still good and this list is still the right next
     * step. Null on the way *in* to the picker.
     */
    data class ChoosingServer(
        val servers: NonEmptyList<Resource>,
        @param:StringRes val messageRes: Int? = null
    ) : PlexSignInState

    /** Non-empty by construction: an empty picker is [SignInError.NoLibraries]. */
    data class ChoosingLibrary(val sections: NonEmptyList<Directory>) : PlexSignInState

    data class Failed(@param:StringRes val messageRes: Int) : PlexSignInState

    data object Done : PlexSignInState
}
