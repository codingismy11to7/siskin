package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.plex.PlexFailure
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.CreatedPin
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
    private fun aMediaServer() = Resource().apply {
        name = "Living Room"
        provides = "server"
        connections = listOf(Connection().apply { uri = "https://10.0.0.5:32400" })
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
}
