package com.cappielloantonio.tempo.repository

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Metadata
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

// The resolved okhttp3 version only exposes ResponseBody.create(MediaType?, String)
// as deprecated in favour of an extension function this alpha release doesn't
// yet publish; the deprecated overload is otherwise exactly what's needed here.
//
// Robolectric rather than plain JUnit: the tests below construct a real
// PlexBrowseRepository, and PlexApi (which it holds) reads
// App.getInstance().preferences -- a live Context, which only exists under
// Robolectric. The pure-function tests do not touch Android at all and behave
// identically either way.
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class PlexBrowseRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        // Points the repository's Retrofit base URL at the mock server. It reads
        // this back through PlexApi on every call, so it must be set before the
        // first request rather than injected.
        //
        // musicSectionKey is reset explicitly rather than assumed absent: App
        // caches the SharedPreferences in a static field that Robolectric does
        // not reset between methods, so a key written by one test is otherwise
        // visible to the next.
        PlexApi().apply {
            serverUri = server.url("/").toString()
            musicSectionKey = null
        }
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun response(vararg items: Metadata) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply { metadata = items.toList() }
    }

    private fun item(ratingKey: String?, type: String) = Metadata().apply {
        this.ratingKey = ratingKey
        this.type = type
    }

    private fun httpException(code: Int) =
        HttpException(Response.error<PlexResponse>(code, ResponseBody.create(null, "")))

    /**
     * A stand-in for the real `getPlaylists`/`getArtists`/... map lambdas:
     * narrow with [PlexBrowseRepository.tracksOf] like a real caller would,
     * then blow up on anything that survives the narrowing. `resultFor`
     * always invokes this on a successful response, even when the eventual
     * list is empty -- only the per-item builder inside it is skipped, so a
     * lambda that explodes unconditionally would fail against the real
     * production lambdas too and would prove nothing.
     */
    private val trackMapThatMustNotBuildAnyItem: (PlexResponse) -> List<MediaItem> =
        { body -> PlexBrowseRepository.tracksOf(body).map { error("built a MediaItem from $it, but the narrowed list should have been empty") } }

    /** For the error branch, where `resultFor` must not call `map` at all. */
    private val mapThatMustNotRun: (PlexResponse) -> List<MediaItem> =
        { error("map must not run when the request failed") }

    @Test
    fun tracksOfKeepsOnlyTracks() {
        // A playlist or album listing can carry non-track entries; anything the
        // player cannot stream must not reach the queue.
        val tracks = PlexBrowseRepository.tracksOf(
            response(item("1", "track"), item("2", "album"), item("3", "track"))
        )
        assertEquals(listOf("1", "3"), tracks.map { it.ratingKey })
    }

    @Test
    fun tracksOfDropsEntriesWithoutARatingKey() {
        val tracks = PlexBrowseRepository.tracksOf(
            response(item(null, "track"), item("", "track"), item("3", "track"))
        )
        assertEquals(listOf("3"), tracks.map { it.ratingKey })
    }

    @Test
    fun tracksOfReturnsEmptyForAnAbsentOrEmptyContainer() {
        // A Plex library with no matching items answers 200 with an absent
        // Metadata list. That is an empty tab, not an error -- the Subsonic
        // implementation got this wrong for playlists and showed "Something
        // went wrong" on the first tab for anyone with no playlists.
        assertTrue(PlexBrowseRepository.tracksOf(null).isEmpty())
        assertTrue(PlexBrowseRepository.tracksOf(PlexResponse()).isEmpty())
        assertTrue(PlexBrowseRepository.tracksOf(response()).isEmpty())
    }

    @Test
    fun itemsOfNarrowsToTheRequestedType() {
        val albums = PlexBrowseRepository.itemsOf(
            response(item("1", "album"), item("2", "artist"), item("3", "album")),
            "album"
        )
        assertEquals(listOf("1", "3"), albums.map { it.ratingKey })
    }

    @Test
    fun itemsOfReturnsEmptyForAnAbsentContainer() {
        assertTrue(PlexBrowseRepository.itemsOf(null, "album").isEmpty())
        assertTrue(PlexBrowseRepository.itemsOf(PlexResponse(), "album").isEmpty())
    }

    // ── resultFor: the fetch() outcome→LibraryResult decision ─────────
    //
    // The service methods are `suspend`, so a non-2xx no longer arrives as an
    // unsuccessful Response -- Retrofit throws HttpException instead, and a
    // transport failure throws IOException. These drive resultFor with a stub
    // request that throws exactly what Retrofit would.

    @Test
    fun resultForAnEmptyLibrarySucceedsWithAnEmptyListRatherThanErroring() = runTest {
        // The regression this whole task exists to catch: Plex answers a
        // no-match listing with HTTP 200, MediaContainer present, Metadata
        // absent. The Subsonic implementation this replaces mistook that shape
        // for a failure and showed "Something went wrong" on the first of
        // three browse tabs for every user with no playlists.
        val result = PlexBrowseRepository.resultFor(
            { PlexResponse().apply { mediaContainer = MediaContainer() } },
            trackMapThatMustNotBuildAnyItem
        )

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.isEmpty())
    }

    @Test
    fun resultForHttp401IsPermissionDenied() = runTest {
        // 401/403 must map to ERROR_PERMISSION_DENIED specifically:
        // MediaLibraryServiceCallback.classifyFailure keys the "sign in again"
        // affordance off exactly that code.
        val result = PlexBrowseRepository.resultFor({ throw httpException(401) }, mapThatMustNotRun)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun resultForHttp403IsPermissionDenied() = runTest {
        val result = PlexBrowseRepository.resultFor({ throw httpException(403) }, mapThatMustNotRun)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun resultForHttp500IsBadValue() = runTest {
        // Any other non-2xx code must not collapse into ERROR_PERMISSION_DENIED,
        // which would tell the user their credentials expired because the
        // server has a bug.
        val result = PlexBrowseRepository.resultFor({ throw httpException(500) }, mapThatMustNotRun)

        assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
    }

    @Test
    fun resultForLetsATransportFailureEscapeRatherThanTurningItIntoAnError() {
        // The distinction the catch in resultFor has to keep: an unreachable
        // server is not a rejected one. Widening that catch to Throwable would
        // swallow this into a LibraryResult error, and the browse callback
        // cannot tell "no network" from "no permission" once it is one.
        val boom = IOException("no route to host")

        val thrown = assertThrows(IOException::class.java) {
            runTest { PlexBrowseRepository.resultFor({ throw boom }, mapThatMustNotRun) }
        }

        assertEquals(boom, thrown)
    }

    // ── end to end, through real Retrofit ───────────────────────────
    //
    // These drive the public future-returning API against a socket, so they
    // cover the whole bridge: the coroutine launch, the HttpException that
    // Retrofit raises for a non-2xx, and the SettableFuture completion. The
    // seam tests above cannot see any of that.

    private fun tracksBody(vararg ratingKeys: String) = """
        {"MediaContainer":{"Metadata":[${
        ratingKeys.joinToString(",") { """{"ratingKey":"$it","type":"track","title":"Track $it"}""" }
    }]}}
    """.trimIndent()

    /** Bounded so a bridge that never completes its future fails instead of hanging. */
    private fun await(future: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>) =
        future.get(10, TimeUnit.SECONDS)

    @Test
    fun a200ListingBecomesASuccessfulItemList() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11", "22")))

        val result = await(PlexBrowseRepository().getAlbumTracks("5"))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("11", "22"), result.value!!.map { it.mediaId })
    }

    @Test
    fun a401BecomesPermissionDeniedRatherThanAnExceptionallyCompletedFuture() {
        // The port's central hazard: with Call<T> a 401 arrived as a Response
        // with isSuccessful == false, and with suspend it arrives as a thrown
        // HttpException. A bridge that lets it escape completes the future
        // exceptionally, classifyFailure never sees ERROR_PERMISSION_DENIED,
        // and the car loses the "sign in again" button entirely.
        server.enqueue(MockResponse().setResponseCode(401))

        val result = await(PlexBrowseRepository().getAlbumTracks("5"))

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun a500BecomesBadValue() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = await(PlexBrowseRepository().getAlbumTracks("5"))

        assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
    }

    @Test
    fun anUnreachableServerCompletesTheFutureExceptionally() {
        // The other half of the distinction: a dropped connection must *not*
        // arrive as a LibraryResult error, or the car offers to re-authenticate
        // a user whose credentials are fine.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val thrown = assertThrows(ExecutionException::class.java) {
            await(PlexBrowseRepository().getAlbumTracks("5"))
        }

        assertTrue("cause was ${thrown.cause}", thrown.cause is IOException)
    }

    @Test
    fun a200WithNoBodyAtAllCompletesTheFutureExceptionally() {
        // Not a documented Plex response, but pinned because the suspend port
        // moved it: the service methods declare a non-null PlexResponse, so
        // Retrofit raises rather than handing back null, and this now reads as
        // unreachable instead of as an empty tab. If Plex ever does answer this
        // way on a real endpoint, this test is the record of what the user sees.
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val thrown = assertThrows(ExecutionException::class.java) {
            await(PlexBrowseRepository().getAlbumTracks("5"))
        }

        assertTrue("cause was ${thrown.cause}", thrown.cause !is HttpException)
    }

    /**
     * Answers by the `type` the request asked for rather than by arrival order,
     * so a merge that mismatched a tier's response would show up as the wrong
     * mediaId prefix instead of silently passing.
     */
    private fun searchDispatcher(failingType: Int? = null) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val type = request.requestUrl?.queryParameter("type")?.toInt()
            if (type == failingType) return MockResponse().setResponseCode(500)
            val (name, key) = when (type) {
                PlexItemType.ARTIST -> "artist" to "a1"
                PlexItemType.ALBUM -> "album" to "b1"
                else -> "track" to "t1"
            }
            return MockResponse().setResponseCode(200).setBody(typedBody(name, key))
        }
    }

    private fun requestedTypes() = (0 until server.requestCount).map {
        server.takeRequest().requestUrl?.queryParameter("type")?.toInt()
    }

    @Test
    fun searchIssuesTheThreeTiersInOrderAndMergesThemInThatOrder() {
        // Plex rejects a multi-type search with HTTP 400, so this is three
        // requests merged. Both the request order and the merge order are
        // asserted: the tabs show one flat list, and a port that raced the tiers
        // to "speed it up" would pass a set-equality check while shuffling what
        // the user reads.
        PlexApi().musicSectionKey = "1"
        server.dispatcher = searchDispatcher()

        val result = await(PlexBrowseRepository().search("q", "album-", "artist-"))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("artist-a1", "album-b1", "t1"), result.value!!.map { it.mediaId })
        assertEquals(
            listOf(PlexItemType.ARTIST, PlexItemType.ALBUM, PlexItemType.TRACK),
            requestedTypes()
        )
    }

    @Test
    fun searchKeepsTheOtherTiersWhenOneFails() {
        // One failed tier must not lose the other two: a 500 on albums still
        // leaves usable artist and track results on screen. With Call<T> that
        // was an unsuccessful Response; now it is a thrown HttpException that
        // would abandon the whole merge if it were not caught per tier.
        PlexApi().musicSectionKey = "1"
        server.dispatcher = searchDispatcher(failingType = PlexItemType.ALBUM)

        val result = await(PlexBrowseRepository().search("q", "album-", "artist-"))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("artist-a1", "t1"), result.value!!.map { it.mediaId })
    }

    private fun typedBody(type: String, ratingKey: String) =
        """{"MediaContainer":{"Metadata":[{"ratingKey":"$ratingKey","type":"$type","title":"$ratingKey"}]}}"""

    // ── getPlaylists: section scoping ───────────────────────────

    @Test
    fun getPlaylistsWithNoSectionSelectedReturnsTheSamePermissionDeniedErrorAsTheOtherSectionScopedMethods() {
        // Playlists must be scoped to the chosen music section exactly like
        // getArtists/getAlbums (see PlexBrowseRepository.getPlaylists KDoc) --
        // without that, this tab shows playlists from whichever library Plex
        // feels like rather than the one the user picked. No section has been
        // chosen in this test (PlexApi.musicSectionKey defaults to null, and
        // nothing here sets it), so this must hit the same errorFuture() the
        // other section-scoped methods fall back to -- ERROR_PERMISSION_DENIED
        // -- without ever reaching the network.
        //
        // The bounded get() is deliberate, not just tidiness: errorFuture()
        // completes its future synchronously, so a correctly scoped
        // implementation returns near-instantly. A broken (unscoped)
        // implementation would instead reach the mock server, which has no
        // response queued -- turning what should be a fast, clear assertion
        // failure into a real network attempt and, on an unbounded get(), a hang.
        val result = PlexBrowseRepository().getPlaylists("prefix").get(2, TimeUnit.SECONDS)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        assertEquals(0, server.requestCount)
    }
}
