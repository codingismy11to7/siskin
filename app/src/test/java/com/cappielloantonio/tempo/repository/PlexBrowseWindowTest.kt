package com.cappielloantonio.tempo.repository

import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.util.Constants
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlexBrowseWindowTest {
    private val fixture = PlexBrowseTestServer()

    @Before fun startServer() {
        fixture.start()
    }

    @After fun stopServer() {
        fixture.stop()
    }

    @Test
    fun shortTitlesAreLeftAlone() {
        assertEquals("Beck", PlexBrowseRepository.shortened("Beck"))
    }

    @Test
    fun aTitleExactlyAtTheLimitIsLeftAlone() {
        val exact = "1234567890123456" // 16 characters
        assertEquals(exact, PlexBrowseRepository.shortened(exact))
    }

    @Test
    fun longTitlesAreCutWithAnEllipsis() {
        // Both ends of a range must fit one row; the car truncates from the
        // right, which would cost the second title entirely.
        assertEquals("A State of Tran…", PlexBrowseRepository.shortened("A State of Trance Classics, Vol. 2"))
    }

    @Test
    fun theCutDoesNotLeaveATrailingSpaceBeforeTheEllipsis() {
        assertEquals("A State of the…", PlexBrowseRepository.shortened("A State of the Art Recording"))
    }

    /**
     * A listing body with `totalSize` and `size` set the way PMS sets them.
     * `type` defaults to "artist" for the artist-tab tests; the album tests
     * pass "album" so `itemsOf(body, TYPE_ALBUM)` actually finds the items.
     */
    private fun listing(
        totalSize: Int,
        vararg titles: String,
        type: String = "artist",
    ): String {
        val items =
            titles
                .mapIndexed { i, t ->
                    """{"ratingKey":"$i","type":"$type","title":"$t"}"""
                }.joinToString(",")
        return """{"MediaContainer":{"size":${titles.size},"totalSize":$totalSize,"offset":0,"Metadata":[$items]}}"""
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun aListingThatFitsIsReturnedFlatRatherThanWindowed() =
        runTest {
            // WINDOW_SIZE items or fewer: the tab keeps today's behaviour and never
            // pays for the window machinery.
            fixture.server.enqueue(ok(listing(totalSize = 3, "Aa", "Bb", "Cc")))

            val result =
                PlexBrowseRepository()
                    .getArtistWindows(Constants.ARTIST_WINDOW_ID, Constants.ARTIST_ID)
                    .get()

            assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
            assertEquals(listOf("[artistID]0", "[artistID]1", "[artistID]2"), result.value!!.map { it.mediaId })
            assertEquals(1, fixture.server.requestCount) // one request decided the shape
        }

    @Test
    fun anOversizedListingBecomesWindowRowsAtWindowSizeOffsets() =
        runTest {
            // 120 items at WINDOW_SIZE=50 is three windows: 0, 50, 100.
            fixture.server.enqueue(ok(listing(totalSize = 120, "Aardvark")))
            repeat(3) { fixture.server.enqueue(ok(listing(totalSize = 120, "Boundary$it"))) }

            val result =
                PlexBrowseRepository()
                    .getArtistWindows(Constants.ARTIST_WINDOW_ID, Constants.ARTIST_ID)
                    .get()

            assertEquals(
                listOf("[artistWindowID]0", "[artistWindowID]50", "[artistWindowID]100"),
                result.value!!.map { it.mediaId },
            )
            // One request decides the shape (it doubles as window 0's own content
            // and its "from" boundary title), plus one titleAt call per remaining
            // boundary (50, 100, and the final total-1=119) -- four total. A ceiling
            // that silently fetched an extra window, or a boundary fetched twice,
            // would still pass the id assertion above but show up here as a request
            // count that no longer matches.
            assertEquals(4, fixture.server.requestCount)
        }

    @Test
    fun anExactMultipleOfWindowSizeStillProducesATrueCeiling() =
        runTest {
            // total=120 above is ceil(120/50)=3, but so is the wrong formula
            // total/size+1 = 120/50+1 = 3 -- the two agree there and the test above
            // cannot tell them apart. total=100 is where they diverge: the true
            // ceiling is 2 windows (0, 50), while total/size+1 would claim 3.
            //
            // failFast rather than the default queue behaviour: an implementation
            // using the wrong formula requests a 4th response that was never
            // enqueued, and the default QueueDispatcher blocks on take() waiting
            // for one -- which would surface as this test hanging on .get() forever
            // rather than as a clean assertion failure.
            fixture.server.dispatcher = QueueDispatcher().apply { setFailFast(true) }
            fixture.server.enqueue(ok(listing(totalSize = 100, "Aardvark")))
            repeat(2) { fixture.server.enqueue(ok(listing(totalSize = 100, "Boundary$it"))) }

            val result =
                PlexBrowseRepository()
                    .getArtistWindows(Constants.ARTIST_WINDOW_ID, Constants.ARTIST_ID)
                    .get()

            assertEquals(
                listOf("[artistWindowID]0", "[artistWindowID]50"),
                result.value!!.map { it.mediaId },
            )
        }

    @Test
    fun eachWindowIsLabelledWithItsOwnAndTheNextWindowsBoundaryTitle() =
        runTest {
            // The label pairing is the arithmetic most likely to be subtly wrong:
            // each window is labelled with its own first title and the NEXT
            // window's first title, and the final window's label ends at the last
            // item's title (boundary index total-1=119, not 100+50=150).
            //
            // Boundary requests are issued concurrently via async, so MockWebServer's
            // default in-order queue cannot pin which enqueued body answers which
            // request -- a Dispatcher keyed on the offset a request actually asks
            // for (X-Plex-Container-Start) is what makes this deterministic. Every
            // title here is <=16 chars so `shortened` cannot truncate any of them
            // and the label assertions below can compare against the raw titles.
            val boundaryTitles =
                mapOf(
                    0 to "Aardvark",
                    50 to "Marigold",
                    100 to "Sunflower",
                    119 to "Zzyzx",
                )
            fixture.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val start = request.getHeader("X-Plex-Container-Start")!!.toInt()
                        return ok(listing(totalSize = 120, boundaryTitles.getValue(start)))
                    }
                }

            val result =
                PlexBrowseRepository()
                    .getArtistWindows(Constants.ARTIST_WINDOW_ID, Constants.ARTIST_ID)
                    .get()

            assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
            assertEquals(3, result.value!!.size)
            assertEquals("Aardvark  -  Marigold", result.value!![0].mediaMetadata.title)
            assertEquals("Sunflower  -  Zzyzx", result.value!![2].mediaMetadata.title)
        }

    @Test
    fun aWindowFetchesExactlyItsOwnSliceWithTheSameSort() =
        runTest {
            // The window id is an offset into the sorted listing, so start, size and
            // sort must all match what the window list was built from -- a mismatch
            // points every window at the wrong slice.
            //
            // Asserted via the parsed query parameter rather than a substring check:
            // Plex's own "titleSort" value contains "sort=title" as a substring, so
            // a plain `path.contains("sort=title")` cannot fail if the production
            // code is switched to the wrong sort. queryParameter reads the whole
            // value, so "title" and "titleSort" cannot be confused.
            fixture.server.enqueue(ok(listing(totalSize = 120, "Fifty")))

            PlexBrowseRepository().getArtistWindow(50, Constants.ARTIST_ID).get()

            val request = fixture.server.takeRequest()
            assertEquals("50", request.getHeader("X-Plex-Container-Start"))
            assertEquals("50", request.getHeader("X-Plex-Container-Size"))
            assertEquals("title", request.requestUrl!!.queryParameter("sort"))
        }

    @Test
    fun anAlbumWindowFetchesItsSliceWithTheSameSort() =
        runTest {
            // The Albums tab windows exactly the same way as Artists -- this is the
            // only test in the file that goes through getAlbumWindow at all, and it
            // uses the same discriminating sort assertion as the artist window test
            // above.
            fixture.server.enqueue(ok(listing(totalSize = 120, "Fifty", type = "album")))

            PlexBrowseRepository().getAlbumWindow(50, Constants.ALBUM_ID).get()

            val request = fixture.server.takeRequest()
            assertEquals("50", request.getHeader("X-Plex-Container-Start"))
            assertEquals("50", request.getHeader("X-Plex-Container-Size"))
            assertEquals("title", request.requestUrl!!.queryParameter("sort"))
        }

    @Test
    fun aFailedFirstRequestReturnsPermissionDeniedForA401() =
        runTest {
            // The Artists tab's own path to the "sign in again" affordance. windowed()
            // used to hand-copy resultFor's HTTP routing and this test guarded that
            // copy; the copy is gone, and this now pins that the tab still reaches
            // the shared decider through fetch() rather than losing the affordance on
            // the way.
            fixture.server.enqueue(MockResponse().setResponseCode(401))

            val result =
                PlexBrowseRepository()
                    .getArtistWindows(Constants.ARTIST_WINDOW_ID, Constants.ARTIST_ID)
                    .get()

            assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        }

    @Test
    fun aWindowWhoseBoundaryTitleCouldNotBeFetchedFallsBackToItsPosition() =
        runTest {
            // titleAt returns null rather than raising -- it runs inside async, and a
            // raise across a coroutine-builder boundary is forbidden here. Losing a
            // label must not fail the whole tab.
            fixture.server.enqueue(ok(listing(totalSize = 120, "Aardvark")))
            repeat(3) { fixture.server.enqueue(MockResponse().setResponseCode(500)) }

            val result =
                PlexBrowseRepository()
                    .getArtistWindows(Constants.ARTIST_WINDOW_ID, Constants.ARTIST_ID)
                    .get()

            assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
            assertEquals(3, result.value!!.size)
            assertEquals("1 - 50", result.value!![0].mediaMetadata.title)
        }
}
