package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexFailure
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.CreatedPin
import com.cappielloantonio.tempo.plex.api.auth.ServerProbe
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doReturnConsecutively
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

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
    }

    private val created = CreatedPin(
        id = 42L,
        code = "ABCD",
        qrUrl = null,
        expiresAtEpochSeconds = null
    )

    private fun approvedPin() = Pin().apply { authToken = "granted" }

    /**
     * A media server that survives [AuthClient.mediaServers]: `provides`
     * contains "server" and it has at least one connection with a non-blank
     * `uri`, per [com.cappielloantonio.tempo.plex.api.auth.ServerProbe.hasUsableConnection].
     */
    private fun aMediaServer(accessToken: String? = null) = Resource().apply {
        name = "Living Room"
        provides = "server"
        connections = listOf(Connection().apply { uri = "https://10.0.0.5:32400" })
        this.accessToken = accessToken
    }

    /** A minimal getSections() body with one music (type "artist") section. */
    private fun sectionsBody(key: String) =
        """{"MediaContainer":{"Directory":[{"key":"$key","type":"artist","title":"Music"}]}}"""

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
    fun aDroppedPollDoesNotFailTheSignIn() = runTest(dispatcher) {
        // The behaviour the recover-versus-bind decision exists to protect: on
        // car Wi-Fi a poll gets dropped routinely, and abandoning a live PIN for
        // one blip would make sign-in unusable in a moving vehicle.
        val authClient = mock<AuthClient>().stub {
            onBlocking { createPin() } doReturn created.right()
            onBlocking { getPin(42L) } doReturnConsecutively listOf(
                PlexFailure.Unreachable(PlexHost.PlexTv).left(),
                PlexFailure.Http(PlexHost.PlexTv, 500).left(),
                approvedPin().right()
            )
            onBlocking { getResources() } doReturn listOf(aMediaServer()).right()
        }

        val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient)
        viewModel.start()
        advanceUntilIdle()

        // Two dropped polls did not end the sign-in: it reached the real
        // server picker rather than Failed. A bound (not recovered) poll would
        // turn the first Unreachable into Failed, so only ChoosingServer here
        // proves the loop survived both blips.
        val state = viewModel.state.value
        assertTrue(
            "expected the loop to survive two dropped polls and reach the server picker, got $state",
            state is PlexSignInState.ChoosingServer
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
            onBlocking { createPin() } doReturn created.right()
            onBlocking { getPin(42L) } doReturn approvedPin().right()
            onBlocking { getResources() } doReturn listOf(resource).right()
        }

    @Test
    fun chooseServerPersistsNothing() = runTest(dispatcher) {
        // Drives chooseServer all the way to a successful ChoosingLibrary and
        // asserts that none of the four session-backing values moved, even
        // though chooseServer now knows the candidate server's URI and access
        // token perfectly well. This is the assertion that fails if a mid-flow
        // write like the deleted `api.serverUri = uri` is ever reintroduced.
        val accessToken = "resource-access-token"
        val resource = aMediaServer(accessToken = accessToken)
        val serverUri = server.url("/").toString()
        val authClient = setUpToChoosingServer(resource)
        val probe = mock<ServerProbe>().stub {
            onBlocking { bestConnectionUri(resource) } doReturn serverUri
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody("5")))

        val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, probe = probe)
        viewModel.start()
        advanceUntilIdle()

        val stateAfterSignIn = viewModel.state.value
        assertTrue(
            "setup did not reach ChoosingServer, got $stateAfterSignIn",
            stateAfterSignIn is PlexSignInState.ChoosingServer
        )

        // Snapshot immediately before the call under test -- both null here,
        // since nothing has touched these three keys yet, but read back
        // explicitly rather than assumed so the assertion below is "unchanged"
        // and not just "still null".
        val serverUriBefore = PlexApi().serverUri
        val sectionKeyBefore = PlexApi().musicSectionKey
        val serverTokenBefore = PlexApi().serverToken

        viewModel.chooseServer(resource)
        awaitSettled(viewModel)

        val state = viewModel.state.value
        assertTrue(
            "expected ChoosingLibrary once the probed server answered, got $state",
            state is PlexSignInState.ChoosingLibrary
        )

        assertNull("chooseServer must not persist a session", PlexApi().session)
        assertEquals(serverUriBefore, PlexApi().serverUri)
        assertEquals(sectionKeyBefore, PlexApi().musicSectionKey)
        assertEquals(serverTokenBefore, PlexApi().serverToken)
    }

    @Test
    fun chooseLibraryCommitsAllFourValuesTogether() = runTest(dispatcher) {
        // The other half: once a library is picked, all four values land in
        // one write, matching the account token from sign-in, the URI the
        // probe returned (not something chooseLibrary re-derives), the
        // section the user picked, and that server's own access token.
        val accessToken = "resource-access-token"
        val resource = aMediaServer(accessToken = accessToken)
        val serverUri = server.url("/").toString()
        val sectionKey = "5"
        val authClient = setUpToChoosingServer(resource)
        val probe = mock<ServerProbe>().stub {
            onBlocking { bestConnectionUri(resource) } doReturn serverUri
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(sectionsBody(sectionKey)))

        val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient, probe = probe)
        viewModel.start()
        advanceUntilIdle()
        viewModel.chooseServer(resource)
        awaitSettled(viewModel)

        val stateAfterChoosingServer = viewModel.state.value
        assertTrue(
            "setup did not reach ChoosingLibrary, got $stateAfterChoosingServer",
            stateAfterChoosingServer is PlexSignInState.ChoosingLibrary
        )
        val section = (stateAfterChoosingServer as PlexSignInState.ChoosingLibrary).sections.head

        viewModel.chooseLibrary(section)

        assertEquals(PlexSignInState.Done, viewModel.state.value)
        assertEquals(
            // "granted" is approvedPin()'s authToken -- the account token
            // signIn() wrote before any server was even chosen.
            PlexSession(
                accountToken = "granted",
                serverUri = serverUri,
                musicSectionKey = SectionKey(sectionKey),
                serverToken = accessToken
            ),
            PlexApi().session
        )
    }
}
