package com.cappielloantonio.tempo.plex.auth

import androidx.annotation.StringRes
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexFailure
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.api.auth.CreatedPin

/**
 * What the user is told, and the one transition that still needs a function.
 *
 * Smaller than it was. The emptiness checks this object used to perform --
 * afterResources, afterSections -- are now `ensureNotNull` preconditions at the
 * call site, where they read as requirements rather than as state-returning
 * helpers, and expiry is a `raise` rather than a mapped state. What survives is
 * pure and testable without a network, which is why the object still exists.
 */
object PlexSignInFlow {

    /**
     * Cannot fail: [CreatedPin] is validated at the client, so a code is
     * guaranteed present. The check this used to perform, and the "only returns
     * AwaitingApproval for a pin with an id" comment that explained it, are gone.
     */
    fun afterPinCreated(created: CreatedPin): PlexSignInState.AwaitingApproval =
        PlexSignInState.AwaitingApproval(
            code = created.code,
            qrUrl = created.qrUrl,
            expiresAtEpochSeconds = created.expiresAtEpochSeconds
        )

    /**
     * The single place a failure becomes a message.
     *
     * Exhaustive with no `else`, so a new [SignInError] or [PlexFailure] case
     * will not compile until it has a string. That is the point: these used to be
     * six scattered `Failed(R.string...)` calls across two files.
     */
    @StringRes
    fun messageFor(error: SignInError): Int = when (error) {
        is SignInError.Api -> messageForApi(error.failure)
        SignInError.PinExpired -> R.string.plex_sign_in_error_expired
        SignInError.NoServers -> R.string.plex_sign_in_error_no_servers
        SignInError.NoLibraries -> R.string.plex_sign_in_error_no_libraries
        SignInError.NoCandidate -> R.string.plex_sign_in_error_lost_candidate
    }

    /**
     * A transport failure reads as whichever side failed to answer.
     *
     * This preserves a deliberate behaviour: a dropped connection and a 401 from
     * the server both read as "could not reach that Plex server". It falls out of
     * the host now rather than being asserted at each call site.
     */
    @StringRes
    private fun messageForApi(failure: PlexFailure): Int = when (failure) {
        PlexFailure.NoPinCode -> R.string.plex_sign_in_error_pin
        is PlexFailure.Unreachable, is PlexFailure.Http -> when (failure.host) {
            PlexHost.PlexTv -> R.string.plex_sign_in_error_network
            PlexHost.Server -> R.string.plex_sign_in_error_server_unreachable
        }
    }
}
