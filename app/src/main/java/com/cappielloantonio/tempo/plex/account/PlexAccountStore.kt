package com.cappielloantonio.tempo.plex.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R

/**
 * The only place in the app that talks to [AccountManager].
 *
 * Siskin's two credentials live here rather than in SharedPreferences, which
 * AAOS requires of a media app with authentication and which also puts them
 * outside the app's data directory, where a backup cannot reach them.
 *
 * The account name is the constant [ACCOUNT_NAME] rather than the user's Plex
 * identity, because Siskin never learns it: its auth surface is POST /pins,
 * GET /pins/{id} and GET /resources, and none of those name the account
 * holder. A constant is also a stable key, so "exactly one account" needs no
 * enforcement beyond addressing it by name.
 */
class PlexAccountStore(private val context: Context = App.getContext()) {

    private val manager: AccountManager get() = AccountManager.get(context)

    private val accountType: String get() = context.getString(R.string.plex_account_type)

    private val account: Account? get() = manager.getAccountsByType(accountType).firstOrNull()

    fun hasAccount(): Boolean = account != null

    /** The Plex account token, from the approved PIN. */
    fun accountToken(): String? = account?.let { manager.getPassword(it) }

    /**
     * Assigning a token creates the account when there is none and updates it
     * when there is; assigning null removes it.
     *
     * Deliberately *not* remove-then-add: removal fires the accounts-updated
     * listener that clears the session, so a re-sign-in would trip its own
     * sign-out handler partway through.
     */
    fun setAccountToken(value: String?) {
        val existing = account
        when {
            value == null -> existing?.let { manager.removeAccountExplicitly(it) }
            existing == null ->
                manager.addAccountExplicitly(Account(ACCOUNT_NAME, accountType), value, null)
            else -> manager.setPassword(existing, value)
        }
    }

    /**
     * The access token for one server, or null when this account has none for
     * *that* server -- which is also what a server the account owns looks
     * like, and every reader already handles.
     */
    fun serverToken(machineIdentifier: String?): String? =
        account?.let { manager.peekAuthToken(it, authTokenType(machineIdentifier)) }

    fun setServerToken(machineIdentifier: String?, value: String?) {
        val existing = account ?: return
        val type = authTokenType(machineIdentifier)

        if (value == null) {
            // Cleared by type rather than left to rot: an access token for a
            // server the user has left is a credential with no owner.
            manager.peekAuthToken(existing, type)?.let { manager.invalidateAuthToken(accountType, it) }
        } else {
            manager.setAuthToken(existing, type, value)
        }
    }

    companion object {
        const val ACCOUNT_NAME = "Plex"

        private const val SERVER_TOKEN_TYPE = "plex.server"

        /**
         * The token type names the server, so a lookup for a different one
         * returns null on its own. That is what holds PlexSession's invariant
         * across two stores: serverUri and musicSectionKey are in preferences
         * while this is in the account, so there is a window between the two
         * writes, and inside it the worst case is a request made with the
         * account token instead of a server token -- never one carrying
         * another server's credentials.
         */
        fun authTokenType(machineIdentifier: String?): String =
            if (machineIdentifier.isNullOrBlank()) SERVER_TOKEN_TYPE
            else "$SERVER_TOKEN_TYPE:$machineIdentifier"
    }
}
