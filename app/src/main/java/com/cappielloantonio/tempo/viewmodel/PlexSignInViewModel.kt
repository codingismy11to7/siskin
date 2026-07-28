package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.auth.PlexPinState
import com.cappielloantonio.tempo.plex.auth.PlexSignInFlow
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

private const val TAG = "PlexSignInViewModel"
private const val POLL_INTERVAL_MS = 2_000L

/**
 * Drives the Plex PIN flow.
 *
 * Deliberately callback-based rather than coroutine-based: this project has no
 * coroutines dependency and no lifecycle-ktx, and every other async path in it
 * is a Retrofit enqueue. Living in a ViewModel is what lets the poll loop
 * survive fragment recreation instead of restarting and orphaning a pin.
 */
class PlexSignInViewModel(application: Application) : AndroidViewModel(application) {

    private val api = PlexApi()
    private val authClient = AuthClient(api)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableLiveData<PlexSignInState>(PlexSignInState.Working)
    val state: LiveData<PlexSignInState> get() = _state

    private var pinId: Long? = null
    private var pollStartedAtEpochSeconds = 0L
    private var creating = false
    private var cleared = false

    /** Safe to call repeatedly; does nothing once a pin is live or on its way. */
    fun start() {
        // `creating` covers the createPin round trip, during which pinId is still
        // null. Without it, two start() calls inside that window -- the fragment
        // being recreated while the first request is outstanding, which is the
        // case this ViewModel exists to survive -- both pass the guard, issue two
        // pins, and leave two independent poll loops running.
        if (pinId != null || creating) return
        createPin()
    }

    fun retry() {
        handler.removeCallbacksAndMessages(null)
        pinId = null
        // A retry abandons whatever was in flight, so the guard must not outlive
        // it; createPin() below sets it again for the new request.
        creating = false
        _state.value = PlexSignInState.Working
        createPin()
    }

    fun chooseServer(resource: Resource) {
        api.serverUri = AuthClient.bestConnectionUri(resource)
        // Null for a server the account owns; serverHeaders() falls back to the
        // account token in that case.
        api.serverToken = resource.accessToken
        _state.value = PlexSignInState.Working

        // Constructed *after* the assignments above on purpose: LibraryClient
        // pins api.serverUri at construction time and never re-reads it.
        LibraryClient(api).getSections().enqueue(object : Callback<PlexResponse> {
            override fun onResponse(call: Call<PlexResponse>, response: Response<PlexResponse>) {
                // An error status has a null body, and afterSections would read
                // that as "this server has no music libraries". A 401 from a
                // stale token is an unreachable server, not an empty one.
                if (!response.isSuccessful) {
                    Log.d(TAG, "reading sections returned HTTP ${response.code()}")
                    _state.value =
                        PlexSignInState.Failed(R.string.plex_sign_in_error_server_unreachable)
                    return
                }

                _state.value = PlexSignInFlow.afterSections(response.body())
            }

            override fun onFailure(call: Call<PlexResponse>, t: Throwable) {
                Log.d(TAG, "could not read sections from the chosen server", t)
                _state.value =
                    PlexSignInState.Failed(R.string.plex_sign_in_error_server_unreachable)
            }
        })
    }

    fun chooseLibrary(section: Directory) {
        api.musicSectionKey = section.key
        _state.value = PlexSignInState.Done
    }

    override fun onCleared() {
        super.onCleared()
        cleared = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun createPin() {
        creating = true
        authClient.createPin().enqueue(object : Callback<Pin> {
            override fun onResponse(call: Call<Pin>, response: Response<Pin>) {
                creating = false

                if (!response.isSuccessful) {
                    Log.d(TAG, "creating a pin returned HTTP ${response.code()}")
                    _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
                    return
                }

                val pin = response.body()
                val next = PlexSignInFlow.afterPinCreated(pin)

                if (next is PlexSignInState.AwaitingApproval) {
                    // The pin and the poll clock are set *before* publishing:
                    // setValue dispatches synchronously, so an observer would
                    // otherwise run against a half-built poll loop.
                    pinId = pin?.id
                    pollStartedAtEpochSeconds = nowEpochSeconds()
                    _state.value = next
                    schedulePoll()
                } else {
                    _state.value = next
                }
            }

            override fun onFailure(call: Call<Pin>, t: Throwable) {
                creating = false
                Log.d(TAG, "could not create a pin", t)
                _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
            }
        })
    }

    private fun schedulePoll() {
        // Every reschedule goes through here so that one flag can stop the loop.
        // onCleared can only drain the queue: a getPin already in flight comes
        // back afterwards through onFailure, which reschedules, and poll() then
        // finds pinId and the retained LiveData value still intact. Without this
        // guard that alone restarts the loop for the rest of the hard cap, long
        // after the user backed out of sign-in.
        if (cleared) return
        handler.postDelayed({ poll() }, POLL_INTERVAL_MS)
    }

    private fun poll() {
        val id = pinId ?: return
        val awaiting = _state.value as? PlexSignInState.AwaitingApproval ?: return

        if (!PlexPinState.shouldKeepPolling(
                pollStartedAtEpochSeconds,
                nowEpochSeconds(),
                awaiting.expiresAtEpochSeconds
            )
        ) {
            // Routed through the flow rather than mapped here so that "the pin
            // ran out" has exactly one translation into a message, the same one
            // a server-reported expiry takes below.
            _state.value = PlexSignInFlow.afterPinPoll(PlexPinState.Expired, awaiting)
            return
        }

        authClient.getPin(id).enqueue(object : Callback<Pin> {
            override fun onResponse(call: Call<Pin>, response: Response<Pin>) {
                // Same treatment as a dropped poll, for the same reason: a 429,
                // a 5xx or a 404 on a consumed pin is not worth abandoning a
                // sign-in over, and the bound above still ends the loop.
                if (!response.isSuccessful) {
                    Log.d(TAG, "pin poll returned HTTP ${response.code()}, retrying")
                    schedulePoll()
                    return
                }

                val pin = response.body()
                val pinState = PlexPinState.evaluate(
                    pin?.authToken,
                    pin?.let { AuthClient.expiresAtEpochSeconds(it) },
                    nowEpochSeconds()
                )

                if (pinState is PlexPinState.Authorized) {
                    api.accountToken = pinState.authToken
                    _state.value = PlexSignInState.Working
                    discoverServers()
                    return
                }

                // Assigned only when it actually changed. afterPinPoll returns
                // `current` by identity so a poll that changes nothing does not
                // re-emit, but setValue has no such short-circuit -- publishing
                // unconditionally would reload the QR image every two seconds.
                val next = PlexSignInFlow.afterPinPoll(pinState, awaiting)
                if (next === awaiting) schedulePoll() else _state.value = next
            }

            override fun onFailure(call: Call<Pin>, t: Throwable) {
                // A dropped poll is not a failed sign-in -- the pin is still live
                // and the bound above is what eventually gives up.
                Log.d(TAG, "pin poll failed, retrying", t)
                schedulePoll()
            }
        })
    }

    private fun discoverServers() {
        authClient.getResources().enqueue(object : Callback<List<Resource>> {
            override fun onResponse(
                call: Call<List<Resource>>,
                response: Response<List<Resource>>
            ) {
                // An error status has a null body, and afterResources would read
                // that as "this account has no servers". A 401 from a token that
                // stopped working is a network failure, not an empty account.
                if (!response.isSuccessful) {
                    Log.d(TAG, "discovering servers returned HTTP ${response.code()}")
                    _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
                    return
                }

                _state.value = PlexSignInFlow.afterResources(response.body())
            }

            override fun onFailure(call: Call<List<Resource>>, t: Throwable) {
                Log.d(TAG, "could not discover servers", t)
                _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
            }
        })
    }

    private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
