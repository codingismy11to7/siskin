package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexIdentity
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.ServerProbe
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.auth.PlexPinState
import com.cappielloantonio.tempo.plex.auth.PlexSignInFlow
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

private const val TAG = "PlexSignInViewModel"
private const val POLL_INTERVAL_MS = 2_000L

/**
 * Drives the Plex PIN flow.
 *
 * Living in a ViewModel is what lets the poll loop survive fragment recreation
 * instead of restarting and orphaning a pin.
 */
class PlexSignInViewModel(application: Application) : AndroidViewModel(application) {

    private val api = PlexApi()
    private val authClient = AuthClient(api)

    /** The probe is unauthenticated; the identity headers are courtesy, not access. */
    private val probe = ServerProbe(
        headers = PlexIdentity.headers(api.clientIdentifier, api.appVersion, null)
    )

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

            val uri = probe.bestConnectionUri(resource)
            if (uri == null) {
                Log.d(TAG, "no advertised connection answered for ${resource.name}")
                _state.value =
                    PlexSignInState.Failed(R.string.plex_sign_in_error_server_unreachable)
                return@launch
            }

            api.serverUri = uri

            // Constructed *after* the assignment above on purpose: LibraryClient pins
            // api.serverUri at construction time and never re-reads it.
            val sections = try {
                LibraryClient(api).getSections()
            } catch (e: IOException) {
                // The probe reached this address moments ago, so this is the server
                // going away mid-sign-in rather than the wrong address.
                Log.d(TAG, "could not read sections from the chosen server", e)
                _state.value =
                    PlexSignInState.Failed(R.string.plex_sign_in_error_server_unreachable)
                return@launch
            } catch (e: HttpException) {
                // Reachable but refusing. A 401 from a stale token is not a server
                // with no music libraries, and must not be reported as one.
                Log.d(TAG, "reading sections returned HTTP ${e.code()}")
                _state.value =
                    PlexSignInState.Failed(R.string.plex_sign_in_error_server_unreachable)
                return@launch
            }

            _state.value = PlexSignInFlow.afterSections(sections)
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
            val pin = try {
                authClient.createPin()
            } catch (e: IOException) {
                Log.d(TAG, "could not create a pin", e)
                _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
                return@launch
            } catch (e: HttpException) {
                Log.d(TAG, "creating a pin returned HTTP ${e.code()}")
                _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
                return@launch
            }

            val awaiting = PlexSignInFlow.afterPinCreated(pin)
            _state.value = awaiting
            if (awaiting !is PlexSignInState.AwaitingApproval) return@launch

            // afterPinCreated only returns AwaitingApproval for a pin with an id.
            val pinId = pin.id ?: return@launch

            val authorized = awaitApproval(pinId, awaiting) ?: return@launch

            api.accountToken = authorized.authToken
            _state.value = PlexSignInFlow.afterPinPoll(authorized, awaiting)

            discoverServers()
        }
    }

    /**
     * Polls until the pin is approved, expires, or the attempt is cancelled.
     *
     * Returns null when the sign-in is over; the expiry paths publish their own
     * state before returning. A dropped poll is not a failed sign-in -- the pin is
     * still live, so the loop keeps going and the bound is what eventually gives
     * up.
     */
    private suspend fun awaitApproval(
        pinId: Long,
        awaiting: PlexSignInState.AwaitingApproval
    ): PlexPinState.Authorized? {
        val startedAt = nowEpochSeconds()

        while (true) {
            delay(POLL_INTERVAL_MS)

            if (!PlexPinState.shouldKeepPolling(
                    startedAt,
                    nowEpochSeconds(),
                    awaiting.expiresAtEpochSeconds
                )
            ) {
                // Routed through the flow rather than mapped here so that "the pin
                // ran out" has exactly one translation into a message, the same one
                // a server-reported expiry takes below.
                _state.value = PlexSignInFlow.afterPinPoll(PlexPinState.Expired, awaiting)
                return null
            }

            val pin = try {
                authClient.getPin(pinId)
            } catch (e: IOException) {
                Log.d(TAG, "pin poll failed, retrying", e)
                continue
            } catch (e: HttpException) {
                // A 429, a 5xx or a 404 on a consumed pin is not worth abandoning a
                // sign-in over, and the bound above still ends the loop.
                Log.d(TAG, "pin poll returned HTTP ${e.code()}, retrying")
                continue
            }

            when (val pinState = PlexPinState.evaluate(
                pin.authToken,
                AuthClient.expiresAtEpochSeconds(pin),
                nowEpochSeconds()
            )) {
                is PlexPinState.Authorized -> return pinState

                PlexPinState.Expired -> {
                    _state.value = PlexSignInFlow.afterPinPoll(pinState, awaiting)
                    return null
                }

                // Nothing changed, so nothing is published: re-emitting the same
                // state would reload the QR image every two seconds.
                PlexPinState.Pending -> {}
            }
        }
    }

    private suspend fun discoverServers() {
        val resources = try {
            authClient.getResources()
        } catch (e: IOException) {
            Log.d(TAG, "could not discover servers", e)
            _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
            return
        } catch (e: HttpException) {
            // A 401 from a token that stopped working is a network failure, not an
            // empty account.
            Log.d(TAG, "discovering servers returned HTTP ${e.code()}")
            _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
            return
        }

        _state.value = PlexSignInFlow.afterResources(resources)
    }

    private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
