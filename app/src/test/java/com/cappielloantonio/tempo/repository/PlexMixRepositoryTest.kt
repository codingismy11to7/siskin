package com.cappielloantonio.tempo.repository

import androidx.media3.common.MediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Continuous play is best effort: every failure comes back as no tracks rather
 * than an exception.
 *
 * Robolectric because PlexMixRepository holds a PlexApi, which reads
 * App.getInstance().preferences.
 */
@RunWith(RobolectricTestRunner::class)
class PlexMixRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        // App caches SharedPreferences in a static field Robolectric does not
        // reset between methods, so every key is set explicitly. accountToken is
        // needed here too: randomTracks now reads the section key through
        // PlexApi.session, which -- being all-or-nothing -- only reports the key
        // a test sets below once the account token and server URI are also present.
        // machineIdentifier and serverCandidates are reset for the same reason --
        // a value left over from an address-recovery test would let a later
        // test's request reach ServerAddressBook.shared's re-probe path and race
        // real addresses instead of hitting the mock server it thinks it is
        // talking to.
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = server.url("/").toString()
            musicSectionKey = null
            machineIdentifier = null
            serverCandidates = null
        }
        // ServerAddressBook.shared is the real production singleton -- there is
        // exactly one, by design -- so its failure-cooldown clock is shared with
        // every other test in this run, not just this class. Nothing else can
        // reach the private field that holds it, so this is the reset.
        ServerAddressBook.shared.resetForTest()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    /** Bounded, so a request that never completes fails rather than hangs the suite. */
    private fun collect(request: suspend PlexMixRepository.() -> List<MediaItem>): List<MediaItem> =
        runBlocking { withTimeout(10_000) { PlexMixRepository().request() } }

    @Test
    fun similarTracksDeliversTheTracksFromA200() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"MediaContainer":{"Metadata":[
                    {"ratingKey":"11","type":"track","title":"One"},
                    {"ratingKey":"22","type":"track","title":"Two"}
                ]}}""",
            ),
        )

        val tracks = collect { similarTracks("5", 25) }

        assertEquals(listOf("11", "22"), tracks.map { it.mediaId })
    }

    @Test
    fun similarTracksDeliversNoTracksForAnEmptyContainer() {
        // The ordinary outcome on a library without Plex Pass sonic analysis:
        // 200 with an empty container, never an error. The caller reads the
        // empty list as "fall through to random".
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{}}"""))

        val tracks = collect { similarTracks("5", 25) }

        assertTrue(tracks.isEmpty())
    }

    @Test
    fun anHttpFailureReturnsNoTracks() {
        server.enqueue(MockResponse().setResponseCode(500))

        val tracks = collect { similarTracks("5", 25) }

        assertTrue(tracks.isEmpty())
    }

    @Test
    fun anUnreachableServerReturnsNoTracks() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val tracks = collect { similarTracks("5", 25) }

        assertTrue(tracks.isEmpty())
    }

    @Test
    fun randomTracksWithNoSectionSelectedReturnsWithoutReachingTheServer() {
        val tracks = collect { randomTracks(25) }

        assertTrue(tracks.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun randomTracksDeliversTheTracksFromA200() {
        PlexApi().musicSectionKey = "1"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"MediaContainer":{"Metadata":[{"ratingKey":"33","type":"track"}]}}"""),
        )

        val tracks = collect { randomTracks(25) }

        assertEquals(listOf("33"), tracks.map { it.mediaId })
    }

    // ── address recovery: wired to ServerAddressBook.shared ─────
    //
    // Proves both the wiring (Important 2) and Critical 1 together: a mix
    // request against a session's stored address that has gone stale
    // re-probes through the real ServerAddressBook.shared and retries once
    // against whichever stored candidate answers. That retry only reaches the
    // live server because `libraryClient` is a `get()` that re-reads the
    // session on every access -- were it a `val` captured at construction
    // (Critical 1), the retry would replay against the dead address the first
    // attempt just failed on and this test would come back with no tracks.

    /** A port with nothing listening: connection refused, the fastest failure. */
    private fun deadUri(): String {
        val dead = MockWebServer()
        dead.start()
        val uri = dead.url("/").toString().trimEnd('/')
        dead.shutdown()
        return uri
    }

    /** Answers /identity like a reachable Plex server, and everything else with [body]. */
    private fun liveServer(body: String) =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        if (request.requestUrl?.encodedPath == "/identity") {
                            MockResponse().setResponseCode(200)
                        } else {
                            MockResponse().setResponseCode(200).setBody(body)
                        }
                }
            start()
        }

    @Test
    fun similarTracksRecoversWhenTheStoredAddressDiesButAnotherStillAnswers() {
        val dead = deadUri()
        val live =
            liveServer(
                """{"MediaContainer":{"Metadata":[{"ratingKey":"11","type":"track","title":"One"}]}}""",
            )
        val liveUri = live.url("/").toString().trimEnd('/')

        PlexApi().apply {
            accountToken = "account-token"
            serverUri = dead
            musicSectionKey = "1"
            machineIdentifier = "machine-a"
        }
        ServerAddressBook.shared.adopt(
            Resource().apply {
                clientIdentifier = "machine-a"
                connections =
                    listOf(
                        Connection().apply { uri = dead },
                        Connection().apply { uri = liveUri },
                    )
            },
            dead,
        )

        val tracks = collect { similarTracks("5", 25) }

        assertEquals(listOf("11"), tracks.map { it.mediaId })
        assertEquals(
            "the re-probe must move the session onto the address that answered",
            liveUri,
            PlexApi().serverUri,
        )
        live.shutdown()
    }
}
