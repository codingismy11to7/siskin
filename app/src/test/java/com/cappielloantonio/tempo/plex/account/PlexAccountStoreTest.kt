package com.cappielloantonio.tempo.plex.account

import android.accounts.AccountManager
import android.content.Context
import com.cappielloantonio.tempo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlexAccountStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var store: PlexAccountStore

    @Before
    fun setUp() {
        val manager = AccountManager.get(context)
        // ShadowAccountManager refuses addAccountExplicitly for a type with no
        // registered authenticator, the same way the real one does.
        shadowOf(manager).addAuthenticator(context.getString(R.string.plex_account_type))
        store = PlexAccountStore(context)
    }

    @Test
    fun theAccountTokenRoundTrips() {
        assertFalse(store.hasAccount())

        store.setAccountToken("acct")

        assertTrue(store.hasAccount())
        assertEquals("acct", store.accountToken())
    }

    @Test
    fun clearingTheAccountTokenRemovesTheAccount() {
        store.setAccountToken("acct")

        store.setAccountToken(null)

        assertFalse(store.hasAccount())
        assertNull(store.accountToken())
    }

    @Test
    fun signingInTwiceLeavesOneAccountRatherThanTwo() {
        store.setAccountToken("first")
        store.setAccountToken("second")

        val type = context.getString(R.string.plex_account_type)
        assertEquals(1, AccountManager.get(context).getAccountsByType(type).size)
        assertEquals("second", store.accountToken())
    }

    @Test
    fun aServerTokenIsInvisibleToADifferentServer() {
        // The invariant, asserted directly: serverUri and musicSectionKey live
        // in preferences and the server token lives here, so without the tag a
        // reader mid-switch could pair one server's address with another's
        // token and send it.
        store.setAccountToken("acct")
        store.setServerToken("machine-a", "token-a")

        assertEquals("token-a", store.serverToken("machine-a"))
        assertNull(store.serverToken("machine-b"))
    }

    @Test
    fun aServerTokenWithNoMachineIdentifierFallsBackToTheUntaggedType() {
        // Defensive: both session writes stamp machineIdentifier from
        // Resource.clientIdentifier. Dropping the token instead would break a
        // shared server, which genuinely needs it.
        store.setAccountToken("acct")
        store.setServerToken(null, "token-untagged")

        assertEquals("token-untagged", store.serverToken(null))
        assertNull(store.serverToken("machine-a"))
    }

    @Test
    fun clearingAServerTokenLeavesTheAccountAndItsPasswordAlone() {
        // Signing out of a server is not signing out of Plex; plex.tv calls
        // still need the account token.
        store.setAccountToken("acct")
        store.setServerToken("machine-a", "token-a")

        store.setServerToken("machine-a", null)

        assertNull(store.serverToken("machine-a"))
        assertEquals("acct", store.accountToken())
        assertTrue(store.hasAccount())
    }

    @Test
    fun aServerTokenWithoutAnAccountIsDroppedRatherThanCrashing() {
        store.setServerToken("machine-a", "orphan")

        assertNull(store.serverToken("machine-a"))
    }
}
