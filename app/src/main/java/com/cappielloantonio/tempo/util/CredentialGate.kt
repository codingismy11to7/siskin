package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.PlexApi

/**
 * The single definition of "Siskin is signed in".
 *
 * The AAOS browse gate needs this answer the instant the car asks for a library,
 * and two copies of the rule would drift apart.
 *
 * A PlexSession exists only when every value it needs is present, and it is
 * persisted as a unit, so there is no partial set left for this to guess about.
 * The twenty lines of comment that used to explain the mixed-set hazard are gone
 * with the hazard.
 */
object CredentialGate {

    @JvmStatic
    fun isSignedIn(): Boolean = PlexApi().session != null
}
