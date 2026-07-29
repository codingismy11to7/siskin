package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.toNonEmptyListOrNull
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexFailure
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexIdentity
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.CreatedPin
import com.cappielloantonio.tempo.plex.api.auth.ServerProbe
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.auth.PlexPinState
import com.cappielloantonio.tempo.plex.auth.PlexSignInFlow
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.auth.SignInError
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PlexSignInViewModel"
private const val POLL_INTERVAL_MS = 2_000L

/**
 * Drives the Plex PIN flow.
 *
 * Living in a ViewModel is what lets the poll loop survive fragment recreation
 * instead of restarting and orphaning a pin.
 */
class PlexSignInViewModel @JvmOverloads constructor(
    application: Application,
    private val api: PlexApi = PlexApi(),
    private val authClient: AuthClient = AuthClient(api),
    /** The probe is unauthenticated; the identity headers are courtesy, not access. */
    private val probe: ServerProbe = ServerProbe(
        headers = PlexIdentity.headers(api.clientIdentifier, api.appVersion, null)
    )
) : AndroidViewModel(application) {

    private val _state = MutableLiveData<PlexSignInState>(PlexSignInState.Working)
    val state: LiveData<PlexSignInState> get() = _state

    /**
     * The attempt in flight. Cancelling it abandons everything that attempt
     * issued -- the request awaiting a response, the poll loop's `delay`, and any
     * probe still racing -- because all of them are suspended inside this job.
     *
     * This is what a generation counter used to do by hand. A Retrofit callback
     * cannot be called off once enqueued, so the only way to neutralise one was to
     * make its *result* inert and re-check a counter at every callback entry. A
     * cancelled coroutine has no result -- the continuation never resumes -- so
     * there is nothing left to guard against publishing. The duplicate-poll-loop
     * bug that motivated the counter cannot recur either: the loop is a `while`
     * inside this job rather than a self-rescheduling Handler message, so there is
     * no queued message that can outlive the attempt that posted it.
     */
    private var attempt: Job? = null

    /** Safe to call repeatedly; does nothing while an attempt is already running. */
    fun start() {
        if (attempt?.isActive == true) return
        signIn()
    }

    fun retry() {
        _state.value = PlexSignInState.Working
        signIn()
    }

    fun chooseServer(resource: Resource) {
        // Picking a server supersedes the poll loop and any earlier pick: an
        // outstanding probe or sections call describes a server the user is no
        // longer signing in to.
        attempt?.cancel()
        _state.value = PlexSignInState.Working

        attempt = viewModelScope.launch {
            either {
                // THE INVARIANT: accountToken, serverUri and musicSectionKey describe one
                // connection, and CredentialGate.isSignedIn() reads all three as a set.
                // They are written at different moments, so every moment that invalidates
                // one of them must invalidate the rest -- a *mixed* set must never be
                // readable, because the gate would report it as signed in and browse would
                // then ask this server for another server's section.
                //
                // Cleared before the new server is adopted, and serverUri is not written
                // again until the probe has found an address that answers. The worst a
                // reader can see in between is no server at all: signed out, which is
                // true.
                //
                // CarSignInActivity is the media session's activity, so the car's app
                // affordance opens this screen at any time, including while fully signed
                // in. Approving a pin, picking a different server and walking away is
                // therefore reachable, and this is what makes the state it leaves behind
                // read as signed out rather than as a working sign-in pointed at the
                // wrong library.
                api.musicSectionKey = null
                api.serverUri = null
                api.serverToken = resource.accessToken

                // The probe answering with nothing is the same failure a dead call
                // is, so it reports as the same thing rather than via a second case.
                val uri = ensureNotNull(probe.bestConnectionUri(resource)) {
                    Log.d(TAG, "no advertised connection answered for ${resource.name}")
                    SignInError.Api(PlexFailure.Unreachable(PlexHost.Server))
                }

                api.serverUri = uri

                // Constructed *after* the assignment above on purpose: LibraryClient pins
                // api.serverUri at construction time and never re-reads it.
                val response = LibraryClient(api).getSections()
                    .mapLeft(SignInError::Api)
                    .bind()

                val sections = ensureNotNull(
                    LibraryClient.musicSections(response).toNonEmptyListOrNull()
                ) { SignInError.NoLibraries }

                _state.value = PlexSignInState.ChoosingLibrary(sections)
            }.onLeft { _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(it)) }
        }
    }

    fun chooseLibrary(section: Directory) {
        // Done is terminal, and cancelling is what makes that true of the state as
        // well as of the flow: nothing still outstanding can publish a Failed over a
        // finished sign-in.
        attempt?.cancel()
        api.musicSectionKey = section.key
        _state.value = PlexSignInState.Done
    }

    private fun signIn() {
        attempt?.cancel()

        attempt = viewModelScope.launch {
            either {
                val created = authClient.createPin().mapLeft(SignInError::Api).bind()

                val awaiting = PlexSignInFlow.afterPinCreated(created)
                _state.value = awaiting

                val authorized = awaitApproval(created, awaiting).bind()
                api.accountToken = authorized.authToken
                _state.value = PlexSignInState.Working

                val resources = authClient.getResources().mapLeft(SignInError::Api).bind()
                val servers = ensureNotNull(
                    AuthClient.mediaServers(resources).toNonEmptyListOrNull()
                ) { SignInError.NoServers }

                _state.value = PlexSignInState.ChoosingServer(servers)
            }.onLeft { _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(it)) }
        }
    }

    /**
     * Polls until the pin is approved, expires, or the attempt is cancelled.
     *
     * A dropped poll is deliberately *not* a failure: the pin is still live, so
     * the loop keeps going and [PlexPinState.shouldKeepPolling] is what
     * eventually gives up. That distinction is the one thing `either { }` cannot
     * express on its own -- a bound failure would abandon the whole sign-in on a
     * single network blip, which on car Wi-Fi is a routine event.
     */
    private suspend fun awaitApproval(
        created: CreatedPin,
        awaiting: PlexSignInState.AwaitingApproval
    ): Either<SignInError, PlexPinState.Authorized> = either {
        val startedAt = nowEpochSeconds()

        while (true) {
            delay(POLL_INTERVAL_MS)

            ensure(
                PlexPinState.shouldKeepPolling(
                    startedAt,
                    nowEpochSeconds(),
                    awaiting.expiresAtEpochSeconds
                )
            ) { SignInError.PinExpired }

            // Recovered, not bound: a 429, a 5xx, a 404 on a consumed pin or a
            // dropped connection is not worth abandoning a sign-in over.
            val pin = authClient.getPin(created.id)
                .onLeft { Log.d(TAG, "pin poll failed, retrying: $it") }
                .getOrNull() ?: continue

            when (
                val pinState = PlexPinState.evaluate(
                    pin.authToken,
                    AuthClient.expiresAtEpochSeconds(pin),
                    nowEpochSeconds()
                )
            ) {
                is PlexPinState.Authorized -> return@either pinState

                PlexPinState.Expired -> raise(SignInError.PinExpired)

                // Nothing changed, so nothing is published: re-emitting the same
                // state would reload the QR image every two seconds.
                PlexPinState.Pending -> {}
            }
        }

        // Unreachable: the loop only leaves via return@either or raise.
        error("poll loop fell through")
    }

    private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
