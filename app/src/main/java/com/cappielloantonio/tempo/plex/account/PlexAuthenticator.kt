package com.cappielloantonio.tempo.plex.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.ui.activity.CarHostActivity

/**
 * Makes Siskin's Plex sign-in a system account, which AAOS requires of any
 * media app with authentication: it is what puts sign-in and sign-out in the
 * car's Settings, and what lets an OEM forbid adding accounts on a guest
 * profile via DISALLOW_MODIFY_ACCOUNTS.
 *
 * Almost every method here is unsupported on purpose. Siskin holds one account
 * whose credential is a Plex token that never expires and cannot be refreshed,
 * so there is no credential-confirmation step and no token to re-mint --
 * [PlexAccountStore] reads and writes both tokens directly, and the only thing
 * the system needs from this class is somewhere to send a user who taps
 * "Add account".
 */
class PlexAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

    // CarHostActivity is @UnstableApi (media3); this is the only call site in
    // this class that touches it, matching the per-call-site @OptIn pattern
    // BrowseTabOrderFragment and PlexSignInFragment already use for the same
    // class, rather than an @UnstableApi on the whole authenticator. The OptIn
    // here must be androidx.annotation.OptIn, not kotlin.OptIn -- UnstableApi
    // carries androidx.annotation.RequiresOptIn, which only the androidx
    // annotation checks against; kotlin.OptIn validates against
    // kotlin.RequiresOptIn instead and silently no-ops.
    @OptIn(UnstableApi::class)
    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        // EXTRA_FORCE_SIGN_IN is the flag CarHostActivity already uses to tell
        // "the settings gear opened this" from "something needs a sign-in".
        // Reusing it means Add account joins a path that exists rather than
        // adding a third one to keep in step.
        val intent = Intent(context, CarHostActivity::class.java)
            .putExtra(CarHostActivity.EXTRA_FORCE_SIGN_IN, true)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)

        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle = unsupported("editProperties")

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle = unsupported("confirmCredentials")

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle = unsupported("getAuthToken")

    override fun getAuthTokenLabel(authTokenType: String?): String =
        context.getString(R.string.app_name)

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle = unsupported("updateCredentials")

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    /**
     * Returned rather than thrown: these calls arrive over a binder, and an
     * exception crossing it surfaces to the caller as a dead process rather
     * than as a refusal.
     */
    private fun unsupported(what: String): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "$what is not supported by Siskin")
    }
}
