package com.cappielloantonio.tempo.plex.api.library

import com.cappielloantonio.tempo.plex.api.annotatedEndpoints
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            artistId = "15100",
            trackDecade = null,
            albumDecade = null
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
    fun getDecadesAsksTheSectionsDecadeIndexForAlbums() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getDecades(sectionId = "4", type = 9)

        val request = server.takeRequest()
        assertEquals("/library/sections/4/decade", request.requestUrl?.encodedPath)
        // type=9 (album) is the only type with a decade filter at all. Measured
        // against PMS 1.43.3: filters?type=10 lists mood, genre, userRating and
        // audioCodec, and nothing else.
        assertEquals("9", request.requestUrl?.queryParameter("type"))
    }

    @Test
    fun getSectionContentFiltersTracksOnAlbumDecadeNotDecade() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getSectionContent(
            sectionId = "4",
            type = 10,
            start = 0,
            size = 500,
            sort = "random",
            artistId = null,
            trackDecade = "1980",
            albumDecade = null
        )

        val request = server.takeRequest()
        // album.decade, with the dot. Measured against PMS 1.43.3: a bare
        // `decade=1980` on type=10 answers 200 with ZERO results rather than
        // erroring, so the misspelling renders an empty list and reads as an
        // empty library. `year=1985` fails the same way.
        assertEquals("1980", request.requestUrl?.queryParameter("album.decade"))
        assertNull(request.requestUrl?.queryParameter("decade"))
        assertEquals("random", request.requestUrl?.queryParameter("sort"))
    }

    @Test
    fun getSectionContentFiltersAlbumsOnDecadeNotAlbumDecade() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getSectionContent(
            sectionId = "4",
            type = 9,
            start = 0,
            size = 8,
            sort = "random",
            artistId = null,
            trackDecade = null,
            albumDecade = "1980"
        )

        val request = server.takeRequest()
        // A bare `decade`, no dot. An album's decade is its own field, unlike a
        // track's, which belongs to the parent album -- the server says so itself
        // in the fastKey it returns on every decade entry:
        // /library/sections/4/all?decade=1980&type=9
        assertEquals("1980", request.requestUrl?.queryParameter("decade"))
        assertNull(request.requestUrl?.queryParameter("album.decade"))
        assertEquals("9", request.requestUrl?.queryParameter("type"))
        assertEquals("random", request.requestUrl?.queryParameter("sort"))
        assertEquals("8", request.getHeader("X-Plex-Container-Size"))
    }

    @Test
    fun getFirstCharactersAsksForArtistsAndTakesNoPaging() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{"size":0}}"""))

        service().getFirstCharacters(sectionId = "4", type = 8)

        val request = server.takeRequest()
        assertEquals("/library/sections/4/firstCharacter", request.requestUrl?.encodedPath)
        assertEquals("8", request.requestUrl?.queryParameter("type"))
        // Bounded by the number of distinct initials in a library, like the
        // decade index -- so no Start/Size, and their absence is asserted rather
        // than assumed.
        assertNull(request.getHeader("X-Plex-Container-Start"))
        assertNull(request.getHeader("X-Plex-Container-Size"))
    }

    @Test
    fun aBucketKeyThatArrivedPercentEncodedIsNotEncodedASecondTime() = runTest {
        // The server hands back key="%23" for the symbol bucket. Re-encoding it
        // yields %2523, which is a different bucket that does not exist -- and
        // the failure is a silently empty list, not an error. pathSegments
        // decodes, so this asserts the server sees a segment meaning "#".
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{"size":0}}"""))

        service().getFirstCharacterContent(sectionId = "4", key = "%23", type = 8, start = 0, size = 500)

        val request = server.takeRequest()
        assertEquals("#", request.requestUrl!!.pathSegments.last())
        assertFalse(request.path!!.contains("%2523"))
    }

    @Test
    fun aNonAsciiBucketKeySurvivesTheRoundTrip() = runTest {
        // A real library produces these: the reference library has a "∆" bucket
        // holding one artist. It arrives unencoded, unlike "%23".
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{"size":0}}"""))

        service().getFirstCharacterContent(sectionId = "4", key = "∆", type = 8, start = 0, size = 500)

        assertEquals("∆", server.takeRequest().requestUrl!!.pathSegments.last())
    }

    @Test
    fun aBucketListingCarriesPagingAndNoSort() = runTest {
        // No sort on purpose. Bucket membership is decided by titleSort, so
        // sort=title opens bucket D on "Arne Domnérus, Bob Dylan, Brigitte
        // DeMeyer" -- a list under a heading reading D that starts A, B, B.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{"size":0}}"""))

        service().getFirstCharacterContent(sectionId = "4", key = "D", type = 8, start = 0, size = 500)

        val request = server.takeRequest()
        assertEquals("8", request.requestUrl!!.queryParameter("type"))
        assertNull(request.requestUrl!!.queryParameter("sort"))
        assertEquals("0", request.getHeader("X-Plex-Container-Start"))
        assertEquals("500", request.getHeader("X-Plex-Container-Size"))
    }

    @Test
    fun getFirstCharactersReportsEachBucketsCount() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"MediaContainer":{"size":2,"Directory":[
                   {"size":12,"key":"%23","title":"#"},{"size":79,"key":"A","title":"A"}]}}"""
            )
        )

        val response = service().getFirstCharacters(sectionId = "4", type = 8)

        val buckets = response.mediaContainer!!.directory!!
        assertEquals(listOf("#", "A"), buckets.map { it.title })
        assertEquals(listOf(12, 79), buckets.map { it.size })
    }

    @Test
    fun followsAHubKeyAsGivenIncludingItsComparisonOperators() = runTest {
        server.enqueue(MockResponse().setBody("{\"MediaContainer\":{\"size\":0}}"))

        service().getByPath(
            "/library/sections/7/all?type=8&viewCount>=50&lastViewedAt<=-5mon&sort=random"
        )

        val request = server.takeRequest()
        assertEquals("/library/sections/7/all", request.requestUrl?.encodedPath)
        assertEquals("50", request.requestUrl?.queryParameter("viewCount>"))
        assertEquals("-5mon", request.requestUrl?.queryParameter("lastViewedAt<"))
        assertEquals("random", request.requestUrl?.queryParameter("sort"))
    }

    @Test
    fun rejectsAHubKeyThatIsNotARelativePath() {
        assertFalse(LibraryClient.isSafeHubKey("https://elsewhere.example/library/sections/7/all"))
        assertFalse(LibraryClient.isSafeHubKey("//elsewhere.example/library/sections/7/all"))
        assertFalse(LibraryClient.isSafeHubKey("library/sections/7/all"))
        assertFalse(LibraryClient.isSafeHubKey(null))
        assertFalse(LibraryClient.isSafeHubKey("   "))
    }

    @Test
    fun acceptsAnOrdinaryHubKey() {
        assertTrue(LibraryClient.isSafeHubKey("/library/sections/7/all?type=9&genre=138884"))
        assertTrue(LibraryClient.isSafeHubKey("/hubs/sections/7/popular?monthsAgo=4"))
    }

    @Test
    fun rejectsAHubKeyThatWouldResolveOffHostViaABackslash() {
        // A hole the brief's cases miss: "/\evil.example/x" passes both
        // startsWith("/") and !startsWith("//") on its own, but OkHttp's
        // HttpUrl.resolve follows the WHATWG URL Standard's
        // backslash-as-slash normalisation, so a leading "/\" or "\\"
        // resolves exactly like "//" -- measured against this test's own
        // MockWebServer base URL, resolving "/\evil.example/x" answers
        // http://evil.example/x, a different host, with the account token
        // still attached by PlexRetrofitFactory's interceptor.
        assertFalse(LibraryClient.isSafeHubKey("/\\evil.example/library/sections/7/all"))
        assertFalse(LibraryClient.isSafeHubKey("\\\\evil.example/library/sections/7/all"))
        // A backslash anywhere is rejected, not just leading -- a real Plex
        // key never contains one, so there is nothing to allow.
        assertFalse(LibraryClient.isSafeHubKey("/library/sections/7\\evil.example/all"))
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
                "getSectionHubs",
                "getDecades",
                "getFirstCharacters",
                "getFirstCharacterContent",
                "getByPath"
            ),
            annotatedEndpoints(LibraryService::class.java)
        )
    }
}
