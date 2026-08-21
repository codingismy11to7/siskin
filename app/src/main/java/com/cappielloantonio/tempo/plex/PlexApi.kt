package com.cappielloantonio.tempo.plex

import androidx.core.content.edit
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.plex.account.PlexAccountStore
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

    /**
     * Both tokens live in the system account rather than in preferences -- see
     * `docs/decisions/2026-08-16-plex-system-account-design.md`. Constructed
     * per PlexApi like `preferences` above:
     * this class is built ad hoc at eleven call sites and has no lifecycle to
     * hang a shared instance on.
     */
    private val accounts get() = PlexAccountStore()

    /** Stable per install; generated once on first use. */
    val clientIdentifier: String
        get() =
            synchronized(IDENTITY_LOCK) {
                preferences.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
                    preferences.edit { putString(KEY_CLIENT_ID, it) }
                }
            }

    /** From the approved PIN. Authenticates plex.tv calls. */
    var accountToken: String?
        get() = accounts.accountToken()
        set(value) = accounts.setAccountToken(value)

    /**
     * From the chosen Resource.accessToken, and null for a server the account
     * owns -- those accept the account token. A *shared* server does not, which
     * is the whole reason this is a second field rather than an overwrite of
     * [accountToken]: plex.tv still needs the account one afterwards.
     *
     * Stored against [machineIdentifier], so this reads null for a token
     * belonging to a server other than the one currently selected rather than
     * handing it out. That is deliberate and is what keeps [session]'s
     * invariant true across two stores.
     */
    var serverToken: String?
        get() = accounts.serverToken(machineIdentifier)
        set(value) = accounts.setServerToken(machineIdentifier, value)

    /** Base URL of the chosen media server; null until discovery completes. */
    var serverUri: String?
        get() = preferences.getString(KEY_SERVER_URI, null)
        set(value) = preferences.edit { putString(KEY_SERVER_URI, value) }

    /** Section key of the chosen music library; null until the user picks one. */
    var musicSectionKey: String?
        get() = preferences.getString(KEY_MUSIC_SECTION_KEY, null)
        set(value) = preferences.edit { putString(KEY_MUSIC_SECTION_KEY, value) }

    /** Stable per server; absent for sessions written before it was recorded. */
    var machineIdentifier: String?
        get() = preferences.getString(KEY_MACHINE_IDENTIFIER, null)
        set(value) = preferences.edit { putString(KEY_MACHINE_IDENTIFIER, value) }

    /**
     * Every address the current server advertises, as JSON; null before one has
     * been recorded. Read and written only through ServerAddressBook, which owns
     * the encoding and the machineIdentifier stamp.
     *
     * Deliberately *not* part of [session]. A session is credentials, written
     * all-or-nothing; this is a cache of reachability information that goes
     * stale on its own schedule and whose absence is survivable -- a re-probe
     * without it just starts from plex.tv instead.
     */
    var serverCandidates: String?
        get() = preferences.getString(KEY_SERVER_CANDIDATES, null)
        set(value) = preferences.edit { putString(KEY_SERVER_CANDIDATES, value) }

    /**
     * The signed-in connection, or null when there is not a complete one.
     *
     * The two tokens live in the system account, and the server token is
     * filed under the machine identifier it belongs to -- that closes the
     * *write* window: whichever of a preferences edit and an account write
     * lands last, a lookup for the current server can only return that
     * server's own token or null, never a token belonging to a different one.
     *
     * It does not close the read window. This getter still makes five
     * independent reads -- accountToken, serverUri, musicSectionKey,
     * serverToken (which itself re-reads machineIdentifier), and
     * machineIdentifier again -- and a write landing between any two of them
     * can still hand back a session assembled from two servers, e.g. an old
     * [serverUri] beside a [serverToken] for the server a concurrent write
     * just switched *to*. That is not new: the pre-account-store getter read
     * the same five preference keys independently and had the identical
     * exposure -- only the span changed, from an in-memory map read to two
     * Binder round trips. Narrowing it would mean making this getter atomic,
     * which was considered and rejected; see the design doc's "Alternatives
     * considered".
     */
    var session: PlexSession?
        get() =
            PlexSession.from(
                accountToken,
                serverUri,
                musicSectionKey,
                serverToken,
                machineIdentifier,
            )
        set(value) {
            // Read before the preferences write below overwrites it. Switching
            // servers has to clear the previous one's access token: the tag
            // already makes it unreadable, but unreadable is not gone, and a
            // credential for a server the user has left should not outlive the
            // leaving.
            val previousMachineIdentifier = machineIdentifier

            // Preferences first. The account writes below take
            // previousMachineIdentifier and value.machineIdentifier as
            // explicit arguments rather than re-reading the machineIdentifier
            // property, so nothing here depends on this edit having landed
            // yet -- only previousMachineIdentifier, captured above, does.
            preferences.edit {
                if (value == null) {
                    remove(KEY_SERVER_URI)
                    remove(KEY_MUSIC_SECTION_KEY)
                    remove(KEY_MACHINE_IDENTIFIER)
                } else {
                    putString(KEY_SERVER_URI, value.serverUri)
                    putString(KEY_MUSIC_SECTION_KEY, value.musicSectionKey.value)
                    putString(KEY_MACHINE_IDENTIFIER, value.machineIdentifier)
                }
            }

            if (value == null) {
                // Clearing the session deliberately leaves accountToken alone --
                // the PIN grant is still valid and plex.tv calls still need it.
                // The server token goes, because it belongs to a server that is
                // no longer selected.
                accounts.setServerToken(previousMachineIdentifier, null)
            } else {
                if (previousMachineIdentifier != null &&
                    previousMachineIdentifier != value.machineIdentifier
                ) {
                    accounts.setServerToken(previousMachineIdentifier, null)
                }
                accounts.setAccountToken(value.accountToken)
                accounts.setServerToken(value.machineIdentifier, value.serverToken)
            }
        }

    val appVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * The car's language, for `X-Plex-Language`. Read from the default locale
     * rather than from Preferences: the head unit's language is the car's
     * setting, and this app has no language setting of its own.
     */
    val language: String get() =
        java.util.Locale
            .getDefault()
            .language

    fun plexTvHeaders(): Map<String, String> = PlexIdentity.headers(clientIdentifier, appVersion, accountToken, language)

    companion object {
        private const val KEY_CLIENT_ID = "plex_client_identifier"
        private const val KEY_SERVER_URI = "plex_server_uri"
        private const val KEY_MUSIC_SECTION_KEY = "plex_music_section_key"
        private const val KEY_MACHINE_IDENTIFIER = "plex_machine_identifier"
        private const val KEY_SERVER_CANDIDATES = "plex_server_candidates"

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
        fun serverTokenOrAccount(
            serverToken: String?,
            accountToken: String?,
        ): String? = if (serverToken.isNullOrBlank()) accountToken else serverToken
    }
}
