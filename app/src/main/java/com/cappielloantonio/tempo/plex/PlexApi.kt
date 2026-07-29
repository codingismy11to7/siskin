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

    /** From the approved PIN. Authenticates plex.tv calls. */
    var accountToken: String?
        get() = preferences.getString(KEY_ACCOUNT_TOKEN, null)
        set(value) = preferences.edit().putString(KEY_ACCOUNT_TOKEN, value).apply()

    /**
     * From the chosen Resource.accessToken, and null for a server the account
     * owns -- those accept the account token. A *shared* server does not, which
     * is the whole reason this is a second field rather than an overwrite of
     * [accountToken]: plex.tv still needs the account one afterwards.
     */
    var serverToken: String?
        get() = preferences.getString(KEY_SERVER_TOKEN, null)
        set(value) = preferences.edit().putString(KEY_SERVER_TOKEN, value).apply()

    /** Base URL of the chosen media server; null until discovery completes. */
    var serverUri: String?
        get() = preferences.getString(KEY_SERVER_URI, null)
        set(value) = preferences.edit().putString(KEY_SERVER_URI, value).apply()

    /** Section key of the chosen music library; null until the user picks one. */
    var musicSectionKey: String?
        get() = preferences.getString(KEY_MUSIC_SECTION_KEY, null)
        set(value) = preferences.edit().putString(KEY_MUSIC_SECTION_KEY, value).apply()

    /**
     * The signed-in connection, or null when there is not a complete one.
     *
     * Written as a unit: one `edit()` carrying all four keys, or all four
     * removed. That atomicity is the point -- a reader between two separate
     * writes could otherwise see one server's address beside another's section
     * key and treat it as a working sign-in.
     */
    var session: PlexSession?
        get() = PlexSession.from(accountToken, serverUri, musicSectionKey, serverToken)
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    // Clearing the session deliberately leaves accountToken alone --
                    // the PIN grant is still valid and plex.tv calls still need it.
                    remove(KEY_SERVER_URI)
                    remove(KEY_SERVER_TOKEN)
                    remove(KEY_MUSIC_SECTION_KEY)
                } else {
                    putString(KEY_ACCOUNT_TOKEN, value.accountToken)
                    putString(KEY_SERVER_URI, value.serverUri)
                    putString(KEY_MUSIC_SECTION_KEY, value.musicSectionKey)
                    putString(KEY_SERVER_TOKEN, value.serverToken)
                }
            }.apply()
        }

    val appVersion: String get() = BuildConfig.VERSION_NAME

    fun plexTvHeaders(): Map<String, String> =
        PlexIdentity.headers(clientIdentifier, appVersion, accountToken)

    fun serverHeaders(): Map<String, String> =
        PlexIdentity.headers(clientIdentifier, appVersion, serverTokenOrAccount(serverToken, accountToken))

    companion object {
        private const val KEY_CLIENT_ID = "plex_client_identifier"
        private const val KEY_ACCOUNT_TOKEN = "plex_token"
        private const val KEY_SERVER_TOKEN = "plex_server_token"
        private const val KEY_SERVER_URI = "plex_server_uri"
        private const val KEY_MUSIC_SECTION_KEY = "plex_music_section_key"

        /**
         * Process-wide, because each Plex client constructs its own PlexApi and the
         * identity interceptor reads clientIdentifier on every request from OkHttp's
         * multi-threaded dispatcher. Without this, a fresh install can mint two
         * identifiers concurrently -- and Plex ties the PIN grant to the identifier,
         * so the grant would be unclaimable.
         */
        private val IDENTITY_LOCK = Any()

        /**
         * Which token authenticates a media-server call. Pure and static because
         * PlexApi itself reads SharedPreferences, which unit tests cannot observe.
         */
        @JvmStatic
        fun serverTokenOrAccount(serverToken: String?, accountToken: String?): String? =
            if (serverToken.isNullOrBlank()) accountToken else serverToken
    }
}
