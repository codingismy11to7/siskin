package com.cappielloantonio.tempo.plex.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import org.junit.Assert.assertEquals
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
        // removalListenerRegistered is process-lifetime state by design (see
        // its KDoc), which makes it leak between test methods the same way
        // Robolectric's statically cached SharedPreferences does -- without
        // this, whichever test runs first claims the registration and every
        // test after it silently no-ops.
        PlexAccountStore.resetRemovalListenerForTest()
    }

    @Test
    fun removingTheAccountFromSystemSettingsClearsTheSession() {
        val api = PlexApi()
        api.session =
            PlexSession(
                accountToken = "acct",
                serverUri = "https://one.example",
                musicSectionKey = SectionKey("4"),
                serverToken = "srv",
                machineIdentifier = "machine-a",
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

    @Test
    fun observingRemovalTwiceStillRegistersOnlyOneListener() {
        // Simulates BaseMediaService.initializeMediaLibrarySession running
        // twice in one process: the service is destroyed (onTaskRemoved's
        // stopSelf when nothing is playing) and recreated, calling
        // observeRemoval again from onCreate(). Without the guard in
        // PlexAccountStore, that second call would register a second
        // AccountManager listener that never gets torn down either.
        val type = context.getString(R.string.plex_account_type)
        val manager = AccountManager.get(context)
        manager.addAccountExplicitly(Account(PlexAccountStore.ACCOUNT_NAME, type), "acct", null)

        var callCount = 0
        PlexAccountStore(context).observeRemoval { callCount++ }
        PlexAccountStore(context).observeRemoval { callCount++ }

        manager.getAccountsByType(type).forEach { manager.removeAccountExplicitly(it) }
        ShadowLooper.idleMainLooper()

        assertEquals("a second observeRemoval call registered its own listener", 1, callCount)
    }
}
