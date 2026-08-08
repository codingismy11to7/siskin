package com.cappielloantonio.tempo.plex.api.server

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Answers /identity, like a reachable Plex server. */
    private fun liveServer() = MockWebServer().apply {
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{}}""")
        }
        start()
    }

    /** A port with nothing listening: connection refused, the fastest failure. */
    private fun deadUri(): String {
        val server = MockWebServer()
        server.start()
        val uri = server.url("/").toString().trimEnd('/')
        server.shutdown()
        return uri
    }

    private fun signedInAt(uri: String, machineIdentifier: String = "machine-a") {
        api.session = com.cappielloantonio.tempo.plex.PlexSession(
            accountToken = "account-token",
            serverUri = uri,
            musicSectionKey = com.cappielloantonio.tempo.plex.SectionKey("5"),
            serverToken = "server-token",
            machineIdentifier = machineIdentifier
        )
    }

    @Test
    fun theDriveHome() = runTest {
        // The bug this whole change exists for: signed in on an address that
        // works, then that address stops answering while another one still does.
        val live = liveServer()
        val liveUri = live.url("/").toString().trimEnd('/')
        val dead = deadUri()

        val book = ServerAddressBook(api)
        book.adopt(resource("machine-a", connection(dead), connection(liveUri)), dead)
        signedInAt(dead)

        val recovered = book.reprobe(dead)

        assertEquals(liveUri, recovered)
        assertEquals("Winner must be persisted", liveUri, api.serverUri)
        live.shutdown()
    }

    @Test
    fun reprobeKeepsTheRestOfTheSessionIntact() = runTest {
        // Guards the invariant PlexSession's KDoc describes. If this copy is ever
        // turned into a whole-session rebuild, this goes red.
        val live = liveServer()
        val liveUri = live.url("/").toString().trimEnd('/')
        val dead = deadUri()

        val book = ServerAddressBook(api)
        book.adopt(resource("machine-a", connection(dead), connection(liveUri)), dead)
        signedInAt(dead)

        book.reprobe(dead)

        val session = api.session
        assertNotNull(session)
        assertEquals("machine-a", session?.machineIdentifier)
        assertEquals("5", session?.musicSectionKey?.value)
        assertEquals("server-token", session?.serverToken)
        assertEquals("account-token", session?.accountToken)
        live.shutdown()
    }

    @Test
    fun concurrentCallersShareOneRace() = runTest {
        // Four browse tabs failing at once must not start four races.
        val live = liveServer()
        val liveUri = live.url("/").toString().trimEnd('/')
        val dead = deadUri()

        val book = ServerAddressBook(api)
        book.adopt(resource("machine-a", connection(dead), connection(liveUri)), dead)
        signedInAt(dead)

        val results = coroutineScope {
            (1..4).map { async { book.reprobe(dead) } }.awaitAll()
        }

        assertTrue("all four should get the new address", results.all { it == liveUri })
        assertEquals("only one race should have run", 1, live.requestCount)
        live.shutdown()
    }

    @Test
    fun aCallerArrivingAfterTheFixDoesNotProbe() = runTest {
        val live = liveServer()
        val liveUri = live.url("/").toString().trimEnd('/')
        val dead = deadUri()

        val book = ServerAddressBook(api)
        book.adopt(resource("machine-a", connection(dead), connection(liveUri)), dead)
        signedInAt(dead)

        book.reprobe(dead)
        val requestsAfterFirst = live.requestCount

        // Second caller still holding the old address; the book already moved on.
        assertEquals(liveUri, book.reprobe(dead))
        assertEquals("no second race", requestsAfterFirst, live.requestCount)
        live.shutdown()
    }

    @Test
    fun aTotalFailureBacksOff() = runTest {
        val dead = deadUri()
        val alsoDead = deadUri()
        var now = 0L

        val book = ServerAddressBook(api, clock = { now })
        book.adopt(resource("machine-a", connection(dead), connection(alsoDead)), dead)
        signedInAt(dead)

        assertNull(book.reprobe(dead))

        // Inside the cooldown: returns null without racing again. Observable as
        // the call being immediate rather than paying another round of timeouts.
        now = 1_000L
        val started = System.currentTimeMillis()
        assertNull(book.reprobe(dead))
        assertTrue("should not have raced again", System.currentTimeMillis() - started < 500)
    }
}
