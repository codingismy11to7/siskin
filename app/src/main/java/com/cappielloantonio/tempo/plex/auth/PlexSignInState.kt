package com.cappielloantonio.tempo.plex.auth

import androidx.annotation.StringRes
import arrow.core.NonEmptyList
import arrow.core.toNonEmptyListOrNull
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.HomeUser
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
     * This profile may not add accounts, so sign-in is impossible rather than
     * merely likely to fail. A car is a shared device and an OEM can set
     * DISALLOW_MODIFY_ACCOUNTS on a guest profile; without this state the PIN
     * flow would run to a dead end that reads as Plex being unreachable.
     */
    data object SignInNotAllowed : PlexSignInState

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
        val expiresAtEpochSeconds: Long?,
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
     * [com.cappielloantonio.tempo.ui.fragment.DebugServerFragment]'s `chooseServer`.
     *
     * [account] is who a commit from here signs in as. Nothing has been
     * written for it yet; see [PendingAccount].
     *
     * [listener] draws the Switch user row; null when there is no Home of two
     * or more to show. See the 2026-11-14 picker design.
     */
    data class ChoosingServer(
        val servers: NonEmptyList<Resource>,
        val account: PendingAccount,
        @param:StringRes val messageRes: Int? = null,
        val listener: HomeListener? = null,
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
     *
     * [account] is carried forward the same way, and is the account token the
     * commit from here writes.
     *
     * [listener] is carried for the same reason as [servers]: back restores the
     * picker whole.
     */
    data class ChoosingLibrary(
        val sections: NonEmptyList<Directory>,
        val servers: NonEmptyList<Resource>,
        val account: PendingAccount,
        val listener: HomeListener? = null,
    ) : PlexSignInState

    /**
     * Who is listening, for a Home with more than one member. Non-empty by
     * construction; a one-member Home never shows this.
     *
     * [currentUuid] is set when opened from settings or the server picker's
     * row, where it marks the active member and tapping it does nothing. At
     * sign-in it is null and every member, the signed-in one included, is a
     * switch.
     *
     * [account] is the token a switch is made *from*: the one that approved
     * the PIN at sign-in, the stored one from settings.
     *
     * [returnTo] is set when opened from the server picker's row, and back
     * returns there.
     *
     * [keepsSaved] lets a switch from here keep the saved server when the new
     * member can still see it. Its own flag rather than inferred from
     * [returnTo]: the two coincide today, and a later entry point that wants
     * one without the other should not inherit both.
     *
     * [savedUuid] is the member whose session is stored, when known. Choosing
     * them returns to their own servers with the stored token rather than
     * spending a switch.
     */
    data class ChoosingUser(
        val users: NonEmptyList<HomeUser>,
        val account: PendingAccount,
        val currentUuid: String? = null,
        @param:StringRes val messageRes: Int? = null,
        val returnTo: ChoosingServer? = null,
        val keepsSaved: Boolean = false,
        val savedUuid: String? = null,
    ) : PlexSignInState

    /**
     * A protected member's PIN.
     *
     * Carries the [picker] it was opened from, whole, so back returns to it and
     * a switch from here keeps every choice the picker made.
     */
    data class EnteringPin(
        val user: HomeUser,
        val picker: ChoosingUser,
        @param:StringRes val messageRes: Int? = null,
    ) : PlexSignInState

    data class Failed(
        @param:StringRes val messageRes: Int,
    ) : PlexSignInState

    data object Done : PlexSignInState

    /** Signed in. The settings screen. */
    data object Connected : PlexSignInState
}

/**
 * Who is listening, for the server picker's Switch user row. Exists only for a
 * Home of two or more with the listener among its members: anything less is
 * no one to switch to, or a list that cannot mark who is current.
 *
 * [savedUuid] is the member whose session is stored, when known; see
 * [PlexSignInState.ChoosingUser.savedUuid].
 */
data class HomeListener(
    val users: NonEmptyList<HomeUser>,
    val currentUuid: String,
    val savedUuid: String? = null,
) {
    val name: String get() = users.firstOrNull { it.uuid == currentUuid }?.title.orEmpty()

    companion object {
        /** Whether a Home has anyone to switch to: the one count rule, so no two places can disagree. */
        fun hasOthers(users: List<HomeUser>): Boolean = users.size >= 2

        fun of(
            users: List<HomeUser>,
            currentUuid: String?,
            savedUuid: String? = null,
        ): HomeListener? {
            val home = users.toNonEmptyListOrNull() ?: return null
            if (!hasOthers(home) || currentUuid == null || home.none { it.uuid == currentUuid }) return null
            return HomeListener(home, currentUuid, savedUuid)
        }
    }
}
