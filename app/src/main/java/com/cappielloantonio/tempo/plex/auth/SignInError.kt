package com.cappielloantonio.tempo.plex.auth

import com.cappielloantonio.tempo.plex.PlexFailure

/**
 * Everything that can end a sign-in.
 *
 * Separate from [PlexFailure] because "the API did not give us what we asked
 * for" and "this account has no media servers" are different kinds of
 * statement, and because folding them together would drag flow concerns into
 * `plex/`, which is Android-free.
 *
 * There is no `ServerUnreachable` case: that is
 * `Api(PlexFailure.Unreachable(PlexHost.Server))`, and the host already says it.
 */
sealed interface SignInError {

    /** The API failed. [PlexFailure.host] decides how it reads to the user. */
    data class Api(val failure: PlexFailure) : SignInError

    /** The PIN ran out before anyone approved it. */
    data object PinExpired : SignInError

    /** The account is fine; it just has no media servers this app could use. */
    data object NoServers : SignInError

    /** The server is fine; it just has no music libraries. */
    data object NoLibraries : SignInError
}
