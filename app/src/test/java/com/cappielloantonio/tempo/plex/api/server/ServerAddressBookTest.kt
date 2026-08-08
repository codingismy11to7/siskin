package com.cappielloantonio.tempo.plex.api.server

import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
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

    /**
     * Shaped like what plex.tv's /resources actually returns: `provides`
     * contains "server" and it carries at least one https connection, per
     * [AuthClient.mediaServers] / [ServerProbe.hasUsableConnection]. [resource]
     * above sets neither, because storedCandidates/adopt never go through that
     * filter -- only a list freshly fetched from plex.tv does.
     */
    private fun mediaServerResource(id: String, vararg connections: Connection) = Resource().apply {
        this.clientIdentifier = id
        this.provides = "server"
        this.connections = connections.toList()
    }

    @Test
    fun adoptStoresEveryAdvertisedAddressSplitIntoTiers() {
        val book = ServerAddressBook.newForTest(api)

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
        val book = ServerAddressBook.newForTest(api)
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

        assertEquals("https://lan.example", ServerAddressBook.newForTest(api).current())
    }

    @Test
    fun currentIsNullWithoutACompleteSession() {
        // serverUri alone is not a session -- PlexSession.from returns null
        // without a section key, and current() must not report an address the
        // rest of the app would refuse to use.
        api.serverUri = "https://lan.example"

        assertNull(ServerAddressBook.newForTest(api).current())
    }

    /** Answers /identity, like a reachable Plex server. */
    private fun liveServer() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{}}""")
        }
        start()
    }

    /**
     * Answers every request with failure while staying alive, so a caller can
     * read requestCount afterward -- unlike [deadUri], which shuts down before
     * returning and leaves nothing to query.
     */
    private fun failingServer() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
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

    /**
     * A syntactically https:// address nothing listens on. Only exists to
     * satisfy [AuthClient.mediaServers]'s scheme filter on a resource built
     * for a test -- the port is closed, so a probe against it fails at the TCP
     * level exactly like [deadUri], with no TLS handshake ever attempted.
     */
    private fun deadHttpsUri(): String = deadUri().replaceFirst("http://", "https://")

    private fun signedInAt(uri: String, machineIdentifier: String = "machine-a") {
        api.session = PlexSession(
            accountToken = "account-token",
            serverUri = uri,
            musicSectionKey = SectionKey("5"),
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

        val book = ServerAddressBook.newForTest(api)
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

        val book = ServerAddressBook.newForTest(api)
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

        val book = ServerAddressBook.newForTest(api)
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

        val book = ServerAddressBook.newForTest(api)
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
        // Stays alive (unlike deadUri) so requestCount is readable afterward,
        // and getResources() is stubbed so the plex.tv escalation this total
        // failure reaches does not make a real network call -- see Important 3.
        val server = failingServer()
        val uri = server.url("/").toString().trimEnd('/')
        // Starts non-zero deliberately: lastFailureAt is nullable now, so there
        // is no 0L sentinel to collide with, but a clock starting at zero is
        // exactly the value an earlier version of this test passed vacuously
        // under. Kept non-zero so that mistake cannot silently recur.
        var now = 1_000L

        val authClient = mock<AuthClient>().stub {
            onBlocking { getResources() } doReturn
                PlexTransportFailure.Unreachable(PlexHost.PlexTv).left()
        }
        val book = ServerAddressBook.newForTest(api, authClient = authClient, clock = { now })
        book.adopt(resource("machine-a", connection(uri)), uri)
        signedInAt(uri)

        assertNull(book.reprobe(uri))
        val requestsAfterFirstFailure = server.requestCount

        // Inside the cooldown: a second reprobe must not issue another
        // request at all. A wall-clock assertion here would pass even with the
        // cooldown deleted, since two connection-refused-style failures are
        // fast regardless -- counting requests is what actually distinguishes
        // "raced again" from "did not".
        now += 1_000L
        assertNull(book.reprobe(uri))
        assertEquals("should not have raced again", requestsAfterFirstFailure, server.requestCount)

        server.shutdown()
    }

    @Test
    fun anExhaustedStoredListEscalatesToAFreshPlexTvList() = runTest {
        // The only leg of reprobe that wasn't covered before this test: every
        // stored candidate is dead, so it asks plex.tv for a fresh list and
        // adopts whichever address in that fresh list answers.
        val live = liveServer()
        val liveUri = live.url("/").toString().trimEnd('/')
        val storedDead = deadUri()
        val freshDeadHttps = deadHttpsUri()

        val freshResource = mediaServerResource(
            "machine-a",
            connection(freshDeadHttps),
            connection(liveUri)
        )
        val authClient = mock<AuthClient>().stub {
            onBlocking { getResources() } doReturn listOf(freshResource).right()
        }

        val book = ServerAddressBook.newForTest(api, authClient = authClient)
        book.adopt(resource("machine-a", connection(storedDead)), storedDead)
        signedInAt(storedDead)

        val recovered = book.reprobe(storedDead)

        assertEquals(liveUri, recovered)
        assertEquals("winner persisted", liveUri, api.serverUri)
        assertEquals(
            "the fresh list replaces the stale stored one",
            listOf(freshDeadHttps, liveUri),
            book.storedCandidates("machine-a")?.direct
        )
        live.shutdown()
    }

    @Test
    fun aRaceInFlightDoesNotResurrectASignedOutSession() = runTest {
        // The hazard: reprobe captures the session, spends real time racing,
        // then writes the captured session back. If sign-out lands in that
        // window -- exactly when a user would, since the "sign in again"
        // affordance appears while browse is failing -- the finishing race
        // must not undo it and bring the account token back.
        signedInAt("https://lan.example")

        val probe = mock<ServerProbe>().stub {
            onBlocking { bestOf(any()) } doAnswer {
                // Simulates sign-out landing mid-race. Deterministic, no
                // sleeps: the stub itself mutates state before "returning" a
                // winner, rather than a real race that might or might not
                // lose to a background sign-out under a timer.
                api.session = null
                api.accountToken = null
                "https://public.example"
            }
        }
        val book = ServerAddressBook.newForTest(api, probe = probe)
        book.adopt(resource("machine-a", connection("https://lan.example")), "https://lan.example")

        val recovered = book.reprobe("https://lan.example")

        assertNull("a race that started before sign-out must not write a session back", recovered)
        assertNull("session must stay cleared", api.session)
        assertNull("account token must not be resurrected", api.accountToken)
    }

    @Test
    fun aRaceInFlightDoesNotOverwriteASwitchToADifferentServer() = runTest {
        // The other half of the same guard: adoptAddress also refuses when
        // the re-read session's machineIdentifier differs from the one the
        // race was started against. The library picker switching to a
        // different server while a race for the old one is still in flight
        // must not have the finishing race write the old server's address
        // back over the server the user switched to.
        signedInAt("https://lan.example", machineIdentifier = "machine-a")

        val probe = mock<ServerProbe>().stub {
            onBlocking { bestOf(any()) } doAnswer {
                // Simulates the library picker landing on a different server
                // mid-race. Deterministic, no sleeps -- same technique as
                // aRaceInFlightDoesNotResurrectASignedOutSession above.
                api.session = PlexSession(
                    accountToken = "account-token",
                    serverUri = "https://other-server.example",
                    musicSectionKey = SectionKey("7"),
                    serverToken = "other-server-token",
                    machineIdentifier = "machine-b"
                )
                "https://public.example"
            }
        }
        val book = ServerAddressBook.newForTest(api, probe = probe)
        book.adopt(resource("machine-a", connection("https://lan.example")), "https://lan.example")

        val recovered = book.reprobe("https://lan.example")

        assertNull("a race started against the old server must not write over a switch", recovered)
        assertEquals(
            "the server the user switched to must keep its own address",
            "https://other-server.example",
            api.session?.serverUri
        )
        assertEquals("machine-b", api.session?.machineIdentifier)
    }

    @Test
    fun aCallIsRetriedOnceWhenTheAddressMoves() = runTest {
        val live = liveServer()
        val liveUri = live.url("/").toString().trimEnd('/')
        val dead = deadUri()

        val book = ServerAddressBook.newForTest(api)
        book.adopt(resource("machine-a", connection(dead), connection(liveUri)), dead)
        signedInAt(dead)

        var calls = 0
        val result = book.withAddressRecovery {
            calls++
            if (calls == 1) {
                PlexTransportFailure.Unreachable(PlexHost.Server).left()
            } else {
                "ok".right()
            }
        }

        assertEquals("ok", result.getOrNull())
        assertEquals("exactly one retry", 2, calls)
        live.shutdown()
    }

    @Test
    fun aCallIsNotRetriedWhenTheAddressCouldNotMove() = runTest {
        // Everything is dead, so the re-probe fails and the address is unchanged.
        // Retrying would buy a second full timeout and nothing else.
        val dead = deadUri()

        val book = ServerAddressBook.newForTest(api)
        book.adopt(resource("machine-a", connection(dead)), dead)
        signedInAt(dead)

        var calls = 0
        val result = book.withAddressRecovery {
            calls++
            PlexTransportFailure.Unreachable(PlexHost.Server).left()
        }

        assertEquals("no retry", 1, calls)
        assertTrue(result.isLeft())
    }

    @Test
    fun anHttpFailureDoesNotReprobe() = runTest {
        // 401 means the token stopped being accepted. The address is fine, and
        // re-probing would spend a race answering the wrong question.
        val dead = deadUri()
        val book = ServerAddressBook.newForTest(api)
        book.adopt(resource("machine-a", connection(dead)), dead)
        signedInAt(dead)

        var calls = 0
        val result = book.withAddressRecovery {
            calls++
            PlexTransportFailure.Http(PlexHost.Server, 401).left()
        }

        assertEquals(1, calls)
        assertTrue(result.isLeft())
    }

    @Test
    fun aPlexTvFailureDoesNotReprobeTheServer() = runTest {
        // Unreachable, but about plex.tv -- says nothing about the server address.
        val dead = deadUri()
        val book = ServerAddressBook.newForTest(api)
        book.adopt(resource("machine-a", connection(dead)), dead)
        signedInAt(dead)

        var calls = 0
        book.withAddressRecovery {
            calls++
            PlexTransportFailure.Unreachable(PlexHost.PlexTv).left()
        }

        assertEquals(1, calls)
    }
}
