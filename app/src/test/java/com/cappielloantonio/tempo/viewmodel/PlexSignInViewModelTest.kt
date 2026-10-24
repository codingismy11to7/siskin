package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.plex.HomeUserSwitch
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.SwitchOutcome
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.CreatedPin
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.api.server.ServerProbe
import com.cappielloantonio.tempo.plex.auth.PendingAccount
import com.cappielloantonio.tempo.plex.auth.PlexPinState
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.auth.SignInError
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.PlexAccount
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaManager
import com.cappielloantonio.tempo.service.PlaybackReporter
import com.cappielloantonio.tempo.util.PlexResourceFixture.aMediaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doReturnConsecutively
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

// Robolectric, like PlexBrowseRepositoryTest and PlexMixRepositoryTest: the
// ViewModel's default PlexApi (and signIn()'s writes through it, e.g.
// api.accountToken) reads/writes App.getInstance().preferences -- a live
// Context, which only exists under Robolectric.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlexSignInViewModelTest {
    @get:Rule
    val instantTaskRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() = server.shutdown()

    // App caches SharedPreferences in a static field Robolectric does not
    // reset between methods, so every key chooseServer/chooseLibrary can touch
    // is cleared explicitly before each test rather than assumed absent --
    // otherwise a session (or a bare accountToken) written by one test would
    // still be readable by the next, and a test asserting "nothing was
    // persisted" could pass for the wrong reason.
    @Before
    fun clearSession() {
        PlexApi().session = null
        PlexApi().accountToken = null
        PlexApi().serverCandidates = null
    }

    // A live session is what makes chooseLibrary's new invalidatesQueue guard
    // (see the comment on that call site) do anything at all -- stopPlayback()
    // returns early with no session attached, which would make every
    // assertion below pass vacuously. Attached for every test in this class,
    // the same way LibraryPickerCommitTest attaches one for every test in
    // that file, even the ones that never touch it.
    private lateinit var player: Player

    @Before
    fun attachBrowseTreeInvalidator() {
        player = mock()
        val mediaSession = mock<MediaLibrarySession>()
        whenever(mediaSession.player).thenReturn(player)
        BrowseTreeInvalidator.attach(mediaSession)
    }

    @After
    fun detachBrowseTreeInvalidator() {
        // BrowseTreeInvalidator is a process-wide singleton; leaving a mock
        // attached would leak into whatever test class runs next.
        BrowseTreeInvalidator.detach()
    }

    private val created =
        CreatedPin(
            id = 42L,
            code = "ABCD",
            qrUrl = null,
            expiresAtEpochSeconds = null,
        )

    private fun approvedPin() = Pin().apply { authToken = "granted" }

    private val me =
        HomeUser().apply {
            uuid = "me"
            title = "me"
            admin = true
        }
    private val jill =
        HomeUser().apply {
            uuid = "jill"
            title = "jill"
            restricted = true
        }
    private val locked =
        HomeUser().apply {
            uuid = "locked"
            title = "locked"
            restricted = true
            isProtected = true
        }

    /** A minimal getSections() body with one music (type "artist") section. */
    private fun sectionsBody(key: String) = """{"MediaContainer":{"Directory":[{"key":"$key","type":"artist","title":"Music"}]}}"""

    /**
     * chooseServer's getSections() call is real Retrofit/OkHttp traffic to
     * [server] -- ServerProbe is stubbed to avoid real network I/O, but the
     * LibraryClient it feeds is constructed directly inside the ViewModel and
     * cannot be substituted, so the round trip itself has to be genuine.
     *
     * That round trip completes on OkHttp's own thread pool, not on
     * [dispatcher]: advanceUntilIdle() only drains coroutines already queued
     * at the moment it runs, so a single call races the (loopback-only, but
     * still real) response. This polls in short real increments until the
     * state leaves Working, bounded so a genuine hang fails the test loudly
     * instead of blocking the suite.
     */
    private fun TestScope.awaitSettled(viewModel: PlexSignInViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value is PlexSignInState.Working &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
            advanceUntilIdle()
        }
    }

    @Test
    fun aDroppedPollDoesNotFailTheSignIn() =
        runTest(dispatcher) {
            // The behaviour the recover-versus-bind decision exists to protect: on
            // car Wi-Fi a poll gets dropped routinely, and abandoning a live PIN for
            // one blip would make sign-in unusable in a moving vehicle.
            val authClient =
                mock<AuthClient>().stub {
                    on { createPin() } doReturn created.right()
                    on { getPin(42L) } doReturnConsecutively
                        listOf(
                            PlexTransportFailure.Unreachable(PlexHost.PlexTv).left(),
                            PlexTransportFailure.Http(PlexHost.PlexTv, 500).left(),
                            approvedPin().right(),
                        )
                    on { homeUsers() } doReturn listOf(me).right()
                    on { getResources() } doReturn listOf(aMediaServer()).right()
                }

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                )
            viewModel.connect()
            advanceUntilIdle()

            // Two dropped polls did not end the sign-in: it reached the real
            // server picker rather than Failed. A bound (not recovered) poll would
            // turn the first Unreachable into Failed, so only ChoosingServer here
            // proves the loop survived both blips.
            val state = viewModel.state.value
            assertTrue(
                "expected the loop to survive two dropped polls and reach the server picker, got $state",
                state is PlexSignInState.ChoosingServer,
            )

            // Proves the two dropped polls were actually retried rather than
            // skipped: one call per doReturnConsecutively entry, including both
            // failures.
            verify(authClient, times(3)).getPin(42L)
        }

    // ── the atomic-session invariant ──────────────────────────────────
    //
    // The point of committing PlexSession as one unit: a section key from one
    // server must never sit beside a different server's address. chooseServer
    // reads a candidate server's sections before anything is persisted;
    // chooseLibrary is the only place that writes. These two tests pin that
    // split directly, rather than relying on CredentialGate or a repository
    // test to notice a mixed set indirectly.

    private fun setUpToChoosingServer(resource: Resource): AuthClient =
        mock<AuthClient>().stub {
            on { createPin() } doReturn created.right()
            on { getPin(42L) } doReturn approvedPin().right()
            on { homeUsers() } doReturn listOf(me).right()
            on { getResources() } doReturn listOf(resource).right()
        }

    @Test
    fun chooseServerPersistsNothing() =
        runTest(dispatcher) {
            // Drives chooseServer all the way to a successful ChoosingLibrary and
            // asserts that none of the four session-backing values moved, even
            // though chooseServer now knows the candidate server's URI and access
            // token perfectly well. This is the assertion that fails if a mid-flow
            // write like the deleted `api.serverUri = uri` is ever reintroduced.
            val accessToken = "resource-access-token"
            val resource = aMediaServer(accessToken = accessToken)
            val serverUri = server.url("/").toString()
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()

            val stateAfterSignIn = viewModel.state.value
            assertTrue(
                "setup did not reach ChoosingServer, got $stateAfterSignIn",
                stateAfterSignIn is PlexSignInState.ChoosingServer,
            )

            // Snapshot immediately before the call under test -- all null here,
            // since nothing has touched these four keys yet, but read back
            // explicitly rather than assumed so the assertion below is "unchanged"
            // and not just "still null".
            val serverUriBefore = PlexApi().serverUri
            val sectionKeyBefore = PlexApi().musicSectionKey
            val serverTokenBefore = PlexApi().serverToken
            val machineIdentifierBefore = PlexApi().machineIdentifier

            viewModel.chooseServer(resource)
            awaitSettled(viewModel)

            val state = viewModel.state.value
            assertTrue(
                "expected ChoosingLibrary once the probed server answered, got $state",
                state is PlexSignInState.ChoosingLibrary,
            )

            assertNull("chooseServer must not persist a session", PlexApi().session)
            assertEquals(serverUriBefore, PlexApi().serverUri)
            assertEquals(sectionKeyBefore, PlexApi().musicSectionKey)
            assertEquals(serverTokenBefore, PlexApi().serverToken)
            // aMediaServer() defaults clientIdentifier to a non-null value, so a
            // regression that wrote api.machineIdentifier directly (bypassing the
            // atomic session setter) would leave PlexApi().session reading null
            // (the other required fields are still absent) while this individual
            // key silently changed -- only checking it here catches that.
            assertEquals(machineIdentifierBefore, PlexApi().machineIdentifier)
        }

    @Test
    fun chooseLibraryCommitsAllValuesTogether() =
        runTest(dispatcher) {
            // The other half: once a library is picked, all five values land in
            // one write, matching the account token from sign-in, the URI the
            // probe returned (not something chooseLibrary re-derives), the
            // section the user picked, that server's own access token, and its
            // machine identifier.
            val accessToken = "resource-access-token"
            val machineIdentifier = "machine-id"
            val resource = aMediaServer(accessToken = accessToken, clientIdentifier = machineIdentifier)
            val serverUri = server.url("/").toString()
            val sectionKey = "5"
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody(sectionKey)))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()
            viewModel.chooseServer(resource)
            awaitSettled(viewModel)

            val stateAfterChoosingServer = viewModel.state.value
            assertTrue(
                "setup did not reach ChoosingLibrary, got $stateAfterChoosingServer",
                stateAfterChoosingServer is PlexSignInState.ChoosingLibrary,
            )
            val section = (stateAfterChoosingServer as PlexSignInState.ChoosingLibrary).sections.head

            viewModel.chooseLibrary(section)

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            assertEquals(
                // "granted" is approvedPin()'s authToken -- the account token
                // signIn() held as the pending account until this commit.
                PlexSession(
                    accountToken = "granted",
                    serverUri = serverUri,
                    musicSectionKey = SectionKey(sectionKey),
                    serverToken = accessToken,
                    machineIdentifier = machineIdentifier,
                    owned = true,
                ),
                PlexApi().session,
            )
        }

    @Test
    fun chooseLibraryRecordsTheServersOtherAddresses() =
        runTest(dispatcher) {
            // Without this, a fresh sign-in has no list to race, so the first
            // recovery after driving away has to go through plex.tv -- exactly the
            // round trip the stored list exists to avoid.
            val machineIdentifier = "machine-id"
            val resource =
                aMediaServer(
                    accessToken = "resource-access-token",
                    clientIdentifier = machineIdentifier,
                )
            val serverUri = server.url("/").toString()
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()
            viewModel.chooseServer(resource)
            awaitSettled(viewModel)

            val section = (viewModel.state.value as PlexSignInState.ChoosingLibrary).sections.head
            viewModel.chooseLibrary(section)

            val stored = ServerAddressBook.newForTest(PlexApi()).storedCandidates(machineIdentifier)
            assertNotNull("sign-in must record the address list", stored)
            assertTrue(
                "the addresses aMediaServer advertises must be stored",
                stored!!.direct.isNotEmpty(),
            )
        }

    @Test
    fun chooseLibraryAcceptsAServerWithNoMachineIdentifier() =
        runTest(dispatcher) {
            // aMediaServer() defaults clientIdentifier to a non-null value for
            // every other test in this file, so none of them exercise a Resource
            // whose clientIdentifier is null -- the "optional field" property the
            // whole design rests on. This is that case: the session must still
            // commit, just with machineIdentifier absent.
            val accessToken = "resource-access-token"
            val resource = aMediaServer(accessToken = accessToken, clientIdentifier = null)
            val serverUri = server.url("/").toString()
            val sectionKey = "5"
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody(sectionKey)))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()
            viewModel.chooseServer(resource)
            awaitSettled(viewModel)

            val stateAfterChoosingServer = viewModel.state.value
            assertTrue(
                "setup did not reach ChoosingLibrary, got $stateAfterChoosingServer",
                stateAfterChoosingServer is PlexSignInState.ChoosingLibrary,
            )
            val section = (stateAfterChoosingServer as PlexSignInState.ChoosingLibrary).sections.head

            viewModel.chooseLibrary(section)

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            val session = PlexApi().session
            assertNotNull(
                "a missing machineIdentifier must not block the rest of the session from persisting",
                session,
            )
            assertNull(session?.machineIdentifier)
        }

    // ── chooseLibrary's invalidatesQueue guard ─────────────────────────
    //
    // Until the debug screen's "Choose server" row existed, chooseLibrary was
    // reachable only fresh off a PIN, where PlexApi().session was always null
    // going in -- so LibrarySelection.invalidatesQueue(old, new) was always
    // false and there was nothing to guard. CarSettingsFragment can now reopen
    // this same picker while signed in and possibly playing, so a server
    // switch here has to stop playback and drop the saved queue exactly the
    // way LibraryPickerRepository.selectLibrary's equivalent commit already
    // does -- these three prove it, on both the reachable-while-signed-in
    // journey and the original sign-in journey.

    private fun track(ratingKey: String) =
        PlexMediaMapper.buildTrackMediaItem(
            ratingKey = ratingKey,
            title = "Track $ratingKey",
            albumTitle = null,
            artist = null,
            thumb = null,
            partKey = "/library/parts/$ratingKey/file.flac",
            durationMs = null,
            trackIndex = null,
            year = null,
            grandparentRatingKey = null,
            isHearted = false,
            parentId = null,
            serverUri = "http://old-server:32400",
            token = "tok",
        )

    private fun queueIds(): List<String?> {
        var ids: List<String?> = emptyList()
        // Off the test thread: AppDatabase is not built with
        // allowMainThreadQueries() and Robolectric runs the test method on the
        // "main" thread (see LibraryPickerCommitTest for the same pattern).
        Thread {
            ids =
                AppDatabase
                    .getInstance()
                    .queueDao()
                    .getAllSimple()
                    .map { it.id }
        }.apply {
            start()
            join()
        }
        return ids
    }

    private fun pollUntilQueueContains(
        id: String,
        timeoutMs: Long = 3000,
        intervalMs: Long = 10,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (queueIds().contains(id)) return
            Thread.sleep(intervalMs)
        }
        throw AssertionError("'$id' never appeared in the queue within ${timeoutMs}ms")
    }

    /** Seeds the queue and blocks until the seed is actually visible. */
    private fun seedQueue(id: String) {
        QueueRepository().insertAll(listOf(track(id)), true, 0)
        pollUntilQueueContains(id)
    }

    /**
     * Submits a second write through QueueRepository's own single-threaded
     * executor and waits for it, then reads the table -- see
     * LibraryPickerCommitTest's identical helper for why this barrier is what
     * makes a read after chooseLibrary race-free rather than a coin flip
     * against its async deleteAll.
     */
    private fun queueIdsAfterBarrier(): List<String?> {
        QueueRepository().insertAll(listOf(track("barrier")), false, Int.MAX_VALUE)
        pollUntilQueueContains("barrier")
        return queueIds()
    }

    /** Drives [enter] (sign-in by default) through chooseServer to a ready-to-commit ChoosingLibrary. */
    private suspend fun TestScope.setUpToChoosingLibrary(
        resource: Resource,
        enter: (PlexSignInViewModel) -> Unit = { it.connect() },
    ): Pair<PlexSignInViewModel, Directory> {
        val serverUri = server.url("/").toString()
        val authClient = setUpToChoosingServer(resource)
        val probe =
            mock<ServerProbe>().stub {
                on { bestConnectionUri(resource) } doReturn serverUri
            }
        server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

        val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
        enter(viewModel)
        advanceUntilIdle()
        viewModel.chooseServer(resource)
        awaitSettled(viewModel)

        val state = viewModel.state.value
        assertTrue(
            "setup did not reach ChoosingLibrary, got $state",
            state is PlexSignInState.ChoosingLibrary,
        )
        return viewModel to (state as PlexSignInState.ChoosingLibrary).sections.head
    }

    @Test
    fun chooseLibraryOnADifferentServerStopsPlaybackAndClearsTheQueue() =
        runTest(dispatcher) {
            seedQueue("keep-me")
            PlexApi().session =
                PlexSession(
                    accountToken = "old-token",
                    serverUri = "http://old-server:32400",
                    musicSectionKey = SectionKey("9"),
                    serverToken = "old-server-tok",
                    machineIdentifier = "old-machine",
                    owned = true,
                )

            // aMediaServer()'s default clientIdentifier is "machine-id", which
            // disagrees with the "old-machine" session above -- a genuine server
            // change.
            val (viewModel, section) = setUpToChoosingLibrary(aMediaServer(accessToken = "new-tok")) { it.reopenServerPicker() }

            viewModel.chooseLibrary(section)
            // stopPlayback() posts to the main thread, and Robolectric's looper is
            // paused, so without this the queued Player calls never run and the
            // verifications below would fail whether or not the fix is present.
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            verify(player).stop()
            verify(player).clearMediaItems()
            assertFalse(queueIdsAfterBarrier().contains("keep-me"))
        }

    @Test
    fun chooseLibraryOnTheSameServerLeavesPlaybackAndTheQueueAlone() =
        runTest(dispatcher) {
            seedQueue("keep-me")
            PlexApi().session =
                PlexSession(
                    accountToken = "old-token",
                    serverUri = "http://somewhere-else:32400",
                    musicSectionKey = SectionKey("9"),
                    serverToken = "old-server-tok",
                    // Matches aMediaServer()'s default clientIdentifier below: same
                    // server, different library.
                    machineIdentifier = "machine-id",
                    owned = true,
                )

            val (viewModel, section) = setUpToChoosingLibrary(aMediaServer(accessToken = "new-tok")) { it.reopenServerPicker() }

            viewModel.chooseLibrary(section)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            verify(player, never()).stop()
            verify(player, never()).clearMediaItems()
            assertTrue(queueIdsAfterBarrier().contains("keep-me"))
        }

    @Test
    fun chooseLibraryOnTheSignInJourneyLeavesPlaybackAndTheQueueAlone() =
        runTest(dispatcher) {
            // No preset session: clearSession() already left PlexApi().session
            // null, which is the only way chooseLibrary was ever reachable before
            // the debug screen existed. invalidatesQueue(old = null, ...) is
            // false by construction, so this is the case the guard must leave
            // alone.
            seedQueue("keep-me")

            val (viewModel, section) = setUpToChoosingLibrary(aMediaServer(accessToken = "new-tok"))

            viewModel.chooseLibrary(section)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            verify(player, never()).stop()
            verify(player, never()).clearMediaItems()
            assertTrue(queueIdsAfterBarrier().contains("keep-me"))
        }

    // ── recovering from a bad server pick (#18) ────────────────────────
    //
    // These three are also the guard on the ordering inside chooseServer:
    // the server list is captured from the state *before* that state is
    // overwritten with Working, so moving the capture below the overwrite
    // makes it null on every call and turns all three of these red. No
    // separate ordering test is needed, and adding one would assert the
    // same thing a third time.

    /** A getSections() body whose only section is not music. */
    private fun noMusicSectionsBody() = """{"MediaContainer":{"Directory":[{"key":"1","type":"movie","title":"Films"}]}}"""

    @Test
    fun aServerWithNoMusicReturnsToThePickerInsteadOfFailing() =
        runTest(dispatcher) {
            val resource = aMediaServer()
            val serverUri = server.url("/").toString()
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(noMusicSectionsBody()))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()

            // Snapshot immediately before the call under test, same as
            // chooseServerPersistsNothing: uri, resource and candidate are all in
            // scope inside chooseServer's onLeft, so the rejection path is
            // exactly where a partial credential write would be tempting to add
            // later.
            val serverUriBefore = PlexApi().serverUri
            val sectionKeyBefore = PlexApi().musicSectionKey
            val serverTokenBefore = PlexApi().serverToken

            viewModel.chooseServer(resource)
            awaitSettled(viewModel)

            // Failed here is the bug: its only exit calls retry() -> signIn() ->
            // createPin(), which makes the user approve a new PIN to recover from
            // a statement about a server.
            val state = viewModel.state.value
            assertTrue(
                "expected to land back on the server picker, got $state",
                state is PlexSignInState.ChoosingServer,
            )
            state as PlexSignInState.ChoosingServer
            assertEquals(listOf(resource), state.servers)
            assertEquals(R.string.plex_sign_in_error_no_libraries, state.messageRes)

            assertNull("the rejection path must not persist a session", PlexApi().session)
            assertEquals(serverUriBefore, PlexApi().serverUri)
            assertEquals(sectionKeyBefore, PlexApi().musicSectionKey)
            assertEquals(serverTokenBefore, PlexApi().serverToken)
        }

    @Test
    fun anUnreachableServerReturnsToThePickerInsteadOfFailing() =
        runTest(dispatcher) {
            // The probe answering with nothing: no connection the server
            // advertised responded. No MockWebServer traffic -- the flow never
            // gets as far as a sections call.
            val resource = aMediaServer()
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn null
                }

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()
            viewModel.chooseServer(resource)
            awaitSettled(viewModel)

            val state = viewModel.state.value
            assertTrue(
                "expected to land back on the server picker, got $state",
                state is PlexSignInState.ChoosingServer,
            )
            assertEquals(
                R.string.plex_sign_in_error_server_unreachable,
                (state as PlexSignInState.ChoosingServer).messageRes,
            )
        }

    @Test
    fun aSecondPickAfterARejectedOneStillSignsIn() =
        runTest(dispatcher) {
            // The actual fix. The message alone was already correct before this
            // change; what was broken is that the list underneath it was gone.
            //
            // bad and good resolve to the same MockWebServer and its two enqueued
            // responses are dequeued FIFO regardless of which Resource triggered
            // the call, so reaching ChoosingLibrary alone would not prove index 1
            // (good) was actually probed rather than bad again. The verify calls
            // below are what pin that down.
            val bad = aMediaServer()
            val good = aMediaServer(accessToken = "good-token")
            val serverUri = server.url("/").toString()
            val authClient =
                mock<AuthClient>().stub {
                    on { createPin() } doReturn created.right()
                    on { getPin(42L) } doReturn approvedPin().right()
                    on { homeUsers() } doReturn listOf(me).right()
                    on { getResources() } doReturn listOf(bad, good).right()
                }
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(bad) } doReturn serverUri
                    on { bestConnectionUri(good) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(noMusicSectionsBody()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()

            viewModel.chooseServer(bad)
            awaitSettled(viewModel)
            val afterRejection = viewModel.state.value
            assertTrue(
                "expected the picker back after a bad pick, got $afterRejection",
                afterRejection is PlexSignInState.ChoosingServer,
            )

            // Picked straight off the state the user is actually looking at: the
            // list survived rejection, so index 1 (good) is there to pick.
            viewModel.chooseServer((afterRejection as PlexSignInState.ChoosingServer).servers[1])
            awaitSettled(viewModel)

            val state = viewModel.state.value
            assertTrue(
                "expected the second pick to reach the library picker, got $state",
                state is PlexSignInState.ChoosingLibrary,
            )

            // The two things the state assertion above cannot tell apart on its
            // own: that the second pick actually probed good and not bad again
            // (a re-pick of bad would still reach ChoosingLibrary off the second
            // queued response -- see the comment at the top of this test), and
            // that recovering from the rejection did not mint a fresh PIN.
            verify(probe, times(1)).bestConnectionUri(bad)
            verify(probe, times(1)).bestConnectionUri(good)
            verify(authClient, times(1)).createPin()
        }

    // ── in-flow back navigation ─────────────────────────────────────────
    //
    // One row of the table per test, plus handlesBackPress (the enabled/
    // disabled decision the fragment's OnBackPressedCallback reads) checked
    // directly against a sample of each state. The misclick round trip is
    // the scenario this whole feature exists for: chooseServer now carries
    // its server list into ChoosingLibrary (see the comment on that read) so
    // backPressed() has a list to return to instead of an empty screen.

    @Test
    fun backPressedDoesNothingFromDisconnected() =
        runTest {
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = PlexApi(),
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
            assertFalse(viewModel.handlesBackPress(PlexSignInState.Disconnected))

            val consumed = viewModel.backPressed()

            assertFalse("Disconnected has nowhere to go back to", consumed)
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        }

    @Test
    fun backPressedDoesNothingFromConnected() =
        runTest {
            val api = PlexApi()
            api.session =
                PlexSession(
                    accountToken = "t",
                    serverUri = "https://example.invalid",
                    musicSectionKey = SectionKey("1"),
                    serverToken = null,
                    owned = true,
                )
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = api,
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )
            viewModel.open(forceSignIn = false)
            assertEquals(PlexSignInState.Connected, viewModel.state.value)
            assertFalse(viewModel.handlesBackPress(PlexSignInState.Connected))

            val consumed = viewModel.backPressed()

            // Settings (Connected) is a leaf, not a step of the flow -- back
            // leaves the activity, the same as Disconnected.
            assertFalse(consumed)
            assertEquals(PlexSignInState.Connected, viewModel.state.value)
        }

    @Test
    fun handlesBackPressMatchesEveryOtherState() =
        runTest {
            // The three "leaves the activity" states are pinned directly above by
            // their own dedicated tests; this covers the five states that do
            // consume the press, including Done, which the transition table in
            // the task brief omits because it is a one-tick pass-through to
            // onLoginSuccess() rather than a state a user is ever looking at --
            // see the KDoc on handlesBackPress for why "leave" is still the right
            // answer there.
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = PlexApi(),
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )
            val servers = nonEmptyListOf(aMediaServer())
            val sections =
                nonEmptyListOf(
                    Directory().apply {
                        key = "5"
                        title = "Music"
                    },
                )

            assertTrue(viewModel.handlesBackPress(PlexSignInState.Working))
            assertTrue(
                viewModel.handlesBackPress(
                    PlexSignInState.AwaitingApproval(code = "ABCD", qrUrl = null, expiresAtEpochSeconds = null),
                ),
            )
            assertTrue(viewModel.handlesBackPress(PlexSignInState.ChoosingServer(servers, PendingAccount("t"))))
            assertTrue(viewModel.handlesBackPress(PlexSignInState.ChoosingLibrary(sections, servers, PendingAccount("t"))))
            assertTrue(viewModel.handlesBackPress(PlexSignInState.Failed(R.string.plex_sign_in_error_expired)))
            assertFalse(viewModel.handlesBackPress(PlexSignInState.Done))
        }

    @Test
    fun backPressedFromWorkingCancelsTheAttemptAndReturnsToDisconnected() =
        runTest(dispatcher) {
            val resource = aMediaServer()
            val serverUri = server.url("/").toString()
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()
            viewModel.chooseServer(resource)

            // chooseServer sets Working synchronously, before the coroutine that
            // would probe and fetch sections has had a chance to run on
            // `dispatcher` -- so this is genuinely mid-attempt, not a settled
            // state that merely happens to be named Working.
            assertEquals(PlexSignInState.Working, viewModel.state.value)

            val consumed = viewModel.backPressed()

            assertTrue(consumed)
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)

            // Proves the probe/getSections attempt was actually cancelled rather
            // than merely papered over: left running, it would complete once
            // given the chance below and overwrite Disconnected with
            // ChoosingLibrary.
            advanceUntilIdle()
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        }

    @Test
    fun backPressedFromAwaitingApprovalAbandonsThePinAndReturnsToDisconnected() =
        runTest(dispatcher) {
            val authClient =
                mock<AuthClient>().stub {
                    on { createPin() } doReturn created.right()
                    // Always pending: nothing here should matter, since the whole
                    // point is that backPressed() stops the loop before it ever
                    // calls this.
                    on { getPin(42L) } doReturn Pin().right()
                }
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                    nowMillis = { currentTime },
                )
            viewModel.connect()
            // runCurrent(), not advanceUntilIdle(): the poll loop's while(true)
            // always has another delay() queued, so advanceUntilIdle() would fast
            // -forward straight through every iteration to the hard cap and
            // Failed (exactly what anUnapprovedPinGivesUpAtTheHardCap exercises)
            // instead of stopping at the first one. runCurrent() drains only
            // what is due at the current virtual time, which is enough to reach
            // AwaitingApproval and then suspend on that first delay().
            runCurrent()
            assertTrue(viewModel.state.value is PlexSignInState.AwaitingApproval)

            val consumed = viewModel.backPressed()

            assertTrue(consumed)
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)

            // Abandons the pin: the poll loop's first delay() -- not yet elapsed
            // when backPressed() ran, so getPin() had not been called even
            // once -- must never resume and start polling. Advancing well past
            // the hard cap and letting the scheduler drain proves the loop is
            // truly gone rather than merely not yet due.
            advanceTimeBy(PlexPinState.HARD_CAP_SECONDS * 2_000L)
            advanceUntilIdle()
            verify(authClient, times(0)).getPin(42L)
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        }

    @Test
    fun backPressedFromFailedReturnsToDisconnected() =
        runTest(dispatcher) {
            // Same setup as anUnapprovedPinGivesUpAtTheHardCap: no expiry on the
            // created pin, so the hard cap is the only way this reaches Failed.
            val authClient =
                mock<AuthClient>().stub {
                    on { createPin() } doReturn created.right()
                    on { getPin(42L) } doReturn Pin().right()
                }
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                    nowMillis = { currentTime },
                )
            viewModel.connect()
            advanceUntilIdle()
            assertEquals(PlexSignInState.Failed(R.string.plex_sign_in_error_expired), viewModel.state.value)

            val consumed = viewModel.backPressed()

            // signIn()'s coroutine has already run to completion by the time
            // Failed is published, so there is nothing left for attempt?.cancel()
            // to actually stop -- this is exercising that the call is still safe
            // (a no-op on a finished Job) and that Failed still goes to
            // Disconnected regardless.
            assertTrue(consumed)
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        }

    @Test
    fun backPressedFromChoosingServerReturnsToDisconnectedAndAllowsReconnecting() =
        runTest(dispatcher) {
            val authClient = setUpToChoosingServer(aMediaServer())
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                )
            viewModel.connect()
            advanceUntilIdle()
            assertTrue(viewModel.state.value is PlexSignInState.ChoosingServer)

            val consumed = viewModel.backPressed()

            assertTrue(consumed)
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)

            // The point of the misclick recovery: connect()'s own two guards
            // (attempt?.isActive, state != Disconnected) must not silently eat
            // the next Connect tap. Without cancelling attempt above, isActive
            // would still read true here (signIn() had already run to
            // completion, so this is really the state guard being proven, but
            // both are checked by simply confirming the flow restarts).
            viewModel.connect()
            advanceUntilIdle()

            verify(authClient, times(2)).createPin()
            assertTrue(viewModel.state.value is PlexSignInState.ChoosingServer)
        }

    @Test
    fun backPressedFromChoosingLibraryReturnsToChoosingServerWithTheSameServersAndNoMessage() =
        runTest(dispatcher) {
            val resource = aMediaServer()
            val serverUri = server.url("/").toString()
            val authClient = setUpToChoosingServer(resource)
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(resource) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()
            viewModel.chooseServer(resource)
            awaitSettled(viewModel)
            assertTrue(viewModel.state.value is PlexSignInState.ChoosingLibrary)

            val consumed = viewModel.backPressed()

            assertTrue(consumed)
            val state = viewModel.state.value
            assertTrue(
                "expected back to return to the server picker, got $state",
                state is PlexSignInState.ChoosingServer,
            )
            state as PlexSignInState.ChoosingServer
            assertEquals(listOf(resource), state.servers)
            // Deliberately null: arriving here by pressing back is the user
            // correcting their own pick, not a rejection -- unlike
            // aServerWithNoMusicReturnsToThePickerInsteadOfFailing, which
            // asserts a message IS set for the rejection case this is not.
            assertNull(state.messageRes)
        }

    @Test
    fun theMisclickRoundTripPicksADifferentServerAfterBackingOutOfTheWrongLibraryPicker() =
        runTest(dispatcher) {
            // The scenario the whole feature exists for: land in the library
            // picker for the wrong server, back out, and pick the right one --
            // without redoing the PIN flow.
            val wrong = aMediaServer()
            val right = aMediaServer(accessToken = "right-token")
            val serverUri = server.url("/").toString()
            val authClient =
                mock<AuthClient>().stub {
                    on { createPin() } doReturn created.right()
                    on { getPin(42L) } doReturn approvedPin().right()
                    on { homeUsers() } doReturn listOf(me).right()
                    on { getResources() } doReturn listOf(wrong, right).right()
                }
            val probe =
                mock<ServerProbe>().stub {
                    on { bestConnectionUri(wrong) } doReturn serverUri
                    on { bestConnectionUri(right) } doReturn serverUri
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("1")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, authClientFor = { authClient }, probe = probe)
            viewModel.connect()
            advanceUntilIdle()

            viewModel.chooseServer(wrong)
            awaitSettled(viewModel)
            val misclick = viewModel.state.value
            assertTrue(
                "setup did not reach ChoosingLibrary, got $misclick",
                misclick is PlexSignInState.ChoosingLibrary,
            )

            val consumed = viewModel.backPressed()
            assertTrue(consumed)
            val corrected = viewModel.state.value
            assertTrue(
                "expected back to return to the server picker, got $corrected",
                corrected is PlexSignInState.ChoosingServer,
            )
            corrected as PlexSignInState.ChoosingServer
            assertEquals(listOf(wrong, right), corrected.servers)

            viewModel.chooseServer(corrected.servers[1])
            awaitSettled(viewModel)

            val state = viewModel.state.value
            assertTrue(
                "expected the corrected pick to reach the library picker, got $state",
                state is PlexSignInState.ChoosingLibrary,
            )

            // Confirms `right`, not `wrong` again, was actually probed the
            // second time -- both enqueued MockWebServer responses would
            // otherwise make either pick look identical -- and that no fresh
            // PIN was minted along the way.
            verify(probe, times(1)).bestConnectionUri(wrong)
            verify(probe, times(1)).bestConnectionUri(right)
            verify(authClient, times(1)).createPin()
        }

    // ── connect()'s guards (descended from #24) ───────────────────────
    //
    // #24 was activity recreation calling start() a second time through
    // onCreateView. That trigger is gone now that the fragment only ever
    // calls connect() from a Disconnected state -- but connect() is still a
    // public method a test (or a future caller) can call directly, so the
    // ViewModel keeps enforcing the invariant itself rather than trusting the
    // fragment's dispatch to be the only path in.

    @Test
    fun connectDoesNotBeginAgainOnceAPickerIsShowing() =
        runTest(dispatcher) {
            // A direct second call, standing in for whatever future caller might
            // make one -- the fragment itself no longer produces this, since its
            // dispatch only reaches connect() from Disconnected.
            val authClient = setUpToChoosingServer(aMediaServer())

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                )
            viewModel.connect()
            advanceUntilIdle()
            val picker = viewModel.state.value
            assertTrue("setup did not reach ChoosingServer, got $picker", picker is PlexSignInState.ChoosingServer)

            // The second call.
            viewModel.connect()
            advanceUntilIdle()

            // Guarding on attempt?.isActive alone is not enough: signIn() has run to
            // COMPLETION by now, so isActive is false, and without the state check
            // this would fall through to createPin() -- discarding an account
            // token that is still good.
            verify(authClient, times(1)).createPin()
            assertEquals(picker, viewModel.state.value)
        }

    @Test
    fun connectStillBeginsWhenNothingHasBeenPublishedYet() =
        runTest(dispatcher) {
            // The other half, and the reason the guard keys on Disconnected rather
            // than on "not null": Disconnected is the initial value, so a first
            // connect() must still sign in.
            val authClient = setUpToChoosingServer(aMediaServer())

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                )
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)

            viewModel.connect()
            advanceUntilIdle()

            verify(authClient, times(1)).createPin()
            assertTrue(viewModel.state.value is PlexSignInState.ChoosingServer)
        }

    @Test
    fun anUnapprovedPinGivesUpAtTheHardCap() =
        runTest(dispatcher) {
            // `created` carries no expiry, so PlexPinState.evaluate returns Pending
            // forever and HARD_CAP_SECONDS is the only thing that can end this loop.
            // evaluate's KDoc says "the caller bounds the poll loop" -- this is the
            // first test that watches the caller actually do it, which only became
            // possible once the loop stopped reading the wall clock.
            val authClient =
                mock<AuthClient>().stub {
                    on { createPin() } doReturn created.right()
                    on { getPin(42L) } doReturn Pin().right()
                }

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                    nowMillis = { currentTime },
                )
            viewModel.connect()
            advanceUntilIdle()

            assertEquals(
                PlexSignInState.Failed(R.string.plex_sign_in_error_expired),
                viewModel.state.value,
            )

            // At the cap, not before it and not after: a loop that gave up early
            // would abandon a pin the user could still approve.
            assertEquals(PlexPinState.HARD_CAP_SECONDS * 1_000L, currentTime)
        }

    @Test
    fun thePollSlowsDownWhenNobodyApproves() =
        runTest(dispatcher) {
            // The executable statement of the backoff: an abandoned sign-in is what
            // this whole change exists to stop paying for. plex.tv is not rate
            // limiting us -- 174 consecutive polls came back 200 -- so nothing but
            // this test will notice if the ladder is ever reverted to a flat rate.
            val authClient =
                mock<AuthClient>().stub {
                    on { createPin() } doReturn created.right()
                    on { getPin(42L) } doReturn Pin().right()
                }

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = authClient,
                    authClientFor = { authClient },
                    nowMillis = { currentTime },
                )
            viewModel.connect()

            // Three minutes and one second: 30 polls at 2s (t=2s..60s), then 24 at
            // 5s (t=65s..180s). A flat 2s cadence would make this 90.
            advanceTimeBy(181_000L)

            verify(authClient, times(54)).getPin(42L)
        }

    @Test
    fun `starts disconnected and stays there until connect is called`() =
        runTest {
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = PlexApi(),
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )

            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)

            // If construction had dialled out, the state would have moved off
            // Disconnected by the time the scheduler drains.
            advanceUntilIdle()
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        }

    // ── open()'s two entry points ──────────────────────────────────────
    //
    // CredentialGate.isSignedIn() is only `session != null`, and the
    // credentials-rejected path arrives with a session that still exists but
    // is no longer accepted by the server. forceSignIn is what keeps that
    // path landing on sign-in rather than on a settings screen it cannot use
    // to fix itself -- see docs/decisions/2026-08-01-car-sign-in-entry-point-design.md.

    @Test
    fun `open goes straight to work when sign-in is forced even with a session present`() =
        runTest {
            val api = PlexApi()
            api.session =
                PlexSession(
                    accountToken = "t",
                    serverUri = "https://example.invalid",
                    musicSectionKey = SectionKey("1"),
                    serverToken = null,
                    owned = true,
                )

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = api,
                    // A bare mock() (as the brief's snippet used) leaves createPin()
                    // returning null, and runTest drains the coroutine connect()
                    // launches before this test finishes -- so an unstubbed
                    // authClient turns into a NoWhenBranchMatchedException here even
                    // though the assertion below is checked synchronously, before
                    // that coroutine gets to run at all. Stubbed the same way
                    // connectStillBeginsWhenNothingHasBeenPublishedYet does so the
                    // background attempt has somewhere harmless to land.
                    authClient = setUpToChoosingServer(aMediaServer()),
                    authClientFor = { setUpToChoosingServer(aMediaServer()) },
                    probe = mock(),
                    nowMillis = { 0L },
                )

            viewModel.open(forceSignIn = true)

            assertTrue(viewModel.state.value is PlexSignInState.Working)
        }

    @Test
    fun `open without forcing lands on Disconnected when no session exists`() =
        runTest {
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = PlexApi(),
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )

            viewModel.open(forceSignIn = false)

            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        }

    @Test
    fun `open forces work even when the viewmodel already reports Connected`() =
        runTest {
            // The failure this guards against does not need a fresh ViewModel to
            // reproduce: open(false) first, landing on Connected because a
            // session exists (exactly what a hypothetical onNewIntent forwarding
            // to an existing ViewModel would see), and only then force sign-in.
            // Before the fix, connect()'s own Disconnected guard silently ate
            // this call.
            val api = PlexApi()
            api.session =
                PlexSession(
                    accountToken = "t",
                    serverUri = "https://example.invalid",
                    musicSectionKey = SectionKey("1"),
                    serverToken = null,
                    owned = true,
                )

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = api,
                    authClient = setUpToChoosingServer(aMediaServer()),
                    authClientFor = { setUpToChoosingServer(aMediaServer()) },
                    probe = mock(),
                    nowMillis = { 0L },
                )

            viewModel.open(forceSignIn = false)
            assertEquals(PlexSignInState.Connected, viewModel.state.value)

            viewModel.open(forceSignIn = true)

            assertTrue(viewModel.state.value is PlexSignInState.Working)
        }

    @Test
    fun `open without forcing lands on Connected when a session exists`() =
        runTest {
            val api = PlexApi()
            api.session =
                PlexSession(
                    accountToken = "t",
                    serverUri = "https://example.invalid",
                    musicSectionKey = SectionKey("1"),
                    serverToken = null,
                    owned = true,
                )

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = api,
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )

            viewModel.open(forceSignIn = false)

            assertEquals(PlexSignInState.Connected, viewModel.state.value)
        }

    // ── signOut() ────────────────────────────────────────────────────────
    //
    // The settings screen's only action. Drops the session and returns to
    // Disconnected rather than finishing the activity -- see
    // docs/decisions/2026-08-01-car-sign-in-entry-point-design.md. Stopping
    // playback and invalidating the browse tree are LoginHost's job, not
    // this class's, so they are out of scope for this test.

    @Test
    fun `signing out clears the session and returns to disconnected`() =
        runTest {
            val api = PlexApi()
            api.session =
                PlexSession(
                    accountToken = "t",
                    serverUri = "https://example.invalid",
                    musicSectionKey = SectionKey("1"),
                    serverToken = null,
                    owned = true,
                )

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = api,
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )
            viewModel.open(forceSignIn = false)
            assertEquals(PlexSignInState.Connected, viewModel.state.value)

            viewModel.signOut()

            assertNull(api.session)
            assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
        }

    @Test
    fun `signing out also clears the account token, unlike a library switch`() =
        runTest {
            // PlexApi's session setter deliberately leaves accountToken alone when
            // clearing a session -- correct for chooseLibrary's library-switch
            // case, where the account is not changing. Sign out means the account
            // itself is being disowned, so signOut() must go further than the
            // setter it calls: a token left behind would make the next
            // createPin()/getPin() carry the previous account's X-Plex-Token
            // while CredentialGate.isSignedIn() already reads false.
            val api = PlexApi()
            api.accountToken = "granted"
            api.session =
                PlexSession(
                    accountToken = "granted",
                    serverUri = "https://example.invalid",
                    musicSectionKey = SectionKey("1"),
                    serverToken = null,
                    owned = true,
                )

            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    api = api,
                    authClient = mock(),
                    probe = mock(),
                    nowMillis = { 0L },
                )
            viewModel.open(forceSignIn = false)
            assertEquals(PlexSignInState.Connected, viewModel.state.value)

            viewModel.signOut()

            assertNull("sign out must not leave the previous account's token behind", api.accountToken)
        }

    /**
     * The flow's answer, and it is the same one whichever screen opened the
     * picker. Where back *lands* when the debug screen opened it is the back
     * stack's business, not this function's -- CarDebugFragmentTest covers
     * that, and PlexSignInFragment is what declines to claim the press.
     */
    @Test
    fun `back out of the server picker abandons the flow`() {
        val viewModel = PlexSignInViewModel(mock<Application>())
        viewModel.setStateForTest(
            PlexSignInState.ChoosingServer(nonEmptyListOf(aMediaServer()), PendingAccount("t")),
        )

        assertTrue(viewModel.backPressed())

        assertEquals(PlexSignInState.Disconnected, viewModel.state.value)
    }

    @Test
    fun `reopening the picker publishes the servers the stored account has`() =
        runTest(dispatcher) {
            PlexApi().accountToken = "stored"
            val authClient =
                mock<AuthClient>().stub {
                    on { getResources() } doReturn listOf(aMediaServer()).right()
                }
            val tokens = mutableListOf<String>()
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = mock(),
                    authClientFor = { token ->
                        tokens += token
                        authClient
                    },
                )

            viewModel.reopenServerPicker()
            advanceUntilIdle()

            val state = viewModel.state.value as PlexSignInState.ChoosingServer
            assertEquals(1, state.servers.size)
            assertEquals(PendingAccount("stored"), state.account)
            assertEquals(listOf("stored"), tokens)
        }

    @Test
    fun `reopening the picker reports a plex_tv failure as Failed`() =
        runTest(dispatcher) {
            PlexApi().accountToken = "stored"
            val authClient =
                mock<AuthClient>().stub {
                    on { getResources() } doReturn
                        PlexTransportFailure.Unreachable(PlexHost.PlexTv).left()
                }
            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = mock(), authClientFor = { authClient })

            viewModel.reopenServerPicker()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is PlexSignInState.Failed)
        }

    @Test
    fun `reopening the picker reports an account with no servers as Failed`() =
        runTest(dispatcher) {
            // Empty rather than absent: an account with resources that are all
            // players rather than servers reaches the same place, and that is the
            // case NoServers exists for.
            PlexApi().accountToken = "stored"
            val authClient =
                mock<AuthClient>().stub {
                    on { getResources() } doReturn emptyList<Resource>().right()
                }
            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = mock(), authClientFor = { authClient })

            viewModel.reopenServerPicker()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is PlexSignInState.Failed)
        }

    private fun signedInAuthClient(home: List<HomeUser>) =
        mock<AuthClient>().stub {
            on { createPin() } doReturn created.right()
            on { getPin(42L) } doReturn approvedPin().right()
            on { homeUsers() } doReturn home.right()
            on { getResources() } doReturn listOf(aMediaServer()).right()
            on { currentUser() } doReturn
                PlexAccount()
                    .apply {
                        uuid = "me"
                        title = "me"
                    }.right()
        }

    /** One mock serving both the PIN calls and every pending-account call. */
    private fun signedInViewModel(
        authClient: AuthClient,
        probe: ServerProbe = mock(),
        homeUserSwitch: HomeUserSwitch = mock(),
    ) = PlexSignInViewModel(
        mock<Application>(),
        authClient = authClient,
        authClientFor = { authClient },
        probe = probe,
        homeUserSwitch = homeUserSwitch,
    )

    @Test
    fun aOneMemberHomeGoesStraightToTheServerPicker() =
        runTest(dispatcher) {
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me)))
            viewModel.connect()
            advanceUntilIdle()

            assertEquals(PendingAccount("granted"), (viewModel.state.value as PlexSignInState.ChoosingServer).account)
        }

    @Test
    fun anApprovedPinIsAskedAboutAsItselfAndWrittenNowhere() =
        runTest(dispatcher) {
            val pending = signedInAuthClient(listOf(me, jill))
            val tokens = mutableListOf<String>()
            val viewModel =
                PlexSignInViewModel(
                    mock<Application>(),
                    authClient = signedInAuthClient(emptyList()),
                    authClientFor = { token ->
                        tokens += token
                        pending
                    },
                )
            viewModel.connect()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is PlexSignInState.ChoosingUser)
            assertEquals(listOf("granted"), tokens)
            assertNull(PlexApi().accountToken)
            assertNull(PlexApi().session)
        }

    @Test
    fun aLargerHomeAsksWhoIsListening() =
        runTest(dispatcher) {
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, jill)))
            viewModel.connect()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue("got $state", state is PlexSignInState.ChoosingUser)
            state as PlexSignInState.ChoosingUser
            assertEquals(listOf("me", "jill"), state.users.map { it.uuid })
            assertEquals(PendingAccount("granted"), state.account)
            assertNull("nobody is marked at sign-in", state.currentUuid)
        }

    @Test
    fun aHomeThatWillNotListStillSignsIn() =
        runTest(dispatcher) {
            val authClient =
                signedInAuthClient(listOf(me)).stub {
                    on { homeUsers() } doReturn PlexTransportFailure.Unreachable(PlexHost.PlexTv).left()
                }
            val viewModel = signedInViewModel(authClient)
            viewModel.connect()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is PlexSignInState.ChoosingServer)
        }

    @Test
    fun choosingAProtectedMemberAsksForTheirPin() =
        runTest(dispatcher) {
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, locked)))
            val account = PendingAccount("granted")
            viewModel.setStateForTest(PlexSignInState.ChoosingUser(nonEmptyListOf(me, locked), account))

            viewModel.chooseUser(locked)

            assertEquals(PlexSignInState.EnteringPin(locked, nonEmptyListOf(me, locked), account), viewModel.state.value)
        }

    @Test
    fun backFromThePinPadReturnsToThePicker() {
        val viewModel = PlexSignInViewModel(mock<Application>(), authClient = mock())
        val account = PendingAccount("stored")
        viewModel.setStateForTest(PlexSignInState.EnteringPin(locked, nonEmptyListOf(me, locked), account, "me"))

        assertTrue(viewModel.backPressed())

        assertEquals(PlexSignInState.ChoosingUser(nonEmptyListOf(me, locked), account, "me"), viewModel.state.value)
    }

    @Test
    fun aWrongPinStaysOnThePad() =
        runTest(dispatcher) {
            val switch =
                mock<HomeUserSwitch>().stub {
                    on { switch(any(), any(), anyOrNull(), anyOrNull()) } doReturn SignInError.WrongPin.left()
                }
            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = mock(), homeUserSwitch = switch)
            val account = PendingAccount("granted")
            viewModel.setStateForTest(PlexSignInState.EnteringPin(locked, nonEmptyListOf(me, locked), account))

            viewModel.enterPin("0000")
            advanceUntilIdle()

            assertEquals(
                PlexSignInState.EnteringPin(
                    locked,
                    nonEmptyListOf(me, locked),
                    account,
                    messageRes = R.string.plex_sign_in_error_wrong_pin,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun aSwitchDuringSignInBecomesTheAccountAtCommit() =
        runTest(dispatcher) {
            // Owned, with no server token: the sections call has to carry the
            // switched member's token rather than fall back to the stored one.
            val owned = aMediaServer(accessToken = null)
            val outcome = SwitchOutcome.NeedsServer(PendingAccount("jill-token"), nonEmptyListOf(owned))
            val switch =
                mock<HomeUserSwitch>().stub {
                    on { switch(any(), any(), anyOrNull(), anyOrNull()) } doReturn outcome.right()
                }
            val probe = mock<ServerProbe>().stub { on { bestConnectionUri(any()) } doReturn server.url("/").toString() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))
            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = mock(), probe = probe, homeUserSwitch = switch)
            viewModel.setStateForTest(PlexSignInState.ChoosingUser(nonEmptyListOf(me, jill), PendingAccount("granted")))

            viewModel.chooseUser(jill)
            advanceUntilIdle()

            verify(switch).switch(PendingAccount("granted"), jill, null, null)
            assertTrue(viewModel.state.value is PlexSignInState.ChoosingServer)
            assertNull("nothing is written before the commit", PlexApi().accountToken)

            viewModel.chooseServer(owned)
            awaitSettled(viewModel)
            assertEquals("jill-token", server.takeRequest().getHeader("X-Plex-Token"))
            assertNull("nothing is written before the commit", PlexApi().accountToken)
            viewModel.chooseLibrary((viewModel.state.value as PlexSignInState.ChoosingLibrary).sections.head)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            assertEquals("jill-token", PlexApi().session!!.accountToken)
            assertEquals("jill-token", PlexApi().accountToken)
            // A fresh sign-in has no previous listener's queue to discard.
            verify(player, never()).stop()
        }

    // ── backing out writes nothing ─────────────────────────────────────
    //
    // The approved PIN and any switch are carried in the states, so every way
    // out of the pickers short of a commit has to leave the stored account
    // and session as they were -- empty at a first sign-in, the old session
    // at a forced re-sign-in.

    private val savedSession =
        PlexSession("admin-token", "https://saved.example", SectionKey("7"), null, "machine-id", owned = true)

    /** Each a fresh pass from PIN approval to a picker, then back out to Disconnected. */
    private suspend fun TestScope.backOutAtEveryPicker(saved: PlexSession?) {
        val resource = aMediaServer(accessToken = "s", clientIdentifier = "other-machine")
        val outcome = SwitchOutcome.NeedsServer(PendingAccount("jill-token"), nonEmptyListOf(resource))
        val paths: List<Pair<String, suspend (PlexSignInViewModel) -> Unit>> =
            listOf(
                "the Home picker" to { _ -> },
                "the PIN pad" to { vm -> vm.chooseUser(locked) },
                "the server picker after a switch" to { vm ->
                    vm.chooseUser(jill)
                    advanceUntilIdle()
                },
                "the library picker after a switch" to { vm ->
                    vm.chooseUser(jill)
                    advanceUntilIdle()
                    server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))
                    vm.chooseServer(resource)
                    awaitSettled(vm)
                },
            )
        for ((where, path) in paths) {
            PlexApi().session = saved
            if (saved == null) PlexApi().accountToken = null
            val tokenBefore = PlexApi().accountToken
            val sessionBefore = PlexApi().session
            val switch =
                mock<HomeUserSwitch>().stub {
                    on { switch(any(), any(), anyOrNull(), anyOrNull()) } doReturn outcome.right()
                }
            val probe = mock<ServerProbe>().stub { on { bestConnectionUri(any()) } doReturn server.url("/").toString() }
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, jill, locked)), probe, switch)

            if (saved == null) viewModel.connect() else viewModel.open(forceSignIn = true)
            advanceUntilIdle()
            path(viewModel)
            while (viewModel.state.value != PlexSignInState.Disconnected) {
                assertTrue("back did nothing at $where", viewModel.backPressed())
            }

            assertEquals("account token after backing out of $where", tokenBefore, PlexApi().accountToken)
            assertEquals("session after backing out of $where", sessionBefore, PlexApi().session)
        }
    }

    @Test
    fun backingOutOfAFirstSignInWritesNothing() =
        runTest(dispatcher) {
            backOutAtEveryPicker(saved = null)
        }

    @Test
    fun backingOutOfAForcedSignInLeavesTheSavedSessionWhole() =
        runTest(dispatcher) {
            backOutAtEveryPicker(saved = savedSession)
        }

    @Test
    fun aSwitchCommittedAtAForcedSignInClearsTheQueue() =
        runTest(dispatcher) {
            PlexApi().session = savedSession
            val resource = aMediaServer(accessToken = "jill-server", clientIdentifier = "machine-id")
            val outcome = SwitchOutcome.NeedsServer(PendingAccount("jill-token"), nonEmptyListOf(resource))
            val switch =
                mock<HomeUserSwitch>().stub {
                    on { switch(any(), any(), anyOrNull(), anyOrNull()) } doReturn outcome.right()
                }
            val probe = mock<ServerProbe>().stub { on { bestConnectionUri(any()) } doReturn server.url("/").toString() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("7")))
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, jill)), probe, switch)

            viewModel.open(forceSignIn = true)
            advanceUntilIdle()
            viewModel.chooseUser(jill)
            advanceUntilIdle()
            // Switched from the PIN's token, not the saved session's, and
            // without the saved session: sign-in always runs the pickers.
            verify(switch).switch(PendingAccount("granted"), jill, null, null)
            viewModel.chooseServer(resource)
            awaitSettled(viewModel)
            viewModel.chooseLibrary((viewModel.state.value as PlexSignInState.ChoosingLibrary).sections.head)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            assertEquals("jill-token", PlexApi().session!!.accountToken)
            verify(player).stop()
        }

    /**
     * A not-owned member forced to sign in again, landing on another server.
     * Nothing is written before the commit, so `previous` is still their old
     * session when it runs, and the server change is seen.
     */
    @Test
    fun aForcedSignInThatCommitsADifferentServerClearsTheQueue() =
        runTest(dispatcher) {
            PlexApi().session = PlexSession("jill-token", "https://s1.example", SectionKey("7"), "jill-s1", "s1", owned = false)
            val s2 = aMediaServer(accessToken = "s2-token", clientIdentifier = "s2")
            val authClient =
                signedInAuthClient(listOf(me)).stub {
                    on { getResources() } doReturn listOf(s2).right()
                }
            val probe = mock<ServerProbe>().stub { on { bestConnectionUri(any()) } doReturn server.url("/").toString() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("7")))
            val viewModel = signedInViewModel(authClient, probe)

            viewModel.open(forceSignIn = true)
            advanceUntilIdle()
            viewModel.chooseServer(s2)
            awaitSettled(viewModel)
            viewModel.chooseLibrary((viewModel.state.value as PlexSignInState.ChoosingLibrary).sections.head)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            assertEquals("s2", PlexApi().session!!.machineIdentifier)
            verify(player).stop()
        }

    @Test
    fun aForcedSignInAsAnotherAccountOnTheSameServerClearsTheQueue() =
        runTest(dispatcher) {
            PlexApi().session = PlexSession("jill-token", "https://s1.example", SectionKey("7"), "jill-s1", "s1", owned = false)
            val s1 = aMediaServer(accessToken = "granted-s1", clientIdentifier = "s1")
            val authClient =
                signedInAuthClient(listOf(me)).stub {
                    on { getResources() } doReturn listOf(s1).right()
                }
            val probe = mock<ServerProbe>().stub { on { bestConnectionUri(any()) } doReturn server.url("/").toString() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("7")))
            val viewModel = signedInViewModel(authClient, probe)

            viewModel.open(forceSignIn = true)
            advanceUntilIdle()
            viewModel.chooseServer(s1)
            awaitSettled(viewModel)
            viewModel.chooseLibrary((viewModel.state.value as PlexSignInState.ChoosingLibrary).sections.head)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            // Same machine, so only the account change can have cleared it.
            assertEquals("s1", PlexApi().session!!.machineIdentifier)
            assertEquals("granted", PlexApi().session!!.accountToken)
            verify(player).stop()
        }

    @Test
    fun aSwitchFromSettingsCommitsWholeAndClearsTheQueue() =
        runTest(dispatcher) {
            PlexApi().session = savedSession
            val resource = aMediaServer(accessToken = "jill-server", clientIdentifier = "machine-id").apply { owned = false }
            val outcome = SwitchOutcome.Committable(PendingAccount("jill-token"), "https://saved.example", resource, SectionKey("7"))
            val switch =
                mock<HomeUserSwitch>().stub {
                    on { switch(any(), any(), anyOrNull(), anyOrNull()) } doReturn outcome.right()
                }
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, jill)), homeUserSwitch = switch)

            viewModel.reopenUserPicker()
            advanceUntilIdle()
            viewModel.chooseUser(jill)
            advanceUntilIdle()

            verify(switch).switch(PendingAccount("admin-token"), jill, null, savedSession)
            // The write waits for the posted stop.
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PlexSignInState.Done, viewModel.state.value)
            val session = PlexApi().session!!
            assertEquals("jill-token", session.accountToken)
            assertEquals("jill-server", session.serverToken)
            assertEquals(false, session.owned)
            // Same server, but the player's loaded items carry the previous
            // user's token in their stream URLs.
            verify(player).stop()
        }

    /**
     * Clearing the player is what reports the outgoing track stopped, so the
     * new member's session must not be written until it has: the owner's Now
     * Playing would never close, and Jill would be sent the owner's track.
     */
    @Test
    fun theOutgoingTrackIsReportedStoppedAsThePreviousMember() =
        runTest(dispatcher) {
            val uri = server.url("/").toString()
            PlexApi().session = PlexSession("admin-token", uri, SectionKey("7"), "admin-server", "machine-id", owned = false)
            val track =
                MediaItem
                    .Builder()
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setExtras(
                                Bundle().apply {
                                    putString(PlexMediaMapper.EXTRA_ID, "42")
                                    putString(PlexMediaMapper.EXTRA_PART_KEY, "/library/parts/1")
                                },
                            ).build(),
                    ).build()
            whenever(player.applicationLooper).thenReturn(Looper.getMainLooper())
            val reporter = PlaybackReporter(player, MediaManager::scrobble)
            whenever(player.clearMediaItems()).thenAnswer {
                reporter.onPositionDiscontinuity(
                    Player.PositionInfo(null, 0, track, null, 0, 30_000L, 30_000L, C.INDEX_UNSET, C.INDEX_UNSET),
                    Player.PositionInfo(null, 0, null, null, 0, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET),
                    Player.DISCONTINUITY_REASON_REMOVE,
                )
            }
            server.enqueue(MockResponse().setResponseCode(200))
            val resource = aMediaServer(accessToken = "jill-server", clientIdentifier = "machine-id").apply { owned = false }
            val outcome = SwitchOutcome.Committable(PendingAccount("jill-token"), uri, resource, SectionKey("7"))
            val switch =
                mock<HomeUserSwitch>().stub {
                    on { switch(any(), any(), anyOrNull(), anyOrNull()) } doReturn outcome.right()
                }
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, jill)), homeUserSwitch = switch)

            viewModel.reopenUserPicker()
            advanceUntilIdle()
            viewModel.chooseUser(jill)
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()

            val stopped = server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("no report was sent")
            assertEquals("stopped", stopped.requestUrl!!.queryParameter("state"))
            assertEquals("admin-server", stopped.getHeader("X-Plex-Token"))
            assertEquals(PlexSignInState.Done, viewModel.state.value)
            assertEquals("jill-server", PlexApi().session!!.serverToken)
        }

    @Test
    fun theSettingsPickerMarksTheCurrentUserAndIgnoresTapsOnIt() =
        runTest(dispatcher) {
            PlexApi().session = savedSession
            val switch = mock<HomeUserSwitch>()
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, jill)), homeUserSwitch = switch)

            viewModel.reopenUserPicker()
            advanceUntilIdle()
            val picker = viewModel.state.value as PlexSignInState.ChoosingUser
            assertEquals("me", picker.currentUuid)
            assertEquals(PendingAccount("admin-token"), picker.account)

            viewModel.chooseUser(me)
            advanceUntilIdle()

            assertTrue(viewModel.state.value is PlexSignInState.ChoosingUser)
            verify(switch, never()).switch(any(), any(), anyOrNull(), anyOrNull())
        }

    @Test
    fun theSettingsPickerFailsWhenItCannotTellWhoIsSignedIn() =
        runTest(dispatcher) {
            // An unmarked list would let the user switch to themselves.
            PlexApi().session = savedSession
            val authClient =
                signedInAuthClient(listOf(me, jill)).stub {
                    on { currentUser() } doReturn PlexTransportFailure.Unreachable(PlexHost.PlexTv).left()
                }
            val viewModel = signedInViewModel(authClient)

            viewModel.reopenUserPicker()
            advanceUntilIdle()

            assertEquals(PlexSignInState.Failed(R.string.plex_sign_in_error_network), viewModel.state.value)
        }

    @Test
    fun theSettingsPickerFailsWhenPlexTvNamesNobody() =
        runTest(dispatcher) {
            PlexApi().session = savedSession
            val authClient =
                signedInAuthClient(listOf(me, jill)).stub {
                    on { currentUser() } doReturn PlexAccount().right()
                }
            val viewModel = signedInViewModel(authClient)

            viewModel.reopenUserPicker()
            advanceUntilIdle()

            assertEquals(PlexSignInState.Failed(R.string.plex_sign_in_error_network), viewModel.state.value)
        }

    @Test
    fun theSettingsPickerFailsOnAnEmptyHomeWithoutBlamingTheServer() =
        runTest(dispatcher) {
            PlexApi().session = savedSession
            val viewModel = signedInViewModel(signedInAuthClient(emptyList()))

            viewModel.reopenUserPicker()
            advanceUntilIdle()

            assertEquals(PlexSignInState.Failed(R.string.plex_sign_in_error_network), viewModel.state.value)
        }

    @Test
    fun anAbandonedSwitchDoesNotLeakIntoALaterChooseServer() =
        runTest(dispatcher) {
            PlexApi().session = savedSession
            val outcome = SwitchOutcome.NeedsServer(PendingAccount("jill-token"), nonEmptyListOf(aMediaServer(accessToken = "s")))
            val switch =
                mock<HomeUserSwitch>().stub {
                    on { switch(any(), any(), anyOrNull(), anyOrNull()) } doReturn outcome.right()
                }
            val probe = mock<ServerProbe>().stub { on { bestConnectionUri(any()) } doReturn server.url("/").toString() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))
            val viewModel = signedInViewModel(signedInAuthClient(listOf(me, jill)), probe, switch)

            viewModel.reopenUserPicker()
            advanceUntilIdle()
            viewModel.chooseUser(jill)
            advanceUntilIdle()

            viewModel.reopenServerPicker()
            advanceUntilIdle()
            val servers = (viewModel.state.value as PlexSignInState.ChoosingServer).servers
            viewModel.chooseServer(servers.head)
            awaitSettled(viewModel)
            viewModel.chooseLibrary((viewModel.state.value as PlexSignInState.ChoosingLibrary).sections.head)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PlexSignInState.Done, viewModel.state.value)
            assertEquals("admin-token", PlexApi().session!!.accountToken)
            verify(player, never()).stop()
        }

    @Test
    fun anUnownedServerWithNoTokenIsRefusedAtThePickerWithoutProbing() =
        runTest(dispatcher) {
            val unowned = aMediaServer(accessToken = null).apply { owned = false }
            val probe = mock<ServerProbe>().stub { on { bestConnectionUri(any()) } doReturn "https://10.0.0.5:32400" }
            val viewModel = PlexSignInViewModel(mock<Application>(), authClient = mock(), probe = probe)
            val account = PendingAccount("t")
            viewModel.setStateForTest(PlexSignInState.ChoosingServer(nonEmptyListOf(unowned), account))

            viewModel.chooseServer(unowned)
            advanceUntilIdle()

            assertEquals(
                PlexSignInState.ChoosingServer(nonEmptyListOf(unowned), account, R.string.plex_sign_in_error_no_server_access),
                viewModel.state.value,
            )
            verify(probe, never()).bestConnectionUri(any())
        }
}
