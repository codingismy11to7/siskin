package com.cappielloantonio.tempo.plex.auth

import androidx.annotation.StringRes
import arrow.core.NonEmptyList
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Resource

/**
 * What the sign-in screen is showing.
 *
 * Holds data only -- these types carry no behaviour of their own, so a state
 * can be constructed and compared without a network or an Android framework
 * class. Most transitions are built directly at the call site in
 * [com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel]; [PlexSignInFlow]
 * keeps the one that still needs a function, [PlexSignInFlow.afterPinCreated].
 */
sealed interface PlexSignInState {

    /**
     * Nothing has been attempted yet. The screen shows the invitation and a
     * Connect button, and no network call has been made.
     *
     * This is the initial state rather than [Working] because the settings gear
     * can open this screen. Someone who opened settings to look should not have
     * silently started an authentication attempt by arriving.
     */
    data object Disconnected : PlexSignInState

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
     * step. Null on the way *in* to the picker, and also null when the user
     * backs into it from [ChoosingLibrary]: that is the user correcting their
     * own pick, not Plex saying no to anything, so there is nothing to report.
     *
     * Carries nothing about where the user came from, deliberately. The debug
     * screen reaches this picker too, and back from it has to return there
     * rather than abandon a sign-in nobody started -- but that is the fragment
     * back stack's job, not this type's. See
     * [com.cappielloantonio.tempo.ui.fragment.CarDebugFragment]'s `chooseServer`.
     */
    data class ChoosingServer(
        val servers: NonEmptyList<Resource>,
        @param:StringRes val messageRes: Int? = null
    ) : PlexSignInState

    /**
     * Non-empty by construction: an empty picker is [SignInError.NoLibraries].
     *
     * Carries [servers] -- the same list [ChoosingServer] showed on the way in
     * -- so that pressing back can return to the server picker with it intact
     * instead of an empty screen. This is *not* a second owner of the list:
     * [PlexSignInViewModel.chooseServer] still reads it out of the
     * [ChoosingServer] state exactly once, before overwriting that state, and
     * simply carries the same value forward into this one rather than caching
     * it anywhere else. See the comment on that read for why a parallel field
     * is the thing being avoided (issue #18).
     */
    data class ChoosingLibrary(
        val sections: NonEmptyList<Directory>,
        val servers: NonEmptyList<Resource>
    ) : PlexSignInState

    data class Failed(@param:StringRes val messageRes: Int) : PlexSignInState

    data object Done : PlexSignInState

    /** Signed in. The settings screen. */
    data object Connected : PlexSignInState
}
