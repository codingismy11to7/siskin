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

    /** Safe to call repeatedly; does nothing once a pin is live. */
    fun start() {
        if (pinId != null) return
        createPin()
    }

    fun retry() {
        handler.removeCallbacksAndMessages(null)
        pinId = null
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
        handler.removeCallbacksAndMessages(null)
    }

    private fun createPin() {
        authClient.createPin().enqueue(object : Callback<Pin> {
            override fun onResponse(call: Call<Pin>, response: Response<Pin>) {
                val pin = response.body()
                val next = PlexSignInFlow.afterPinCreated(pin)
                _state.value = next

                if (next is PlexSignInState.AwaitingApproval) {
                    pinId = pin?.id
                    pollStartedAtEpochSeconds = nowEpochSeconds()
                    schedulePoll()
                }
            }

            override fun onFailure(call: Call<Pin>, t: Throwable) {
                Log.d(TAG, "could not create a pin", t)
                _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
            }
        })
    }

    private fun schedulePoll() = handler.postDelayed({ poll() }, POLL_INTERVAL_MS)

    private fun poll() {
        val id = pinId ?: return
        val awaiting = _state.value as? PlexSignInState.AwaitingApproval ?: return

        if (!PlexPinState.shouldKeepPolling(
                pollStartedAtEpochSeconds,
                nowEpochSeconds(),
                awaiting.expiresAtEpochSeconds
            )
        ) {
            _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_expired)
            return
        }

        authClient.getPin(id).enqueue(object : Callback<Pin> {
            override fun onResponse(call: Call<Pin>, response: Response<Pin>) {
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

                val next = PlexSignInFlow.afterPinPoll(pinState, awaiting)
                _state.value = next
                if (next === awaiting) schedulePoll()
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
