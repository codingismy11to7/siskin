package com.cappielloantonio.tempo.repository

import android.os.Looper
import androidx.media3.common.MediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
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
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * Continuous play is best effort, and these pin the part of that which is not
 * optional: the callback runs exactly once on every path.
 *
 * MediaManager.continuousPlay clears `continuousPlayIsRunning` from inside the
 * callback and nowhere else, so a path that returns without calling back leaves
 * the flag stuck true and Instant Mix dead for the rest of the process -- a
 * failure with no exception, no log line and no way back short of a restart.
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

    /**
     * Waits for the callback, so a path that never calls back fails rather than
     * hangs.
     *
     * The repository delivers on Dispatchers.Main, because MediaManager's
     * callback drives a MediaBrowser. Robolectric runs this test body on that
     * same thread with a paused looper, so the looper has to be drained by hand
     * -- a plain latch.await() would deadlock against the delivery it is
     * waiting for.
     */
    private fun collect(request: (PlexMixRepository.TracksCallback) -> Unit): Pair<List<MediaItem>, Int> {
        val calls = AtomicInteger(0)
        var tracks: List<MediaItem> = listOf(MediaItem.EMPTY)

        request(PlexMixRepository.TracksCallback {
            tracks = it
            calls.incrementAndGet()
        })

        val deadline = System.currentTimeMillis() + 10_000
        while (calls.get() == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("callback never ran", calls.get() > 0)

        // Give a duplicate delivery a chance to land before counting.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return tracks to calls.get()
    }

    @Test
    fun tracksAreDeliveredOnTheApplicationThread() {
        // MediaManager.continuousPlay's callback reads and mutates a
        // MediaBrowser, and a MediaController rejects access from any thread but
        // the one it was built on. Retrofit's Android platform delivered
        // Call.enqueue callbacks through a main-thread executor; `suspend`
        // resumes wherever the launching scope says instead, so delivering from
        // IO here crashes continuous play.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{}}"""))
        var deliveredOn: Thread? = null

        collect { callback ->
            PlexMixRepository().similarTracks("5", 25, {
                deliveredOn = Thread.currentThread()
                callback.onTracks(it)
            })
        }

        assertEquals(Looper.getMainLooper().thread, deliveredOn)
    }

    @Test
    fun similarTracksDeliversTheTracksFromA200() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"MediaContainer":{"Metadata":[
                    {"ratingKey":"11","type":"track","title":"One"},
                    {"ratingKey":"22","type":"track","title":"Two"}
                ]}}"""
            )
        )

        val (tracks, calls) = collect { PlexMixRepository().similarTracks("5", 25, it) }

        assertEquals(listOf("11", "22"), tracks.map { it.mediaId })
        assertEquals(1, calls)
    }

    @Test
    fun similarTracksDeliversNoTracksForAnEmptyContainer() {
        // The ordinary outcome on a library without Plex Pass sonic analysis:
        // 200 with an empty container, never an error. The caller reads the
        // empty list as "fall through to random".
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{}}"""))

        val (tracks, calls) = collect { PlexMixRepository().similarTracks("5", 25, it) }

        assertTrue(tracks.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun anHttpFailureStillCallsBackWithNoTracks() {
        // Under Call<T> a 500 was an unsuccessful Response; under suspend it is
        // a thrown HttpException. Uncaught, it escapes the coroutine and the
        // callback never runs.
        server.enqueue(MockResponse().setResponseCode(500))

        val (tracks, calls) = collect { PlexMixRepository().similarTracks("5", 25, it) }

        assertTrue(tracks.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun anUnreachableServerStillCallsBackWithNoTracks() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val (tracks, calls) = collect { PlexMixRepository().similarTracks("5", 25, it) }

        assertTrue(tracks.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun randomTracksWithNoSectionSelectedCallsBackWithoutReachingTheServer() {
        val (tracks, calls) = collect { PlexMixRepository().randomTracks(25, it) }

        assertTrue(tracks.isEmpty())
        assertEquals(1, calls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun randomTracksDeliversTheTracksFromA200() {
        PlexApi().musicSectionKey = "1"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"MediaContainer":{"Metadata":[{"ratingKey":"33","type":"track"}]}}""")
        )

        val (tracks, calls) = collect { PlexMixRepository().randomTracks(25, it) }

        assertEquals(listOf("33"), tracks.map { it.mediaId })
        assertEquals(1, calls)
    }

    // ── address recovery: deliver wired to ServerAddressBook.shared ─────
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
    private fun liveServer(body: String) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
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
        val live = liveServer(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"11","type":"track","title":"One"}]}}"""
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
                connections = listOf(
                    Connection().apply { uri = dead },
                    Connection().apply { uri = liveUri }
                )
            },
            dead
        )

        val (tracks, calls) = collect { PlexMixRepository().similarTracks("5", 25, it) }

        assertEquals(listOf("11"), tracks.map { it.mediaId })
        assertEquals(1, calls)
        assertEquals(
            "the re-probe must move the session onto the address that answered",
            liveUri,
            PlexApi().serverUri
        )
        live.shutdown()
    }
}
