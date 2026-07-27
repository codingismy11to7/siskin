package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.BuildConfig
import java.util.UUID

/**
 * Holds what every Plex call needs: who we are, who we are signed in as, and
 * which server we are talking to.
 *
 * The client identifier must be stable for the lifetime of the install -- Plex
 * ties the PIN grant to it, so regenerating it silently invalidates the session.
 */
class PlexApi {

    private val preferences get() = App.getInstance().preferences

    /** Stable per install; generated once on first use. */
    val clientIdentifier: String
        get() = synchronized(IDENTITY_LOCK) {
            preferences.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_CLIENT_ID, it).apply()
            }
        }

    var token: String?
        get() = preferences.getString(KEY_TOKEN, null)
        set(value) = preferences.edit().putString(KEY_TOKEN, value).apply()

    /** Base URL of the chosen media server; null until discovery completes. */
    var serverUri: String?
        get() = preferences.getString(KEY_SERVER_URI, null)
        set(value) = preferences.edit().putString(KEY_SERVER_URI, value).apply()

    val appVersion: String get() = BuildConfig.VERSION_NAME

    fun headers(): Map<String, String> =
        PlexIdentity.headers(clientIdentifier, appVersion, token)

    companion object {
        private const val KEY_CLIENT_ID = "plex_client_identifier"
        private const val KEY_TOKEN = "plex_token"
        private const val KEY_SERVER_URI = "plex_server_uri"

        /**
         * Process-wide, because each Plex client constructs its own PlexApi and the
         * identity interceptor reads clientIdentifier on every request from OkHttp's
         * multi-threaded dispatcher. Without this, a fresh install can mint two
         * identifiers concurrently -- and Plex ties the PIN grant to the identifier,
         * so the grant would be unclaimable.
         */
        private val IDENTITY_LOCK = Any()
    }
}
