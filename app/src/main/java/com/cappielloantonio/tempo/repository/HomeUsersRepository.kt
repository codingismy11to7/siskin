package com.cappielloantonio.tempo.repository

import androidx.annotation.VisibleForTesting
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.PlexAccount

/**
 * Settings' view of the Plex Home: who is in it and who is signed in. Asked
 * again on every Settings visit, so a member added or renamed since sign-in
 * shows up; nothing is written to disk.
 *
 * The last successful answer is held in memory, and the media service asks
 * once at startup, so Settings can draw the Switch user row before plex.tv
 * replies instead of moving Sign out under a driver's finger when it does. It
 * is tagged with [PlexApi.sessionGeneration] and is not handed out once that
 * moves, which a change of listener always does -- so the row never names the
 * member who was listening before a switch.
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

    @Volatile
    private var last: Pair<Long, Home>? = null

    /** The last answer, if it was fetched for the session that is current now. */
    fun remembered(): Home? = last?.takeIf { it.first == PlexApi.sessionGeneration }?.second

    /**
     * Asks plex.tv, remembering a successful answer. A failure leaves the
     * remembered one in place: hiding a row already drawn would slide Sign out
     * up into it.
     */
    suspend fun refresh(): Home? {
        val generation = PlexApi.sessionGeneration
        return fetch()?.also { last = generation to it }
    }

    @VisibleForTesting
    fun resetForTest() {
        fetch = ::fromPlexTv
        last = null
    }

    private suspend fun fromPlexTv(): Home? {
        val client = AuthClient(PlexApi())
        val users = client.homeUsers().getOrNull() ?: return null
        val current = client.currentUser().getOrNull() ?: return null
        return Home(users, current)
    }
}
