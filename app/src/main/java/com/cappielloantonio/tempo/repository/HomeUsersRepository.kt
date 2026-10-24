package com.cappielloantonio.tempo.repository

import androidx.annotation.VisibleForTesting
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.PlexAccount

/**
 * Settings' live view of the Plex Home: who is in it and who is signed in.
 * Asked on every visit rather than stored, so a member added or renamed since
 * sign-in shows up, and Siskin keeps no record of who it is signed in as.
 */
object HomeUsersRepository {
    data class Home(
        val users: List<HomeUser>,
        val current: PlexAccount,
    )

    /**
     * Null when either call fails. A seam: every test that shows settings
     * replaces it, since the real one calls plex.tv.
     */
    @VisibleForTesting
    var fetch: suspend () -> Home? = ::fromPlexTv

    @VisibleForTesting
    fun resetForTest() {
        fetch = ::fromPlexTv
    }

    private suspend fun fromPlexTv(): Home? {
        val client = AuthClient(PlexApi())
        val users = client.homeUsers().getOrNull() ?: return null
        val current = client.currentUser().getOrNull() ?: return null
        return Home(users, current)
    }
}
