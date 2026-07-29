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
import com.cappielloantonio.tempo.plex.models.Pin
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
            onBlocking { getResources() } doReturn emptyList<Nothing>().right()
        }

        val viewModel = PlexSignInViewModel(mock<Application>(), authClient = authClient)
        viewModel.start()
        advanceUntilIdle()

        // Two dropped polls did not end it; the account simply has no servers,
        // which is a different failure reached only by getting past the loop.
        val state = viewModel.state.value
        assertTrue(
            "expected the loop to survive two dropped polls, got $state",
            state is PlexSignInState.Failed || state is PlexSignInState.ChoosingServer
        )
    }
}
