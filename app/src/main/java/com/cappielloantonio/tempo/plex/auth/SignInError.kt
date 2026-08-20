package com.cappielloantonio.tempo.plex.auth

import com.cappielloantonio.tempo.plex.PlexTransportFailure

/**
 * Everything that can end a sign-in.
 *
 * Separate from [PlexTransportFailure] because "the API did not give us what
 * we asked for" and "this account has no media servers" are different kinds
 * of statement, and because folding them together would drag flow concerns
 * into `plex/`, which is Android-free.
 *
 * There is no `ServerUnreachable` case: that is
 * `Api(PlexTransportFailure.Unreachable(PlexHost.Server))`, and the host
 * already says it.
 */
sealed interface SignInError {
    /** The API failed. [PlexTransportFailure.host] decides how it reads to the user. */
    data class Api(
        val failure: PlexTransportFailure,
    ) : SignInError

    /**
     * plex.tv answered when creating a PIN, but not with a usable one. A
     * sign-in outcome in its own right, not a transport one -- the request
     * succeeded; it just did not give createPin what it needed.
     */
    data object NoPinCode : SignInError

    /** The PIN ran out before anyone approved it. */
    data object PinExpired : SignInError

    /** The account is fine; it just has no media servers this app could use. */
    data object NoServers : SignInError

    /** The server is fine; it just has no music libraries. */
    data object NoLibraries : SignInError

    /**
     * The library picker was answered with no candidate server on record --
     * normally unreachable through the UI, since the picker only appears after
     * a server has been chosen, but a silent no-op here would otherwise leave
     * the screen stuck rather than telling the user to start over.
     */
    data object NoCandidate : SignInError
}
