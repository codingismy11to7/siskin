package com.cappielloantonio.tempo.plex.api.library

import com.cappielloantonio.tempo.plex.api.annotatedEndpoints
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * What LibraryService actually puts on the wire.
 *
 * A wrong path here fails open rather than loudly: Plex answers 200, the app
 * narrows the body to an empty list, and a browse tab renders empty. Both
 * mistakes this layer has actually made were found by probing a live server,
 * never by a test -- these assertions are what keep them found.
 */
class LibraryServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service(): LibraryService = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LibraryService::class.java)

    @Test
    fun getSectionsKeepsItsTrailingSlash() = runTest {
        // The interface's KDoc says the trailing slash is Plex's canonical form
        // and deliberate. This is what stops a later tidy-up from dropping it.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getSections()

        assertEquals("/library/sections/", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun getSectionContentAsksASectionForItsItems() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getSectionContent(
            sectionId = "1",
            type = 9,
            start = 0,
            size = 50,
            sort = "titleSort",
            artistId = "15100"
        )

        val request = server.takeRequest()
        assertEquals("/library/sections/1/all", request.requestUrl?.encodedPath)
        assertEquals("9", request.requestUrl?.queryParameter("type"))
        assertEquals("titleSort", request.requestUrl?.queryParameter("sort"))
        // artist.id, with the dot -- Plex's filter syntax, and the only way an
        // artist's albums come back at all, since the children endpoint drops
        // some of them.
        assertEquals("15100", request.requestUrl?.queryParameter("artist.id"))
        // Paging rides in headers here, not query parameters.
        assertEquals("0", request.getHeader("X-Plex-Container-Start"))
        assertEquals("50", request.getHeader("X-Plex-Container-Size"))
    }

    @Test
    fun getChildrenReadsAnAlbumsTracks() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getChildren(ratingKey = "77", start = 0, size = 50)

        val request = server.takeRequest()
        assertEquals("/library/metadata/77/children", request.requestUrl?.encodedPath)
        assertEquals("0", request.getHeader("X-Plex-Container-Start"))
        assertEquals("50", request.getHeader("X-Plex-Container-Size"))
    }

    @Test
    fun getNearestAsksForNearestNotSimilar() = runTest {
        // library/metadata/{id}/similar -- the path Plex's own web client uses
        // -- 404s against PMS 1.43.3. This one answers. That cost live probing
        // to find, and this assertion is what keeps it from being "corrected"
        // back to the name that looks right.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getNearest(ratingKey = "77", limit = 10)

        val request = server.takeRequest()
        assertEquals("/library/metadata/77/nearest", request.requestUrl?.encodedPath)
        assertEquals("10", request.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun getMetadataReadsOneItemByRatingKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getMetadata("77")

        assertEquals("/library/metadata/77", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun getSectionHubsReadsASectionsHubs() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getSectionHubs("1")

        assertEquals("/hubs/sections/1", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun everyEndpointIsCovered() {
        // Fails when an endpoint is added to LibraryService without a test
        // above. The gap this file closes formed exactly that way.
        assertEquals(
            setOf(
                "getSections",
                "getSectionContent",
                "getChildren",
                "getNearest",
                "getMetadata",
                "getSectionHubs"
            ),
            annotatedEndpoints(LibraryService::class.java)
        )
    }
}
