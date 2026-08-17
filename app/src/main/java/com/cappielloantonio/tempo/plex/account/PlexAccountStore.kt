package com.cappielloantonio.tempo.plex.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PlexAccountStore"

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
            existing == null -> {
                val added =
                    manager.addAccountExplicitly(Account(ACCOUNT_NAME, accountType), value, null)
                if (!added) {
                    // A device policy disabling account management for this type, or
                    // the restriction appearing between the sign-in screen's check and
                    // here, leaves no account behind. The PIN flow still reports
                    // success to the user, and the browse gate will ask them to sign
                    // in again on the very next request -- forever, since nothing
                    // retries this write.
                    Log.w(
                        TAG,
                        "addAccountExplicitly returned false; no Plex account was " +
                            "created, so sign-in will appear to finish but the browse " +
                            "gate will keep asking to sign in again"
                    )
                }
            }
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

    /**
     * Calls [onRemoved] when Siskin's account disappears, whichever side did
     * it -- the car's Settings and Siskin's own Sign out are the same
     * operation reached from two places, and neither is the special case.
     *
     * Registers at most one [AccountManager] listener per process: a call
     * after the first is a no-op. That guard is what makes "held for the
     * life of the process," below, actually true rather than aspirational --
     * the only caller, `BaseMediaService.initializeMediaLibrarySession`, runs
     * from `onCreate()`, which is once per *service instance*, and the
     * service is recreated within a single process's lifetime (`onTaskRemoved`
     * calling `stopSelf()` when nothing is playing, then a later restart).
     * Without the guard, each recreation would add another listener that is,
     * itself, never unregistered, and they would accumulate for as long as
     * the process lived.
     *
     * The listener is never unregistered. It is held for the life of the
     * process by design: the thing it protects against is the car's Settings
     * removing the account while Siskin is in the background, which is
     * precisely when a lifecycle-scoped listener would be gone.
     */
    fun observeRemoval(onRemoved: () -> Unit) {
        if (!removalListenerRegistered.compareAndSet(false, true)) return

        var present = hasAccount()

        manager.addOnAccountsUpdatedListener({
            val nowPresent = hasAccount()
            if (present && !nowPresent) onRemoved()
            present = nowPresent
        }, null, true)
    }

    companion object {
        const val ACCOUNT_NAME = "Plex"

        private const val SERVER_TOKEN_TYPE = "plex.server"

        /**
         * Guards [observeRemoval] so repeated calls -- one per service
         * recreation, not per process -- register exactly one
         * [AccountManager] listener. See that function's KDoc.
         */
        private val removalListenerRegistered = AtomicBoolean(false)

        /**
         * Test seam only: [removalListenerRegistered] is deliberately
         * process-lifetime state, which makes it leak between test methods
         * sharing a JVM fork the same way Robolectric's statically cached
         * `SharedPreferences` does. Call from `@Before` so one test's
         * [observeRemoval] call doesn't silently no-op the next test's.
         */
        @VisibleForTesting
        internal fun resetRemovalListenerForTest() {
            removalListenerRegistered.set(false)
        }

        /**
         * The token type names the server, so a lookup for a different one
         * returns null on its own. That is what holds PlexSession's invariant
         * across the *write* path: serverUri and musicSectionKey are in
         * preferences while this is in the account, so there is a window
         * between the two writes, and inside it a lookup can only return the
         * current server's own token or null -- the worst case being a request
         * made with the account token instead of a server token, which fails
         * exactly as an absent server token already does.
         *
         * It does not make PlexApi.session's *getter* atomic, and nothing here
         * does. That getter reads five values independently, so a write
         * landing mid-read can still assemble a session from two servers --
         * see its KDoc and the 2026-08-16 plex-system-account design. The race
         * predates this type; storing the token here widened it from an
         * in-memory read to two Binder round trips without changing its kind.
         */
        fun authTokenType(machineIdentifier: String?): String =
            if (machineIdentifier.isNullOrBlank()) SERVER_TOKEN_TYPE
            else "$SERVER_TOKEN_TYPE:$machineIdentifier"
    }
}
