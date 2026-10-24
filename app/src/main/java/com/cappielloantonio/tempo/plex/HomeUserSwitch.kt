package com.cappielloantonio.tempo.plex

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.toNonEmptyListOrNull
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.SwitchUserError
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.auth.SignInError
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.PlexAccount
import com.cappielloantonio.tempo.plex.models.Resource

/** Where a switch leaves the user. Nothing in it has been persisted. */
sealed interface SwitchOutcome {
    val account: PlexAccount

    /** The saved server and library are both visible to the new user. */
    data class Committable(
        override val account: PlexAccount,
        val serverUri: String,
        val resource: Resource,
        val sectionKey: SectionKey,
    ) : SwitchOutcome

    /** The saved server is visible, its library is not. */
    data class NeedsLibrary(
        override val account: PlexAccount,
        val serverUri: String,
        val resource: Resource,
        val sections: NonEmptyList<Directory>,
        val servers: NonEmptyList<Resource>,
    ) : SwitchOutcome

    /** Nothing saved, or the saved server is not the new user's. */
    data class NeedsServer(
        override val account: PlexAccount,
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
 */
class HomeUserSwitch(
    private val authClient: AuthClient,
    private val authClientFor: (String) -> AuthClient,
    private val sectionsFor: suspend (serverUri: String, serverToken: String?) -> Either<PlexTransportFailure, PlexResponse>,
) {
    constructor(api: PlexApi, authClient: AuthClient) : this(
        authClient,
        { token -> AuthClient(api, token) },
        { uri, token -> LibraryClient(api, uri, token).getSections() },
    )

    suspend fun switch(
        user: HomeUser,
        pin: String?,
        saved: PlexSession?,
    ): Either<SignInError, SwitchOutcome> =
        either {
            val uuid = ensureNotNull(user.uuid) { SignInError.NoCandidate }
            val account =
                authClient
                    .switchTo(uuid, pin)
                    .mapLeft {
                        when (it) {
                            SwitchUserError.WrongPin -> SignInError.WrongPin
                            SwitchUserError.RateLimited -> SignInError.RateLimited
                            is SwitchUserError.Transport -> SignInError.Api(it.failure)
                        }
                    }.bind()
            val token = ensureNotNull(account.authToken) { SignInError.NoCandidate }

            val resources = authClientFor(token).getResources().mapLeft(SignInError::Api).bind()
            val servers =
                ensureNotNull(AuthClient.mediaServers(resources).toNonEmptyListOrNull()) { SignInError.NoServers }

            val savedSession = saved ?: return@either SwitchOutcome.NeedsServer(account, servers)
            val machine = savedSession.machineIdentifier
            val resource =
                servers.firstOrNull { !machine.isNullOrBlank() && it.clientIdentifier == machine }
                    ?: return@either SwitchOutcome.NeedsServer(account, servers)

            ensure(!(resource.owned == false && resource.accessToken.isNullOrBlank())) { SignInError.ServerTokenMissing }

            val response =
                sectionsFor(savedSession.serverUri, resource.accessToken).mapLeft(SignInError::Api).bind()
            val sections =
                LibraryClient.musicSections(response).toNonEmptyListOrNull()
                    ?: return@either SwitchOutcome.NeedsServer(account, servers)

            if (sections.any { it.key == savedSession.musicSectionKey.value }) {
                SwitchOutcome.Committable(account, savedSession.serverUri, resource, savedSession.musicSectionKey)
            } else {
                SwitchOutcome.NeedsLibrary(account, savedSession.serverUri, resource, sections, servers)
            }
        }
}
