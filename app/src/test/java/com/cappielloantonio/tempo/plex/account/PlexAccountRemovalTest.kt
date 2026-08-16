package com.cappielloantonio.tempo.plex.account

import android.accounts.AccountManager
import android.content.Context
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class PlexAccountRemovalTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        shadowOf(AccountManager.get(context))
            .addAuthenticator(context.getString(R.string.plex_account_type))
        PlexApi().session = null
        PlexApi().accountToken = null
    }

    @Test
    fun removingTheAccountFromSystemSettingsClearsTheSession() {
        val api = PlexApi()
        api.session = PlexSession(
            accountToken = "acct",
            serverUri = "https://one.example",
            musicSectionKey = SectionKey("4"),
            serverToken = "srv",
            machineIdentifier = "machine-a"
        )

        var notified = false
        PlexAccountStore(context).observeRemoval { notified = true }

        // What the car's Settings does: it removes the account, and the app
        // finds out only through the listener.
        val type = context.getString(R.string.plex_account_type)
        val manager = AccountManager.get(context)
        manager.getAccountsByType(type).forEach { manager.removeAccountExplicitly(it) }
        ShadowLooper.idleMainLooper()

        assertTrue("the listener never fired", notified)
        assertNull(api.session)
        assertNull(api.accountToken)
    }
}
