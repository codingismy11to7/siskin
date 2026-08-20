package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource

/**
 * Plex `Resource` fixtures shared by the suites that need an account to have
 * servers.
 *
 * Extracted from PlexSignInViewModelTest when CarDebugFragmentTest needed the
 * same shape. The constraints it satisfies are not obvious from looking at it
 * -- see [aMediaServer] -- which is why a second copy would be worse than a
 * shared one.
 */
object PlexResourceFixture {
    /**
     * A media server that survives [com.cappielloantonio.tempo.plex.api.auth.AuthClient.mediaServers]:
     * `provides` contains "server" and it has at least one connection with a
     * non-blank `uri`, per
     * [com.cappielloantonio.tempo.plex.api.server.ServerProbe.hasUsableConnection].
     *
     * A bare `Resource()` is filtered out by both checks, so a test that used
     * one would see an empty server list and fail for a reason that has
     * nothing to do with what it was testing.
     */
    fun aMediaServer(
        accessToken: String? = null,
        clientIdentifier: String? = "machine-id",
    ) = Resource().apply {
        name = "Living Room"
        provides = "server"
        connections = listOf(Connection().apply { uri = "https://10.0.0.5:32400" })
        this.accessToken = accessToken
        this.clientIdentifier = clientIdentifier
    }
}
