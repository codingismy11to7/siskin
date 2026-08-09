package com.cappielloantonio.tempo.plex

/**
 * One signed-in connection: which account, which server, which music library.
 *
 * These five values describe a single connection and are only meaningful
 * together. Held as separate nullable fields they could be read as a *mixed*
 * set -- a section key from one server beside another server's address -- which
 * would report as signed in and then ask one server for the other's section.
 * Constructing this type is all-or-nothing, and [PlexApi.session] persists it
 * the same way, so no reader can observe [serverUri], [musicSectionKey] and
 * [serverToken] out of step with one another -- those three never describe
 * *different servers*.
 *
 * That is the invariant, and it is subtly weaker than "these three never
 * change independently". [serverUri] alone does move, when ServerAddressBook
 * re-probes: an address that stopped answering is replaced while
 * [machineIdentifier] stays put. That is safe precisely because
 * [machineIdentifier] is what says *which* server -- the same server at a new
 * address serves the same sections and accepts the same token. A change that
 * moved [serverUri] to a *different* machine without moving the section key
 * with it is the hazard this type exists to prevent, and still is.
 *
 * [accountToken] is the one exception: `PlexSignInViewModel.signIn()` writes
 * it on its own, mid-flow, before a session exists at all. Re-signing in while
 * already signed in can therefore leave a window where a reader sees the *new*
 * account token beside the *old* server's URI and section key. Harmless for
 * the same account today, but it means this type does not guarantee
 * [accountToken] is in step with the other three the way they are with each
 * other.
 */
data class PlexSession(
    val accountToken: String,
    val serverUri: String,
    val musicSectionKey: SectionKey,
    /**
     * Null for a server the account owns -- those accept the account token. A
     * *shared* server does not, which is why this is a separate field rather
     * than an overwrite of [accountToken]: plex.tv still needs the account one
     * afterwards.
     */
    val serverToken: String?,
    /**
     * Stable per server, from Resource.clientIdentifier. Null for sessions
     * written before this field existed, and for that reason every reader must
     * tolerate its absence rather than treat it as a different server.
     */
    val machineIdentifier: String? = null
) {
    companion object {

        /**
         * Builds a session from stored values, or null if any required one is
         * absent. [serverToken] is not required; see its KDoc.
         */
        @JvmStatic
        fun from(
            accountToken: String?,
            serverUri: String?,
            musicSectionKey: String?,
            serverToken: String?,
            machineIdentifier: String? = null
        ): PlexSession? {
            if (accountToken.isNullOrBlank()) return null
            if (serverUri.isNullOrBlank()) return null
            if (musicSectionKey.isNullOrBlank()) return null
            return PlexSession(
                accountToken,
                serverUri,
                SectionKey(musicSectionKey),
                serverToken,
                machineIdentifier
            )
        }
    }
}
