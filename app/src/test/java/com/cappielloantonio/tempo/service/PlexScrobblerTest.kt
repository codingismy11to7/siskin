package com.cappielloantonio.tempo.service

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.search.TimelineState
import com.cappielloantonio.tempo.util.PlexAccountFixture.aSession
import com.cappielloantonio.tempo.util.PlexAccountFixture.registerAuthenticator
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** Robolectric because PlexApi reads the app's preferences and the system account. */
@RunWith(RobolectricTestRunner::class)
class PlexScrobblerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        registerAuthenticator()
        server = MockWebServer()
        server.start()
        signIn()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun signIn(
        accountToken: String = "acct",
        at: MockWebServer = server,
        machineIdentifier: String = "machine-a",
        musicSectionKey: String = "4",
    ) {
        PlexApi().session =
            aSession(
                accountToken = accountToken,
                serverUri = at.url("/").toString(),
                musicSectionKey = musicSectionKey,
                serverToken = null,
                machineIdentifier = machineIdentifier,
            )
    }

    private fun report(
        ratingKey: String,
        state: TimelineState,
    ) = PlexScrobbler.report(ratingKey, "/library/parts/1", state, 0L, null)

    private fun nextState(server: MockWebServer = this.server) =
        server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("state")

    /** Holds the drain on a slow first send, so what follows is still queued when the session is written. */
    private fun holdTheQueue() {
        server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(1, TimeUnit.SECONDS))
        report("41", TimelineState.PAUSED)
        assertEquals("paused", nextState())
    }

    /** Sent together, a pause could land before the heartbeat ahead of it and leave the car "playing". */
    @Test
    fun aReportWaitsForTheOneBeforeIt() {
        server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(1, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setResponseCode(200))

        report("42", TimelineState.PLAYING)
        report("42", TimelineState.PAUSED)

        assertEquals("playing", nextState())
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals("paused", nextState())
    }

    /** Sent as the new member, it would open a Now Playing entry that nothing closes. */
    @Test
    fun aHeartbeatQueuedBeforeAHomeSwitchIsDropped() {
        holdTheQueue()
        server.enqueue(MockResponse().setResponseCode(200))
        report("42", TimelineState.PLAYING)
        report("42", TimelineState.STOPPED)
        signIn(accountToken = "member")

        val stopped = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("stopped", stopped.requestUrl!!.queryParameter("state"))
        assertEquals("acct", stopped.getHeader("X-Plex-Token"))
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aHeartbeatQueuedBeforeAServerSwitchIsDropped() {
        MockWebServer().use { other ->
            holdTheQueue()
            server.enqueue(MockResponse().setResponseCode(200))
            report("42", TimelineState.PLAYING)
            report("42", TimelineState.STOPPED)
            signIn(at = other, machineIdentifier = "machine-b")

            assertEquals("stopped", nextState())
            assertNull(other.takeRequest(500, TimeUnit.MILLISECONDS))
        }
    }

    /** Nothing stops playback for a re-probe, so nothing would follow a dropped pause and the car would show as playing. */
    @Test
    fun aPauseQueuedBeforeTheServerMovesAddressIsStillSent() {
        MockWebServer().use { moved ->
            holdTheQueue()
            moved.enqueue(MockResponse().setResponseCode(200))
            report("42", TimelineState.PAUSED)
            signIn(at = moved)

            assertEquals("paused", nextState(moved))
        }
    }

    @Test
    fun aPauseQueuedBeforeALibrarySwitchOnTheSameServerIsStillSent() {
        holdTheQueue()
        server.enqueue(MockResponse().setResponseCode(200))
        report("42", TimelineState.PAUSED)
        signIn(musicSectionKey = "9")

        assertEquals("paused", nextState())
    }

    @Test
    fun aStoppedReportKeepsTheServerCurrentWhenItWasMade() {
        MockWebServer().use { other ->
            holdTheQueue()
            server.enqueue(MockResponse().setResponseCode(200))
            report("42", TimelineState.STOPPED)
            signIn(at = other, machineIdentifier = "machine-b")

            assertEquals("stopped", nextState())
        }
    }

    @Test
    fun aServerChangeIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(200))
        report("42", TimelineState.PLAYING)
        assertEquals("playing", nextState())

        MockWebServer().use { other ->
            other.enqueue(MockResponse().setResponseCode(200))
            signIn(at = other, machineIdentifier = "machine-b")

            report("42", TimelineState.PAUSED)

            assertEquals("paused", nextState(other))
        }
    }
}
