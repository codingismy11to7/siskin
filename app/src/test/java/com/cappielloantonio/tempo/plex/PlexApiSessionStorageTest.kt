package com.cappielloantonio.tempo.plex

import android.content.Context
import com.cappielloantonio.tempo.plex.account.PlexAccountStore
import com.cappielloantonio.tempo.util.PlexAccountFixture.aSession
import com.cappielloantonio.tempo.util.PlexAccountFixture.registerAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlexApiSessionStorageTest {
    // androidx.test:core (ApplicationProvider) is not on this module's test
    // classpath -- only androidx.test:monitor arrives transitively via
    // Robolectric. RuntimeEnvironment.getApplication() is the same app Context
    // AppInstanceTest already relies on, with no new dependency.
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var api: PlexApi

    @Before
    fun setUp() {
        registerAuthenticator()
        api = PlexApi()
        // Robolectric caches SharedPreferences statically across methods, so
        // every field this test depends on is reset rather than assumed absent.
        api.session = null
        api.accountToken = null
    }

    @Test
    fun writingASessionPutsBothTokensInTheAccountAndTheRestInPreferences() {
        api.session = aSession()

        val store = PlexAccountStore(context)
        assertEquals("acct", store.accountToken())
        assertEquals("srv", store.serverToken("machine-a"))
        assertEquals("https://one.example", api.serverUri)
        assertEquals("4", api.musicSectionKey)
        assertEquals("machine-a", api.machineIdentifier)
    }

    @Test
    fun aSessionReadsBackWholeThroughTheAccount() {
        api.session = aSession()

        val read = api.session
        assertNotNull(read)
        assertEquals("acct", read!!.accountToken)
        assertEquals("srv", read.serverToken)
        assertEquals("machine-a", read.machineIdentifier)
    }

    @Test
    fun switchingServersDoesNotLeaveThePreviousServersTokenReadable() {
        api.session = aSession(serverToken = "srv-a")

        api.session =
            aSession(
                serverUri = "https://two.example",
                musicSectionKey = "9",
                serverToken = null,
                machineIdentifier = "machine-b",
            )

        // Owned server: no server token, and machine-a's must not surface.
        assertNull(api.serverToken)
        assertEquals("machine-b", api.machineIdentifier)

        // Asserted through the store rather than through api.serverToken,
        // which would pass while machine-a's token sat there untouched: the
        // tag makes it invisible, and invisible is not the same as gone. An
        // access token for a server the user has left is a credential with no
        // owner.
        assertNull(PlexAccountStore(context).serverToken("machine-a"))
    }

    @Test
    fun clearingTheSessionKeepsTheAccountTokenBecauseThePinGrantIsStillGood() {
        api.session = aSession()

        api.session = null

        assertEquals("acct", api.accountToken)
        assertNull(api.serverUri)
        assertNull(api.session)
    }

    @Test
    fun ownedRoundTripsThroughPreferences() {
        api.session = aSession(owned = false)

        assertEquals(false, api.session!!.owned)
    }

    @Test
    fun clearingTheSessionForgetsOwnership() {
        api.session = aSession(owned = false)

        api.session = null

        assertEquals(true, api.serverOwned)
    }
}
