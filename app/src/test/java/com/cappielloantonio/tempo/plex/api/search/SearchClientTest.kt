package com.cappielloantonio.tempo.plex.api.search

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.RatingKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because PlexApi reads App.getInstance().preferences, which needs a live Context. */
@RunWith(RobolectricTestRunner::class)
class SearchClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun clientAgainstServer(): SearchClient {
        val api = PlexApi().apply { serverUri = server.url("/").toString() }
        return SearchClient(api, api.serverUri, api.serverToken)
    }

    /** Literal strings, not `wire`: the point is what Plex accepts, not what the enum says. */
    @Test
    fun everyTimelineStateReachesTheServerAsPlexSpellsIt() =
        runTest {
            val expected =
                mapOf(
                    TimelineState.PLAYING to "playing",
                    TimelineState.PAUSED to "paused",
                    TimelineState.STOPPED to "stopped",
                )
            assertEquals(expected.keys, TimelineState.entries.toSet())

            val client = clientAgainstServer()
            for ((state, wire) in expected) {
                server.enqueue(MockResponse().setResponseCode(200))
                client.reportProgress(RatingKey("42"), "/library/parts/99", state, 0L)
                assertEquals(wire, server.takeRequest().requestUrl?.queryParameter("state"))
            }
        }
}
