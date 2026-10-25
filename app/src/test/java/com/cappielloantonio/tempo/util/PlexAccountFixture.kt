package com.cappielloantonio.tempo.util

import android.accounts.AccountManager
import android.content.Context
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** For the Robolectric suites that write tokens into Siskin's system account. */
object PlexAccountFixture {
    /**
     * ShadowAccountManager refuses addAccountExplicitly for a type with no
     * registered authenticator, the same way the real one does.
     */
    fun registerAuthenticator() {
        val context: Context = RuntimeEnvironment.getApplication()
        shadowOf(AccountManager.get(context)).addAuthenticator(context.getString(R.string.plex_account_type))
    }

    fun aSession(
        accountToken: String = "acct",
        serverUri: String = "https://one.example",
        musicSectionKey: String = "4",
        serverToken: String? = "srv",
        machineIdentifier: String? = "machine-a",
        owned: Boolean = true,
    ) = PlexSession(accountToken, serverUri, SectionKey(musicSectionKey), serverToken, machineIdentifier, owned)
}
