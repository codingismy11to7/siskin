package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.PlexApi

/**
 * The single definition of "Siskin is signed in".
 *
 * The AAOS browse gate needs this answer the instant the car asks for a library,
 * and two copies of the rule would drift apart.
 *
 * A PlexSession exists only when every value it needs is present. Its three
 * server-scoped fields -- serverUri, musicSectionKey, serverToken -- no
 * longer share a store: the first two are persisted together in preferences,
 * and serverToken lives in the system account, filed under a type that names
 * the server it belongs to. A lookup against the wrong server yields null
 * rather than that server's token, so there is still no partial set of
 * *those* for this to guess about on the write path. The twenty lines of
 * comment that used to explain that mixed-set hazard are gone with it.
 *
 * The account token is a narrower exception, not a hole in this: see
 * PlexSession's KDoc for the mid-flow re-sign-in window where it can briefly
 * outrun the other three. It does not change the answer here, because a
 * session with a stale account token beside a fine server is still a session
 * -- isSignedIn is not the check that would ever need to tell the difference.
 */
object CredentialGate {

    @JvmStatic
    fun isSignedIn(): Boolean = PlexApi().session != null
}
