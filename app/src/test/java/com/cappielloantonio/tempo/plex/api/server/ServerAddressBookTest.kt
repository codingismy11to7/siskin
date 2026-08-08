package com.cappielloantonio.tempo.plex.api.server

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because ServerAddressBook reads and writes PlexApi, which reads
 * App.getInstance().preferences.
 */
@RunWith(RobolectricTestRunner::class)
class ServerAddressBookTest {

    private lateinit var api: PlexApi

    /**
     * Robolectric caches SharedPreferences statically across test methods, so
     * every field these tests depend on is reset rather than assumed absent.
     */
    @Before
    fun reset() {
        api = PlexApi()
        api.accountToken = "account-token"
        api.serverUri = null
        api.serverToken = null
        api.musicSectionKey = null
        api.machineIdentifier = null
        api.serverCandidates = null
    }

    private fun connection(uri: String, relay: Boolean = false) = Connection().apply {
        this.uri = uri
        this.local = !relay
        this.relay = relay
    }

    private fun resource(id: String, vararg connections: Connection) = Resource().apply {
        this.clientIdentifier = id
        this.connections = connections.toList()
    }

    @Test
    fun adoptStoresEveryAdvertisedAddressSplitIntoTiers() {
        val book = ServerAddressBook(api)

        book.adopt(
            resource(
                "machine-a",
                connection("https://lan.example"),
                connection("https://public.example"),
                connection("https://relay.example", relay = true)
            ),
            "https://lan.example"
        )

        val stored = book.storedCandidates("machine-a")
        assertEquals(listOf("https://lan.example", "https://public.example"), stored?.direct)
        assertEquals(listOf("https://relay.example"), stored?.relay)
    }

    @Test
    fun storedCandidatesAreRefusedForADifferentServer() {
        val book = ServerAddressBook(api)
        book.adopt(resource("machine-a", connection("https://lan.example")), "https://lan.example")

        // The stamp is the whole point: racing server A's addresses while the
        // session points at server B would adopt an address for the wrong server.
        assertNull(book.storedCandidates("machine-b"))
    }

    @Test
    fun currentReadsTheSessionsAddress() {
        api.serverUri = "https://lan.example"
        api.musicSectionKey = "5"
        api.machineIdentifier = "machine-a"

        assertEquals("https://lan.example", ServerAddressBook(api).current())
    }

    @Test
    fun currentIsNullWithoutACompleteSession() {
        // serverUri alone is not a session -- PlexSession.from returns null
        // without a section key, and current() must not report an address the
        // rest of the app would refuse to use.
        api.serverUri = "https://lan.example"

        assertNull(ServerAddressBook(api).current())
    }
}
