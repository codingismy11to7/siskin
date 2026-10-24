package com.cappielloantonio.tempo.plex

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.toNonEmptyListOrNull
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.SwitchUserError
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.auth.PendingAccount
import com.cappielloantonio.tempo.plex.auth.SignInError
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.plex.models.refusesAccountToken

/** Where a switch leaves the user. Nothing in it has been persisted. */
sealed interface SwitchOutcome {
    val account: PendingAccount

    /** The saved server and library are both visible to the new user. */
    data class Committable(
        override val account: PendingAccount,
        val serverUri: String,
        val resource: Resource,
        val sectionKey: SectionKey,
    ) : SwitchOutcome

    /** The saved server is visible, its library is not. */
    data class NeedsLibrary(
        override val account: PendingAccount,
        val serverUri: String,
        val resource: Resource,
        val sections: NonEmptyList<Directory>,
        val servers: NonEmptyList<Resource>,
    ) : SwitchOutcome

    /**
     * Nothing saved, the saved server is not the new user's, or it could not
     * be confirmed for them. The server picker re-probes whatever is chosen.
     */
    data class NeedsServer(
        override val account: PendingAccount,
        val servers: NonEmptyList<Resource>,
    ) : SwitchOutcome
}

/**
 * Becomes another member of the Plex Home: switch, list that member's
 * servers with their new token, and check what of the saved session they can
 * still see. Persists nothing -- the caller commits through the sign-in
 * flow's one session write.
 *
 * The new member's plex.tv token is refused by a server they do not own, so
 * every server call here uses the per-server token `/resources` hands back.
 * See the 2026-10-24 Home user switching design for the measurements.
 *
 * Only the switch and `/resources` can fail it. Anything wrong with the saved
 * server lands on [SwitchOutcome.NeedsServer] instead: the minted token has
 * already spent one of Plex's few allowed switches, and the saved address is
 * often a home LAN one the car cannot reach on the road.
 */
class HomeUserSwitch(
    private val authClientFor: (String) -> AuthClient,
    private val sectionsFor: suspend (
        serverUri: String,
        serverToken: String?,
        accountToken: String,
    ) -> Either<PlexTransportFailure, PlexResponse>,
) {
    constructor(api: PlexApi) : this(
        { token -> AuthClient(api, token) },
        { uri, serverToken, accountToken -> LibraryClient(api, uri, serverToken) { accountToken }.getSections() },
    )

    /** [from] is the token doing the switching, which need not be the stored one. */
    suspend fun switch(
        from: PendingAccount,
        user: HomeUser,
        pin: String?,
        saved: PlexSession?,
    ): Either<SignInError, SwitchOutcome> =
        either {
            val uuid = ensureNotNull(user.uuid) { SignInError.NoCandidate }
            val switched =
                authClientFor(from.token)
                    .switchTo(uuid, pin)
                    .mapLeft {
                        when (it) {
                            SwitchUserError.WrongPin -> SignInError.WrongPin
                            SwitchUserError.RateLimited -> SignInError.RateLimited
                            is SwitchUserError.Transport -> SignInError.Api(it.failure)
                        }
                    }.bind()
            val account = PendingAccount(ensureNotNull(switched.authToken) { SignInError.NoCandidate })

            val resources = authClientFor(account.token).getResources().mapLeft(SignInError::Api).bind()
            val servers =
                ensureNotNull(AuthClient.mediaServers(resources).toNonEmptyListOrNull()) { SignInError.NoServers }

            keepSaved(account, servers, saved) ?: SwitchOutcome.NeedsServer(account, servers)
        }

    /** Null whenever the saved server cannot be confirmed for [account]. */
    private suspend fun keepSaved(
        account: PendingAccount,
        servers: NonEmptyList<Resource>,
        saved: PlexSession?,
    ): SwitchOutcome? {
        val session = saved ?: return null
        val machine = session.machineIdentifier?.takeIf { it.isNotBlank() } ?: return null
        val resource = servers.firstOrNull { it.clientIdentifier == machine } ?: return null
        if (resource.refusesAccountToken) return null

        val response = sectionsFor(session.serverUri, resource.accessToken, account.token).getOrNull() ?: return null
        val sections = LibraryClient.musicSections(response).toNonEmptyListOrNull() ?: return null

        return if (sections.any { it.key == session.musicSectionKey.value }) {
            SwitchOutcome.Committable(account, session.serverUri, resource, session.musicSectionKey)
        } else {
            SwitchOutcome.NeedsLibrary(account, session.serverUri, resource, sections, servers)
        }
    }
}
