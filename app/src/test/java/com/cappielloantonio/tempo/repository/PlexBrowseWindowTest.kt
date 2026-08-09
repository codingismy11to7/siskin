package com.cappielloantonio.tempo.repository

import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.util.ConstantsAA
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlexBrowseWindowTest {

    private val fixture = PlexBrowseTestServer()

    @Before fun startServer() { fixture.start() }
    @After fun stopServer() { fixture.stop() }

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

    /** A listing body with `totalSize` and `size` set the way PMS sets them. */
    private fun listing(totalSize: Int, vararg titles: String): String {
        val items = titles.mapIndexed { i, t ->
            """{"ratingKey":"$i","type":"artist","title":"$t"}"""
        }.joinToString(",")
        return """{"MediaContainer":{"size":${titles.size},"totalSize":$totalSize,"offset":0,"Metadata":[$items]}}"""
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun aListingThatFitsIsReturnedFlatRatherThanWindowed() = runTest {
        // WINDOW_SIZE items or fewer: the tab keeps today's behaviour and never
        // pays for the window machinery.
        fixture.server.enqueue(ok(listing(totalSize = 3, "Aa", "Bb", "Cc")))

        val result = PlexBrowseRepository()
            .getArtistWindows(ConstantsAA.ARTIST_WINDOW_ID, ConstantsAA.ARTIST_ID).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("[artistID]0", "[artistID]1", "[artistID]2"), result.value!!.map { it.mediaId })
        assertEquals(1, fixture.server.requestCount) // one request decided the shape
    }

    @Test
    fun anOversizedListingBecomesWindowRowsAtWindowSizeOffsets() = runTest {
        // 120 items at WINDOW_SIZE=50 is three windows: 0, 50, 100.
        fixture.server.enqueue(ok(listing(totalSize = 120, "Aardvark")))
        repeat(3) { fixture.server.enqueue(ok(listing(totalSize = 120, "Boundary$it"))) }

        val result = PlexBrowseRepository()
            .getArtistWindows(ConstantsAA.ARTIST_WINDOW_ID, ConstantsAA.ARTIST_ID).get()

        assertEquals(
            listOf("[artistWindowID]0", "[artistWindowID]50", "[artistWindowID]100"),
            result.value!!.map { it.mediaId }
        )
    }

    @Test
    fun aWindowFetchesExactlyItsOwnSliceWithTheSameSort() = runTest {
        // The window id is an offset into the sorted listing, so start, size and
        // sort must all match what the window list was built from -- a mismatch
        // points every window at the wrong slice.
        fixture.server.enqueue(ok(listing(totalSize = 120, "Fifty")))

        PlexBrowseRepository().getArtistWindow(50, ConstantsAA.ARTIST_ID).get()

        val request = fixture.server.takeRequest()
        assertEquals("50", request.getHeader("X-Plex-Container-Start"))
        assertEquals("50", request.getHeader("X-Plex-Container-Size"))
        assertTrue(request.path!!.contains("sort=title"))
    }

    @Test
    fun aWindowWhoseBoundaryTitleCouldNotBeFetchedFallsBackToItsPosition() = runTest {
        // titleAt returns null rather than raising -- it runs inside async, and a
        // raise across a coroutine-builder boundary is forbidden here. Losing a
        // label must not fail the whole tab.
        fixture.server.enqueue(ok(listing(totalSize = 120, "Aardvark")))
        repeat(3) { fixture.server.enqueue(MockResponse().setResponseCode(500)) }

        val result = PlexBrowseRepository()
            .getArtistWindows(ConstantsAA.ARTIST_WINDOW_ID, ConstantsAA.ARTIST_ID).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(3, result.value!!.size)
        assertEquals("1 - 50", result.value!![0].mediaMetadata.title)
    }
}
