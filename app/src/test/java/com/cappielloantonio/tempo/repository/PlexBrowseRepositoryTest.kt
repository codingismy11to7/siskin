package com.cappielloantonio.tempo.repository

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider
import com.cappielloantonio.tempo.provider.CompositeArt
import com.cappielloantonio.tempo.provider.CompositeArtBucket
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DecadeKey
import com.cappielloantonio.tempo.util.HubKey
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

// Robolectric rather than plain JUnit: the tests below construct a real
// PlexBrowseRepository, and PlexApi (which it holds) reads
// App.getInstance().preferences -- a live Context, which only exists under
// Robolectric. The pure-function tests do not touch Android at all and behave
// identically either way.
@RunWith(RobolectricTestRunner::class)
class PlexBrowseRepositoryTest {

    private val fixture = PlexBrowseTestServer()
    private val server: MockWebServer get() = fixture.server

    @Before
    fun startServer() {
        fixture.start()
    }

    @After
    fun stopServer() {
        fixture.stop()
    }

    private fun response(vararg items: Metadata) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply { metadata = items.toList() }
    }

    private fun item(ratingKey: String?, type: String) = Metadata().apply {
        this.ratingKey = ratingKey
        this.type = type
    }

    /** For the error branch, where `resultFor` must not call `map` at all. */
    private val mapThatMustNotRun: suspend (PlexResponse) -> List<MediaItem> =
        { error("map must not run when the request failed") }

    /**
     * A stand-in for the real `getPlaylists`/`getArtistWindows`/... map lambdas:
     * narrow with [PlexBrowseRepository.tracksOf] like a real caller would,
     * then blow up on anything that survives the narrowing. `resultFor`
     * always invokes this on a successful response, even when the eventual
     * list is empty -- only the per-item builder inside it is skipped, so a
     * lambda that explodes unconditionally would fail against the real
     * production lambdas too and would prove nothing.
     */
    private val trackMapThatMustNotBuildAnyItem: suspend (PlexResponse) -> List<MediaItem> =
        { body -> PlexBrowseRepository.tracksOf(body).map { error("built a MediaItem from $it, but the narrowed list should have been empty") } }

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
    // plexCall (see PlexCall.kt) is what turns Retrofit's HttpException and
    // IOException into PlexTransportFailure values, so resultFor itself never touches
    // an exception -- only an Either. These drive it directly with
    // already-folded Right/Left request stubs, to pin the outcome→LibraryResult
    // mapping in isolation from Retrofit and the network.

    @Test
    fun resultForAnEmptyLibrarySucceedsWithAnEmptyListRatherThanErroring() = runTest {
        // The regression this whole task exists to catch: Plex answers a
        // no-match listing with HTTP 200, MediaContainer present, Metadata
        // absent. The Subsonic implementation this replaces mistook that shape
        // for a failure and showed "Something went wrong" on the first of
        // three browse tabs for every user with no playlists.
        val result = PlexBrowseRepository.resultFor(
            { PlexResponse().apply { mediaContainer = MediaContainer() }.right() },
            trackMapThatMustNotBuildAnyItem
        ).getOrNull()!!

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.isEmpty())
    }

    @Test
    fun resultForHttp401IsPermissionDenied() = runTest {
        val result = PlexBrowseRepository.resultFor(
            { PlexTransportFailure.Http(PlexHost.Server, 401).left() },
            mapThatMustNotRun
        ).getOrNull()!!

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun resultForHttp403IsPermissionDenied() = runTest {
        val result = PlexBrowseRepository.resultFor(
            { PlexTransportFailure.Http(PlexHost.Server, 403).left() },
            mapThatMustNotRun
        ).getOrNull()!!

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun resultForHttp500IsBadValue() = runTest {
        val result = PlexBrowseRepository.resultFor(
            { PlexTransportFailure.Http(PlexHost.Server, 500).left() },
            mapThatMustNotRun
        ).getOrNull()!!

        assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
    }

    @Test
    fun resultForKeepsATransportFailureAsALeftRatherThanTurningItIntoAnError() = runTest {
        // The distinction resultFor has to keep: an unreachable server must not
        // reach the user as "rejected". Staying Left is what makes launchInto
        // complete the future exceptionally instead of with a LibraryResult.
        val failure = PlexTransportFailure.Unreachable(PlexHost.Server)

        val result = PlexBrowseRepository.resultFor({ failure.left() }, mapThatMustNotRun)

        assertEquals(failure.left(), result)
    }

    // ── end to end, through real Retrofit ───────────────────────────
    //
    // These drive the public future-returning API against a socket, so they
    // cover the whole bridge the seam tests above cannot see: the coroutine
    // launch, plexCall actually folding a real HttpException/IOException that
    // Retrofit raises into a PlexTransportFailure, and the SettableFuture completion.

    private fun tracksBody(vararg ratingKeys: String) = """
        {"MediaContainer":{"Metadata":[${
        ratingKeys.joinToString(",") { """{"ratingKey":"$it","type":"track","title":"Track $it"}""" }
    }]}}
    """.trimIndent()

    private fun albumsBody(vararg ratingKeys: String) = """
        {"MediaContainer":{"Metadata":[${
        ratingKeys.joinToString(",") { """{"ratingKey":"$it","type":"album","title":"Album $it"}""" }
    }]}}
    """.trimIndent()

    /** Bounded so a bridge that never completes its future fails instead of hanging. */
    private fun await(future: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>) =
        future.get(10, TimeUnit.SECONDS)

    @Test
    fun anArtistsAlbumsComeFromTheSectionFilteredByArtistNotFromItsChildren() {
        // Measured against a live PMS 1.43.3 library: an artist's
        // /library/metadata/{id}/children silently omits albums. Five of the
        // first twelve artists returned 0 children while owning 1 album each,
        // and one returned 14 of its 17 -- the omitted three being a
        // compilation, a greatest-hits and a live album whose own metadata is
        // field-for-field identical in shape to the ones that appeared, so
        // nothing in the response distinguishes them. Adding ?type=9 changes
        // nothing; that endpoint's index is simply incomplete.
        //
        // The section listing filtered by artist.id returned the right count for
        // every artist sampled. It is also what the Albums tab already reads,
        // which is how an album could be visible there and missing from its own
        // artist -- the two screens were asking different indexes.
        server.enqueue(MockResponse().setResponseCode(200).setBody(albumsBody("77")))

        val result = await(PlexBrowseRepository().getArtistAlbums(Constants.ALBUM_ID, "15100"))

        val request = server.takeRequest()
        assertEquals("/library/sections/1/all", request.requestUrl?.encodedPath)
        assertEquals("15100", request.requestUrl?.queryParameter("artist.id"))
        assertEquals(PlexItemType.ALBUM, request.requestUrl?.queryParameter("type")?.toInt())
        assertEquals(
            listOf(Constants.MIX_ARTIST_ID + "15100", Constants.ALBUM_ID + "77"),
            result.value!!.map { it.mediaId }
        )
    }

    @Test
    fun theShuffleRowLeadsAnArtistsAlbumsAndIsPlayableButNotBrowsable() {
        // Playable is what makes the car start playback from the row instead of
        // navigating into it; carrying the artist's ratingKey in the id is what
        // lets the session callback fetch that artist's tracks on the tap.
        server.enqueue(MockResponse().setResponseCode(200).setBody(albumsBody("77", "88")))

        val result = await(PlexBrowseRepository().getArtistAlbums(Constants.ALBUM_ID, "15100"))

        val row = result.value!!.first()
        assertEquals(Constants.MIX_ARTIST_ID + "15100", row.mediaId)
        assertEquals(true, row.mediaMetadata.isPlayable)
        assertEquals(false, row.mediaMetadata.isBrowsable)
        // A non-null localConfiguration would make resolveQueueForItem treat the
        // row as an already-resolved track and play a stream that does not exist.
        assertNull(row.localConfiguration)
    }

    @Test
    fun theShuffleRowLeadsAPlaylistsTracks() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11", "22")))

        val result = await(PlexBrowseRepository().getPlaylistTracks("169077"))

        val row = result.value!!.first()
        assertEquals(Constants.MIX_PLAYLIST_ID + "169077", row.mediaId)
        assertEquals(true, row.mediaMetadata.isPlayable)
        assertNull(row.localConfiguration)
        assertEquals(listOf("11", "22"), result.value!!.drop(1).map { it.mediaId })
    }

    @Test
    fun theQueueAShuffleRowBuildsDoesNotContainTheRowItself() {
        // A queue holding the row would hold a playable item with no stream.
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11", "22")))

        val result = await(PlexBrowseRepository().getPlaylistTracksForShuffle("169077"))

        assertEquals(listOf("11", "22"), result.value!!.map { it.mediaId })
    }

    @Test
    fun anArtistsTracksAreFetchedFlatAndInLibraryOrderForTheShuffleRow() {
        // Left unshuffled deliberately: the player owns shuffling, so turning the
        // car's toggle off has to reveal the artist's real order.
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("1", "2", "3")))

        val result = await(PlexBrowseRepository().getArtistTracks("15100"))

        val request = server.takeRequest()
        assertEquals("/library/sections/1/all", request.requestUrl?.encodedPath)
        assertEquals("15100", request.requestUrl?.queryParameter("artist.id"))
        assertEquals(PlexItemType.TRACK, request.requestUrl?.queryParameter("type")?.toInt())
        assertEquals(listOf("1", "2", "3"), result.value!!.map { it.mediaId })
    }

    @Test
    fun a200ListingBecomesASuccessfulItemList() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11", "22")))

        val result = await(PlexBrowseRepository().getAlbumTracks("5"))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("11", "22"), result.value!!.map { it.mediaId })
    }

    @Test
    fun a401BecomesPermissionDeniedRatherThanAnExceptionallyCompletedFuture() {
        // The central hazard now: plexCall folds the real HttpException
        // Retrofit throws for a 401 into Left(PlexTransportFailure.Http(..., 401)), and
        // resultFor has to route that to a LibraryResult error rather than
        // raise it -- raising would flow through launchInto's Left branch and
        // complete the future exceptionally instead, classifyFailure would
        // never see ERROR_PERMISSION_DENIED, and the car would lose the
        // "sign in again" button entirely.
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

    // ── refreshClients: rebuilds when the session appears later ─────
    //
    // MediaService constructs PlexBrowseRepository when the car first browses,
    // which on a fresh install is *before* any server is known -- see this
    // class's KDoc. Every test above sets a real session in startServer(), so
    // the repository under test is always constructed *after* signing in and
    // refreshClients's rebuild-on-change branch is never entered. If the
    // rebuild ever stopped happening, clients captured against the
    // unreachable placeholder base URL would stay pinned to it for the
    // repository's whole lifetime, and signing in would leave every browse tab
    // permanently empty with no error the user could act on.

    @Test
    fun refreshClientsRebuildsClientsAfterASessionAppears() {
        // No session yet: clears the serverUri/serverToken/musicSectionKey
        // startServer() set, so PlexApi().session is null and the repository
        // below is constructed against the unreachable placeholder base URL
        // (see PlexRetrofitFactory), not the mock server.
        PlexApi().session = null
        val repository = PlexBrowseRepository()

        // Placeholder base URL, so this must fail rather than reach the mock
        // server -- same shape as anUnreachableServerCompletesTheFutureExceptionally.
        val beforeSignIn = assertThrows(ExecutionException::class.java) {
            await(repository.getAlbumTracks("5"))
        }
        assertTrue("cause was ${beforeSignIn.cause}", beforeSignIn.cause is IOException)
        assertEquals("a placeholder-bound client must never reach the mock server", 0, server.requestCount)

        // Sign in for real, against the same repository instance.
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = server.url("/").toString()
            musicSectionKey = "1"
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11")))

        val afterSignIn = await(repository.getAlbumTracks("5"))

        assertEquals(LibraryResult.RESULT_SUCCESS, afterSignIn.resultCode)
        assertEquals(listOf("11"), afterSignIn.value!!.map { it.mediaId })
        assertEquals("the rebuilt client must reach the mock server", 1, server.requestCount)
    }

    // ── address recovery: launchInto wired to ServerAddressBook.shared ──
    //
    // Proves the wiring itself, not ServerAddressBook's internals (those are
    // ServerAddressBookTest's job): a browse against a session's stored
    // address that has gone stale re-probes through the real
    // ServerAddressBook.shared and retries once against whichever stored
    // candidate answers, recovering instead of failing. Reverting launchInto's
    // call site back to a bare `block()` leaves this red -- see Important 2 in
    // the task 4 review findings.

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
    fun aBrowseRecoversWhenTheStoredAddressDiesButAnotherStillAnswers() {
        val dead = deadUri()
        val live = liveServer(tracksBody("11"))
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

        val result = await(PlexBrowseRepository().getAlbumTracks("5"))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("11"), result.value!!.map { it.mediaId })
        assertEquals(
            "the re-probe must move the session onto the address that answered",
            liveUri,
            PlexApi().serverUri
        )
        live.shutdown()
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
        // leaves usable artist and track results on screen. This pins the
        // typed half of that guarantee -- plexCall turns the 500 into a
        // Left(PlexTransportFailure.Http(...)) and collect() folds it to an empty list
        // rather than binding or propagating it. collect()'s catch is the
        // other half, covering what is not a PlexTransportFailure at all (a Gson
        // JsonSyntaxException is not wrapped in IOException by Retrofit, so
        // it reaches collect as itself); no test drives that path.
        PlexApi().musicSectionKey = "1"
        server.dispatcher = searchDispatcher(failingType = PlexItemType.ALBUM)

        val result = await(PlexBrowseRepository().search("q", "album-", "artist-"))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("artist-a1", "t1"), result.value!!.map { it.mediaId })
    }

    @Test
    fun eachSearchTierKeepsOnlyResultsOfItsOwnType() {
        // Every browse node narrows its response with itemsOf(body, TYPE_X);
        // search used to narrow with a filter that admitted tracks, albums and
        // artists alike, and then built items of one specific kind out of
        // whatever survived. A type-scoped Plex search that answered with a
        // mixed set would therefore have had the ARTIST tier building artist
        // entries out of albums -- browsable rows that navigate to an artist id
        // Plex will not resolve.
        //
        // Here the artist tier answers with an album and the album tier with a
        // track, so a tier that does not narrow by its own type produces
        // "artist-a1"/"album-b1" rows for them.
        PlexApi().musicSectionKey = "1"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.queryParameter("type")?.toInt()) {
                    PlexItemType.ARTIST -> typedBody("album", "a1")
                    PlexItemType.ALBUM -> typedBody("track", "b1")
                    else -> typedBody("track", "t1")
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }

        val result = await(PlexBrowseRepository().search("q", "album-", "artist-"))

        assertEquals(listOf("t1"), result.value!!.map { it.mediaId })
    }

    private fun typedBody(type: String, ratingKey: String) =
        """{"MediaContainer":{"Metadata":[{"ratingKey":"$ratingKey","type":"$type","title":"$ratingKey"}]}}"""

    // ── getPlaylists: section scoping ───────────────────────────

    @Test
    fun getPlaylistsWithNoSectionSelectedReturnsTheSamePermissionDeniedErrorAsTheOtherSectionScopedMethods() {
        // Playlists must be scoped to the chosen music section exactly like
        // getArtistWindows/getAlbumWindows (see PlexBrowseRepository.getPlaylists KDoc) --
        // without that, this tab shows playlists from whichever library Plex
        // feels like rather than the one the user picked. No section is chosen
        // here (overriding startServer()'s default), so this must hit the same
        // errorFuture() the other section-scoped methods fall back to --
        // ERROR_PERMISSION_DENIED -- without ever reaching the network.
        //
        // The bounded get() is deliberate, not just tidiness: errorFuture()
        // completes its future synchronously, so a correctly scoped
        // implementation returns near-instantly. There used to be a companion
        // assertEquals(0, server.requestCount) here on the theory that a
        // broken (unscoped) implementation would instead reach the mock
        // server -- that stopped being true once PlexSession became
        // all-or-nothing (see PlexSession's KDoc): clearing musicSectionKey
        // here also clears the session, so refreshClients() bakes every
        // client to the unreachable placeholder base URL regardless of
        // whether getPlaylists is scoped correctly. A broken implementation
        // would therefore also leave server.requestCount at 0 -- it would
        // just fail against the placeholder host instead -- so that assertion
        // held for correct and broken code alike and has been dropped rather
        // than kept as decoration. The resultCode assertion below is the one
        // that still catches the regression: a broken implementation reaches
        // the client and either hangs against the unreachable placeholder
        // (caught by the bounded get() above) or completes the future
        // exceptionally, neither of which is the synchronous
        // ERROR_PERMISSION_DENIED asserted here.
        PlexApi().musicSectionKey = null

        val result = PlexBrowseRepository().getPlaylists("prefix").get(2, TimeUnit.SECONDS)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    // ── getDecades / decade tracks ──────────────────────────────

    private fun decadesBody(vararg decades: String) = """
        {"MediaContainer":{"Directory":[${
        decades.joinToString(",") { """{"fastKey":"/x","key":"$it","title":"${it}s"}""" }
    }]}}
    """.trimIndent()

    @Test
    fun decadesComeFromTheSectionsDecadeIndexInServerOrder() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(decadesBody("2000", "1990")))

        val result = await(PlexBrowseRepository().getDecades(Constants.DECADE_ID))

        val request = server.takeRequest()
        assertEquals("/library/sections/1/decade", request.requestUrl?.encodedPath)
        // Albums, the only type with a decade filter.
        assertEquals(PlexItemType.ALBUM, request.requestUrl?.queryParameter("type")?.toInt())
        // The library rides in the id, not just the decade: a decade key is the
        // same string on every server, and a row whose id survived a server
        // switch unchanged is what let the car's browse adapter diff two
        // libraries' rows as one changed row and crash. See DecadeKey.
        assertEquals(
            listOf(
                Constants.DECADE_ID + DecadeKey.of(scope(), "2000"),
                Constants.DECADE_ID + DecadeKey.of(scope(), "1990")
            ),
            result.value!!.map { it.mediaId }
        )
        // Server order is preserved -- Plex returns newest first, which is the
        // order the car should show, so nothing re-sorts it.
        assertEquals(listOf("2000s", "1990s"), result.value!!.map { it.mediaMetadata.title })
    }

    /**
     * The repository -> mapper -> URI wiring, end to end.
     *
     * `PlexMediaMapperAssemblyTest` pins that the mapper puts whatever scope it
     * is handed into the artwork URI, but not that this function hands it the
     * *session's* scope: a `getDecades` that passed a constant, or the section
     * key alone, would pass every mapper test and reintroduce the identical
     * mosaic across two servers that the scope segment exists to prevent.
     *
     * The bucket is asserted as live rather than as an exact value, because it
     * is read off the clock inside `getDecades` and a test that recomputed it
     * afterwards would fail once an hour.
     */
    @Test
    fun aDecadeRowsArtworkUriNamesTheSessionsLibrary() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(decadesBody("2000")))

        val result = await(PlexBrowseRepository().getDecades(Constants.DECADE_ID))

        val artwork = result.value!!.single().mediaMetadata.artworkUri!!
        assertEquals(AlbumArtContentProvider.AUTHORITY, artwork.authority)
        assertEquals(
            listOf(AlbumArtContentProvider.DECADE_ART, scope(), "2000"),
            artwork.pathSegments.dropLast(1)
        )
        assertTrue(
            "artwork bucket must be live: $artwork",
            CompositeArtBucket.isLive(
                artwork.lastPathSegment!!.toLong(), System.currentTimeMillis()
            )
        )
    }

    /** The scope of the session [PlexBrowseTestServer] wrote, computed through
     * the one definition the rows themselves use rather than spelled out here,
     * so this cannot pin a format the minting side has moved off. */
    private fun scope(): String = CompositeArt.currentScope()!!

    /**
     * A HubKey payload scoped to the live session, so `followHubKey`'s
     * library-switch guard lets it through and the request underneath is
     * what each of these tests is actually about. The mismatch guard itself
     * -- a payload scoped to some *other* library -- has its own tests below,
     * where a foreign scope is the point rather than an accident.
     */
    private fun hubKey(rawKey: String = "/library/sections/7/all?type=9"): String =
        HubKey.of(scope(), rawKey)

    @Test
    fun directoriesOfReturnsEmptyForAnAbsentOrEmptyContainer() {
        assertTrue(PlexBrowseRepository.directoriesOf(null).isEmpty())
        assertTrue(PlexBrowseRepository.directoriesOf(PlexResponse()).isEmpty())
    }

    @Test
    fun aDecadesTracksAreSampledRandomlyAndLedByTheShuffleRow() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11", "22")))

        val result = await(PlexBrowseRepository().getDecadeTracks(DECADE_KEY))

        val request = server.takeRequest()
        assertEquals("/library/sections/1/all", request.requestUrl?.encodedPath)
        assertEquals(PlexItemType.TRACK, request.requestUrl?.queryParameter("type")?.toInt())
        // album.decade, not decade -- a bare `decade` on type=10 answers 200
        // with an empty container and the screen would render empty.
        assertEquals("1980", request.requestUrl?.queryParameter("album.decade"))
        assertNull(request.requestUrl?.queryParameter("decade"))
        // Random rather than library order: the 2000s holds 17,649 tracks
        // against a 500 cap, so unsorted would pin every visit to the same
        // sliver and leave the rest of the decade unreachable by any tap.
        assertEquals(LibraryClient.SORT_RANDOM, request.requestUrl?.queryParameter("sort"))

        // The row carries the whole key it was asked for, library included --
        // MediaLibraryServiceCallback.cachedDecadeTracks rebuilds exactly this
        // string from what the car sends back and compares it against index 0
        // of the cached browse list, so the two agree only if neither splits it.
        val row = result.value!!.first()
        assertEquals(Constants.MIX_DECADE_ID + DECADE_KEY, row.mediaId)
        assertEquals(true, row.mediaMetadata.isPlayable)
        assertEquals(false, row.mediaMetadata.isBrowsable)
        // A non-null localConfiguration would make resolveQueueForItem treat the
        // row as an already-resolved track and play a stream that does not exist.
        assertNull(row.localConfiguration)
        assertEquals(listOf("11", "22"), result.value!!.drop(1).map { it.mediaId })
    }

    /**
     * The composite key is opaque everywhere but the Plex filter, and this is
     * where it stops being opaque.
     *
     * Silent if wrong, which is why it earns a direct assertion rather than
     * coverage by implication: measured against PMS 1.43.3, an unrecognised
     * `album.decade` value answers HTTP 200 with an empty container, so the
     * whole key reaching the query renders as a decade with no tracks -- which
     * reads as an empty library, not as a malformed request.
     */
    @Test
    fun theDecadeTracksRequestCarriesTheBareDecadeNotTheCompositeKey() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11")))

        await(PlexBrowseRepository().getDecadeTracks(DECADE_KEY))

        val filter = server.takeRequest().requestUrl?.queryParameter("album.decade")
        assertEquals("1980", filter)
    }

    @Test
    fun theQueueADecadeShuffleRowBuildsDoesNotContainTheRowItself() {
        // A queue holding the row would hold a playable item with no stream.
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11", "22")))

        val result = await(PlexBrowseRepository().getDecadeTracksForShuffle(DECADE_KEY))

        assertEquals(listOf("11", "22"), result.value!!.map { it.mediaId })
    }

    // ── getHubs ───────────────────────────────────────────────

    @Test
    fun listsOnlyHubsThatCanBeOpened() {
        // Four hubs a real server can hand back, each excluded (or not) for a
        // different reason: an ordinary populated hub survives, an empty one is
        // dropped (measured against PMS 1.43.3 -- an empty hub is not an error,
        // see PlexMediaMapper's KDoc), a `clip` hub is music videos this app
        // cannot play, and a key that resolves off-host must never be followed
        // regardless of what the row looks like (LibraryClient.isSafeHubKey).
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"MediaContainer":{"Hub":[
                  {"hubIdentifier":"music.recent.added.7","title":"Recently Added",
                   "type":"album","size":6,"key":"/library/sections/7/all?type=9"},
                  {"hubIdentifier":"music.top.period.7","title":"Top Albums from 1993",
                   "type":"album","size":0,"key":"/library/sections/7/all?year=1993"},
                  {"hubIdentifier":"music.videos.new.7","title":"Music Videos",
                   "type":"clip","size":6,"key":"/library/sections/7/extras/all"},
                  {"hubIdentifier":"music.evil.7","title":"Elsewhere",
                   "type":"album","size":6,"key":"https://elsewhere.example/x"}
                ]}}
                """.trimIndent()
            )
        )

        val result = await(PlexBrowseRepository().getHubs(Constants.HUB_ID))

        val request = server.takeRequest()
        assertEquals("/hubs/sections/1", request.requestUrl?.encodedPath)
        assertEquals(1, result.value!!.size)
        assertEquals("Recently Added", result.value!!.single().mediaMetadata.title)
        assertEquals(
            Constants.HUB_ID + HubKey.of(scope(), "/library/sections/7/all?type=9"),
            result.value!!.single().mediaId
        )
    }

    /**
     * The repository -> mapper -> URI wiring, end to end, on the other row
     * type. `PlexMediaMapperAssemblyTest` pins that the mapper puts whatever
     * scope and bucket it is handed into the artwork URI, but not that
     * `getHubs` hands it the *session's* scope and a *live* bucket -- a
     * `getHubs` that passed a constant scope, or `0L`, or a bucket that never
     * lands in the live/previous window, would pass every mapper test and
     * every other test in this file.
     *
     * Mirrors [aDecadeRowsArtworkUriNamesTheSessionsLibrary]: the bucket is
     * asserted as live rather than as an exact value, because it is read off
     * the clock inside `getHubs` and a test that recomputed it afterwards
     * would fail once an hour. The path shape differs from the decade URI's,
     * though -- `hubArt` trails the pool rather than the bucket (see
     * `AlbumArtContentProvider.hubContentUri`), so the bucket is read from
     * index 2 rather than off the last segment.
     */
    @Test
    fun aHubRowsArtworkUriNamesTheSessionsLibrary() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"MediaContainer":{"Hub":[
                  {"hubIdentifier":"music.recent.added.7","title":"Recently Added",
                   "type":"album","size":1,"key":"/library/sections/7/all?type=9",
                   "Metadata":[{"ratingKey":"51","thumb":"/library/metadata/51/thumb/1"}]}
                ]}}
                """.trimIndent()
            )
        )

        val result = await(PlexBrowseRepository().getHubs(Constants.HUB_ID))

        val artwork = result.value!!.single().mediaMetadata.artworkUri!!
        assertEquals(AlbumArtContentProvider.AUTHORITY, artwork.authority)
        assertEquals(
            listOf(AlbumArtContentProvider.HUB_ART, scope()),
            artwork.pathSegments.take(2)
        )
        assertTrue(
            "artwork bucket must be live: $artwork",
            CompositeArtBucket.isLive(
                artwork.pathSegments[2].toLong(), System.currentTimeMillis()
            )
        )
    }

    @Test
    fun getHubsWithNoSectionSelectedReturnsPermissionDenied() {
        PlexApi().musicSectionKey = null

        val result = PlexBrowseRepository().getHubs(Constants.HUB_ID).get(2, TimeUnit.SECONDS)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun explainsItselfWhenTheServerOffersNoUsableHubs() {
        // A server with no play history: the one hub in this response is
        // filtered out by getHubs' own size==0 rule, leaving nothing to show --
        // the same ordinary, non-error shape a fresh account actually hits.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"MediaContainer":{"Hub":[
                  {"hubIdentifier":"music.touring.7","title":"Artists on Tour",
                   "type":"artist","size":0,"key":"/library/sections/7/all?type=8"}
                ]}}
                """.trimIndent()
            )
        )

        val result = await(PlexBrowseRepository().getHubs(Constants.HUB_ID))

        val items = result.value!!
        assertEquals(1, items.size)
        assertTrue(items[0].mediaId.startsWith(Constants.PICK_MESSAGE_ID))
        assertEquals(
            App.getContext().getString(R.string.browse_discover_empty),
            items[0].mediaMetadata.title
        )
        assertEquals(
            App.getContext().getString(R.string.browse_discover_empty_hint),
            items[0].mediaMetadata.artist
        )
    }

    // ── getHubContent / getHubTracksForShuffle / getHubTracksForIds ─────

    private fun hubItemsBody(vararg items: Pair<String, String>) = """
        {"MediaContainer":{"Metadata":[${
        items.joinToString(",") { (ratingKey, type) ->
            """{"ratingKey":"$ratingKey","type":"$type","title":"$ratingKey"}"""
        }
    }]}}
    """.trimIndent()

    @Test
    fun opensAHubOnItsMixRowThenItsContainers() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                hubItemsBody("111" to "album", "222" to "artist")
            )
        )

        val result = await(PlexBrowseRepository().getHubContent(hubKey()))

        val items = result.value!!
        assertEquals(3, items.size)
        assertTrue(items[0].mediaId.startsWith(Constants.MIX_HUB_ID))
        assertEquals(Constants.ALBUM_ID + "111", items[1].mediaId)
        assertEquals(Constants.ARTIST_ID + "222", items[2].mediaId)
    }

    @Test
    fun followsOnlyTheKeyPartOfTheHubPayload() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(hubItemsBody()))

        await(PlexBrowseRepository().getHubContent(hubKey()))

        assertEquals("/library/sections/7/all", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun aHubKeyRejectedByTheSafetyGuardBecomesPermissionDeniedRatherThanReachingTheServer() {
        // getByHubKey answers null for a key isSafeHubKey refuses -- getHubs is
        // what normally keeps such a key from ever reaching a row, but a stale
        // or tampered id must still fail safely rather than address whatever
        // host the payload names. Scoped to the live session so this hits the
        // safety guard rather than the library-switch guard tested below --
        // the two are different failures with different renderings, and this
        // test is about the one that has always errored.
        val result = await(
            PlexBrowseRepository().getHubContent(hubKey("https://elsewhere.example/x"))
        )

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        assertEquals(0, server.requestCount)
    }

    // ── the library-switch guard: HubKey's scope checked against the session ──
    //
    // LibraryPickerRepository.selectLibrary invalidates the root and the
    // picker node but not More's children, so Discover's rows survive a
    // library switch on screen. HubKey's own KDoc names the hazard a stale tap
    // would otherwise hit: a hub key addresses a section by number, so the
    // same id against the new server would query whatever section that number
    // happens to be there.

    @Test
    fun aHubKeyFromALibraryTheSessionHasLeftRendersTheMessageRowRatherThanQueryingTheNewServer() {
        val staleHubKey = HubKey.of("some-other-scope-9", "/library/sections/7/all?type=9")

        val result = await(PlexBrowseRepository().getHubContent(staleHubKey))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val items = result.value!!
        assertEquals(1, items.size)
        assertTrue(items[0].mediaId.startsWith(Constants.PICK_MESSAGE_ID))
        assertEquals(
            "a stale scope must never reach the server -- it may not even own this section",
            0,
            server.requestCount
        )
    }

    @Test
    fun theShuffleVariantAlsoRefusesAStaleHubKeyRatherThanQueryingTheNewServer() {
        // followHubKey backs getHubTracksForShuffle too, and a mix tap that
        // fell through the browse cache after a library switch must not query
        // the new server's section 7 either.
        val staleHubKey = HubKey.of("some-other-scope-9", "/library/sections/7/all?type=9")

        val result = await(PlexBrowseRepository().getHubTracksForShuffle(staleHubKey))

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun explainsAHubThatCameBackEmptyInsteadOfOfferingAMixOfNothing() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(hubItemsBody()))

        val result = await(PlexBrowseRepository().getHubContent(hubKey()))

        val items = result.value!!
        assertEquals(1, items.size)
        assertTrue(items[0].mediaId.startsWith(Constants.PICK_MESSAGE_ID))
        assertTrue(items.none { it.mediaId.startsWith(Constants.MIX_HUB_ID) })
    }

    @Test
    fun theShuffleVariantCarriesNoMixRow() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(hubItemsBody("111" to "album"))
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(tracksBody("900"))
        )

        val result = await(PlexBrowseRepository().getHubTracksForShuffle(hubKey()))

        val items = result.value!!
        assertTrue(items.none { it.mediaId.startsWith(Constants.MIX_HUB_ID) })
        assertTrue(items.all { it.mediaMetadata.isPlayable == true })
        assertEquals(listOf("900"), items.map { it.mediaId })
    }

    @Test
    fun theShuffleVariantFiltersTheSecondRequestByTheFirstResponsesContainers() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                hubItemsBody("111" to "album", "222" to "artist")
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("900")))

        await(PlexBrowseRepository().getHubTracksForShuffle(hubKey()))

        server.takeRequest() // the hub-key follow
        val trackRequest = server.takeRequest()
        assertEquals("111", trackRequest.requestUrl?.queryParameter("album.id"))
        assertEquals("222", trackRequest.requestUrl?.queryParameter("artist.id"))
        assertEquals(LibraryClient.SORT_RANDOM, trackRequest.requestUrl?.queryParameter("sort"))
    }

    @Test
    fun theShuffleVariantCostsOnlyOneRequestWhenTheHubHoldsNoContainers() {
        // cachedTracks finds no tracks in the head response and the node
        // renders empty, which is the honest answer to a key that has already
        // stopped matching anything -- and it costs no second request.
        server.enqueue(MockResponse().setResponseCode(200).setBody(hubItemsBody()))

        val result = await(PlexBrowseRepository().getHubTracksForShuffle(hubKey()))

        assertTrue(result.value!!.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theShuffleVariantsFirstRequestFailureReachesTheCaller() {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = await(PlexBrowseRepository().getHubTracksForShuffle(hubKey()))

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theShuffleVariantsSecondRequestFailureReachesTheCaller() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(hubItemsBody("111" to "album"))
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val result = await(PlexBrowseRepository().getHubTracksForShuffle(hubKey()))

        assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
    }

    @Test
    fun mixesAlbumsAndArtistsWithTheirOwnFilters() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody()))

        await(PlexBrowseRepository().getHubTracksForIds(listOf("1", "2"), listOf("3")))

        val url = server.takeRequest().requestUrl
        assertEquals("1,2", url?.queryParameter("album.id"))
        assertEquals("3", url?.queryParameter("artist.id"))
        assertEquals(LibraryClient.SORT_RANDOM, url?.queryParameter("sort"))
    }

    @Test
    fun getHubTracksForIdsWithNoSectionSelectedReturnsPermissionDenied() {
        PlexApi().musicSectionKey = null

        val result = PlexBrowseRepository().getHubTracksForIds(listOf("1"), emptyList())
            .get(2, TimeUnit.SECONDS)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun aHubsContainersAreCappedAtMaxItemsEvenIfTheServerIgnoresTheSizeHeader() {
        // getByHubKey already asks for at most MAX_ITEMS via
        // X-Plex-Container-Size -- measured against PMS 1.43.3 at 1,322 albums
        // for one real "Recently Added" hub, see LibraryService.getByPath's
        // KDoc -- but containersOf must not simply trust that header was
        // honoured. A server that ignored it, or answered through some other
        // path, must still hand back a bounded list here.
        val items = (1..Constants.MAX_ITEMS + 50).map { it.toString() to "album" }.toTypedArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(hubItemsBody(*items)))

        val result = await(PlexBrowseRepository().getHubContent(hubKey()))

        // The Mix row plus exactly MAX_ITEMS containers, not MAX_ITEMS + 50.
        assertEquals(Constants.MAX_ITEMS + 1, result.value!!.size)
    }

    private companion object {
        /** What the car sends back on a decade tap: the whole DecadeKey
         * payload, library and decade, exactly as `getDecades` minted it. The
         * scope is a foreign one on purpose -- it is not the session's -- so a
         * repository that quietly recomputed the decade from its own session
         * instead of reading the key would be visible here. */
        val DECADE_KEY = DecadeKey.of("f00dcafe-9", "1980")
    }
}
