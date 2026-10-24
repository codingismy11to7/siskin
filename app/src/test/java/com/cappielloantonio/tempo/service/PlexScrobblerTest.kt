package com.cappielloantonio.tempo.service

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.search.TimelineState
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

/** Robolectric because PlexApi reads the app's preferences. */
@RunWith(RobolectricTestRunner::class)
class PlexScrobblerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PlexApi().serverUri = server.url("/").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun report(
        ratingKey: String,
        state: TimelineState,
    ) = PlexScrobbler.report(ratingKey, "/library/parts/1", state, 0L, null)

    private fun nextState(server: MockWebServer = this.server) =
        server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("state")

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

    @Test
    fun aServerChangeIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(200))
        report("42", TimelineState.PLAYING)
        assertEquals("playing", nextState())

        MockWebServer().use { other ->
            other.enqueue(MockResponse().setResponseCode(200))
            PlexApi().serverUri = other.url("/").toString()

            report("42", TimelineState.PAUSED)

            assertEquals("paused", nextState(other))
        }
    }
}
