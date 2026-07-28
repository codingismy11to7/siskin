package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.PlexApi

/**
 * The single definition of "Siskin is signed in".
 *
 * The AAOS browse gate needs this answer the instant the car asks for a library,
 * and two copies of the rule would drift apart.
 *
 * Deliberately does *not* require a server token. Resource.accessToken is null
 * for a server the account owns -- those accept the account token -- so requiring
 * it would read as signed-out forever. PlexApi.serverHeaders() supplies the
 * fallback instead.
 */
object CredentialGate {

    @JvmStatic
    fun isSignedIn(
        accountToken: String?,
        serverUri: String?,
        musicSectionKey: String?
    ): Boolean =
        !accountToken.isNullOrBlank() &&
            !serverUri.isNullOrBlank() &&
            !musicSectionKey.isNullOrBlank()

    @JvmStatic
    fun isSignedIn(): Boolean = PlexApi().let {
        isSignedIn(it.accountToken, it.serverUri, it.musicSectionKey)
    }
}
