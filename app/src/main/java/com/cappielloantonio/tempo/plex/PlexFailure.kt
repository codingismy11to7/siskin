package com.cappielloantonio.tempo.plex

/**
 * Which side of the API a call was talking to.
 *
 * This is the distinction the two Retrofit instances already draw -- plex.tv is
 * fixed and usable signed out, a media server's address is discovered and can
 * change -- lifted into the error channel. Without it, every consumer has to
 * reconstruct "could not reach plex.tv" versus "could not reach that Plex
 * server" from where it happens to sit in the flow.
 */
enum class PlexHost { PlexTv, Server }

/**
 * What the Plex API did wrong.
 *
 * Deliberately free of Android and of `R` -- this is what the API reports, not
 * what the user is shown. Translating one of these into a message belongs to
 * whoever has a screen; see PlexSignInFlow.messageFor.
 */
sealed interface PlexFailure {

    val host: PlexHost

    /** The request never got an HTTP response: no route, refused, timed out. */
    data class Unreachable(override val host: PlexHost) : PlexFailure

    /**
     * A non-2xx response.
     *
     * [code] is kept because 401 and 403 mean the token stopped being accepted
     * and should drive re-authentication, where every other status should not.
     * Collapsing them loses the only distinction consumers act on.
     */
    data class Http(override val host: PlexHost, val code: Int) : PlexFailure

    /**
     * plex.tv answered 2xx with a PIN carrying no id or no code.
     *
     * Only AuthClient.createPin produces this, which is fine: it states what
     * plex.tv did wrong, and a sealed hierarchy should be honest rather than
     * minimal.
     */
    data object NoPinCode : PlexFailure {
        override val host: PlexHost = PlexHost.PlexTv
    }
}
