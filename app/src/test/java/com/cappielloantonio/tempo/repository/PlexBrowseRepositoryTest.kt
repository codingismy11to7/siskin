package com.cappielloantonio.tempo.repository

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.util.ConstantsAA
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

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        // Points the repository's Retrofit base URL at the mock server. It reads
        // this back through PlexApi.session on every call (see refreshClients),
        // so it must be set before the first request rather than injected --
        // and accountToken and musicSectionKey have to be present too, because
        // PlexSession only exists as a complete unit: a real serverUri with no
        // section chosen is a state the atomic session model no longer allows,
        // so an incomplete one here would fall back to the placeholder base URL
        // exactly like a missing one.
        //
        // Every field is reset explicitly rather than assumed absent: App
        // caches the SharedPreferences in a static field that Robolectric does
        // not reset between methods, so a value written by one test is
        // otherwise visible to the next. machineIdentifier and serverCandidates
        // are part of that now too -- a value left over from an address-recovery
        // test would let a later test's fetch reach ServerAddressBook.shared's
        // re-probe path and race real addresses instead of hitting the mock
        // server it thinks it is talking to.
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = server.url("/").toString()
            musicSectionKey = "1"
            machineIdentifier = null
            serverCandidates = null
        }
        // ServerAddressBook.shared is the real production singleton -- there is
        // exactly one, by design -- so its failure-cooldown clock is shared with
        // every other test in this run, not just this class. Nothing else can
        // reach the private field that holds it, so this is the reset.
        ServerAddressBook.shared.resetForTest()
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

    /** For the error branch, where `resultFor` must not call `map` at all. */
    private val mapThatMustNotRun: (PlexResponse) -> List<MediaItem> =
        { error("map must not run when the request failed") }

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

        val result = await(PlexBrowseRepository().getArtistAlbums(ConstantsAA.ALBUM_ID, "15100"))

        val request = server.takeRequest()
        assertEquals("/library/sections/1/all", request.requestUrl?.encodedPath)
        assertEquals("15100", request.requestUrl?.queryParameter("artist.id"))
        assertEquals(PlexItemType.ALBUM, request.requestUrl?.queryParameter("type")?.toInt())
        assertEquals(
            listOf(ConstantsAA.SHUFFLE_ARTIST_ID + "15100", ConstantsAA.ALBUM_ID + "77"),
            result.value!!.map { it.mediaId }
        )
    }

    @Test
    fun theShuffleRowLeadsAnArtistsAlbumsAndIsPlayableButNotBrowsable() {
        // Playable is what makes the car start playback from the row instead of
        // navigating into it; carrying the artist's ratingKey in the id is what
        // lets the session callback fetch that artist's tracks on the tap.
        server.enqueue(MockResponse().setResponseCode(200).setBody(albumsBody("77", "88")))

        val result = await(PlexBrowseRepository().getArtistAlbums(ConstantsAA.ALBUM_ID, "15100"))

        val row = result.value!!.first()
        assertEquals(ConstantsAA.SHUFFLE_ARTIST_ID + "15100", row.mediaId)
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
        assertEquals(ConstantsAA.SHUFFLE_PLAYLIST_ID + "169077", row.mediaId)
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
        // getArtists/getAlbums (see PlexBrowseRepository.getPlaylists KDoc) --
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
}
