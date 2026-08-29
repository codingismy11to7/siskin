package com.cappielloantonio.tempo.plex.api.search

import com.cappielloantonio.tempo.plex.api.annotatedEndpoints
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * What SearchService actually puts on the wire.
 *
 * This interface holds both of the layer's genuinely surprising requests: two
 * writes Plex serves over GET, on paths that begin with a colon. Neither is
 * predictable from reading the annotation, and neither was stated anywhere
 * until these tests.
 */
class SearchServiceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service(): SearchService =
        Retrofit
            .Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SearchService::class.java)

    @Test
    fun searchIsScopedToOneSection() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            service().search(sectionId = "1", query = "wish you were here", type = 10, limit = 30)

            val request = server.takeRequest()
            assertEquals("/library/sections/1/search", request.requestUrl?.encodedPath)
            assertEquals("wish you were here", request.requestUrl?.queryParameter("query"))
            // type is required, not optional -- Plex answers 400 without it.
            assertEquals("10", request.requestUrl?.queryParameter("type"))
            assertEquals("30", request.requestUrl?.queryParameter("limit"))
        }

    @Test
    fun getPlaylistsFiltersOnSectionIdNotLibrarySectionId() =
        runTest {
            // The other bug this file exists for. librarySectionID is accepted with
            // 200 and silently ignored; sectionID is the one that filters. The
            // wrong name returns every playlist on the server and looks like it
            // worked. The assertNull below guards a different regression: a
            // future change that sends both parameter names defensively, which
            // the sectionID assertion alone would still pass.
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            service().getPlaylists(sectionId = "1")

            val request = server.takeRequest()
            assertEquals("/playlists", request.requestUrl?.encodedPath)
            assertEquals("1", request.requestUrl?.queryParameter("sectionID"))
            assertNull(request.requestUrl?.queryParameter("librarySectionID"))
            assertEquals("audio", request.requestUrl?.queryParameter("playlistType"))
        }

    @Test
    fun getPlaylistReadsAPlaylistsOwnMetadata() =
        runTest {
            // 561 bytes on a real server, and it answers all three things the
            // Mix decision needs: how many tracks, whether it is smart, and the
            // query it is defined by. A container probe answers the first two
            // only. See the 2026-08-28 mix paging design.
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            service().getPlaylist(playlistId = "169076")

            val request = server.takeRequest()
            assertEquals("/playlists/169076", request.requestUrl?.encodedPath)
        }

    @Test
    fun getPlaylistItemsReadsAPlaylistsTracks() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            service().getPlaylistItems(playlistId = "88", start = 0, size = 50)

            val request = server.takeRequest()
            assertEquals("/playlists/88/items", request.requestUrl?.encodedPath)
            assertEquals("0", request.getHeader("X-Plex-Container-Start"))
            assertEquals("50", request.getHeader("X-Plex-Container-Size"))
        }

    @Test
    fun reportProgressGetsTheColonTimelinePath() =
        runTest {
            // The least predictable request in the layer: a relative URL beginning
            // with a colon, resolving to /:/timeline. Nothing else states what it
            // resolves to. GET despite being a write, which is what Plex expects.
            server.enqueue(MockResponse().setResponseCode(200))

            service().reportProgress(
                ratingKey = "77",
                key = "/library/parts/1",
                state = "playing",
                timeMs = 1234L,
            )

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/:/timeline", request.requestUrl?.encodedPath)
            assertEquals("77", request.requestUrl?.queryParameter("ratingKey"))
            assertEquals("/library/parts/1", request.requestUrl?.queryParameter("key"))
            assertEquals("playing", request.requestUrl?.queryParameter("state"))
            assertEquals("1234", request.requestUrl?.queryParameter("time"))
        }

    @Test
    fun rateGetsTheColonRatePath() =
        runTest {
            // Same shape as :/timeline: a write served over GET on a colon path.
            server.enqueue(MockResponse().setResponseCode(200))

            service().rate(key = "77", identifier = "com.plexapp.plugins.library", rating = 10)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/:/rate", request.requestUrl?.encodedPath)
            assertEquals("77", request.requestUrl?.queryParameter("key"))
            assertEquals(
                "com.plexapp.plugins.library",
                request.requestUrl?.queryParameter("identifier"),
            )
            assertEquals("10", request.requestUrl?.queryParameter("rating"))
        }

    @Test
    fun everyEndpointIsCovered() {
        // Fails when an endpoint is added to SearchService without a test
        // above. The gap this file closes formed exactly that way.
        assertEquals(
            setOf("search", "getPlaylists", "getPlaylist", "getPlaylistItems", "reportProgress", "rate"),
            annotatedEndpoints(SearchService::class.java),
        )
    }
}
