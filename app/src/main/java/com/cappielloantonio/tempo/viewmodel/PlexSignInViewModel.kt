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

    /**
     * Bumped at every point that abandons work already issued. Every request
     * captures this value at the instant it is *issued*, and its callback returns
     * without publishing state or scheduling anything once the field has moved on.
     *
     * Draining the Handler queue is not enough on its own, because a Retrofit call
     * in flight is not a queued message. Without the counter, retry() reopens the
     * duplicate-loop bug through a different door than onCleared() did:
     *
     *  1. poll() enqueues getPin #A.
     *  2. retry() drains the queue, clears pinId and `creating`, publishes
     *     Working, and issues createPin #B.
     *  3. #B's onResponse assigns the *new* pinId, publishes a new
     *     AwaitingApproval, and schedules loop 1.
     *  4. #A comes back late -- onFailure, or the non-2xx branch -- and calls
     *     schedulePoll(), posting a message *after* the drain.
     *  5. Two seconds later poll() finds the new pinId and the new
     *     AwaitingApproval, passes both guards, and loop 2 is self-sustaining
     *     alongside loop 1 for the rest of the hard cap: two pin polls every two
     *     seconds, forever, against plex.tv.
     *
     * A boolean "stopped" flag cannot close that -- retry() has to keep polling,
     * so there is no moment at which the loop is legitimately off. Only a value
     * that says *which attempt* a callback belongs to can. That the same mechanism
     * also subsumes onCleared() (after it, no captured value ever matches again)
     * is why there is one counter here and not a counter plus a flag. Do not
     * "simplify" this back into a boolean.
     *
     * No synchronization: PlexRetrofitFactory does not override Retrofit's
     * callback executor, so on Android every callback -- and therefore every read
     * and write of this field -- runs on the main thread, as do onCleared(),
     * retry() and the Handler's own messages.
     */
    private var generation = 0

    /** Safe to call repeatedly; does nothing once a pin is live or on its way. */
    fun start() {
        // `creating` covers the createPin round trip, during which pinId is still
        // null. Without it, two start() calls inside that window -- the fragment
        // being recreated while the first request is outstanding, which is the
        // case this ViewModel exists to survive -- both pass the guard, issue two
        // pins, and leave two independent poll loops running. `generation` does
        // not replace this: it stops a stale callback from landing, not a second
        // request from being issued.
        if (pinId != null || creating) return
        createPin()
    }

    fun retry() {
        // Abandons the previous attempt. The drain covers what is already in the
        // message queue; the bump covers the calls still in flight, whose
        // callbacks would otherwise reschedule into the attempt started below.
        generation++
        handler.removeCallbacksAndMessages(null)
        pinId = null
        // A retry abandons whatever was in flight, so the guard must not outlive
        // it; createPin() below sets it again for the new request.
        creating = false
        _state.value = PlexSignInState.Working
        createPin()
    }

    fun chooseServer(resource: Resource) {
        // Picking a server supersedes any earlier pick: the assignments below are
        // the very inputs an outstanding getSections was built from, so its result
        // describes a server the user is no longer signing in to. Without the
        // bump, a second tap leaves whichever response lands last in charge --
        // possibly a ChoosingLibrary listing server A's sections while api.serverUri
        // already points at server B, or a late onFailure blanking B's result.
        generation++
        val issuedGeneration = generation

        api.serverUri = AuthClient.bestConnectionUri(resource)
        // Null for a server the account owns; serverHeaders() falls back to the
        // account token in that case.
        api.serverToken = resource.accessToken
        _state.value = PlexSignInState.Working

        // Constructed *after* the assignments above on purpose: LibraryClient
        // pins api.serverUri at construction time and never re-reads it.
        LibraryClient(api).getSections().enqueue(object : Callback<PlexResponse> {
            override fun onResponse(call: Call<PlexResponse>, response: Response<PlexResponse>) {
                if (issuedGeneration != generation) return

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
                if (issuedGeneration != generation) return

                Log.d(TAG, "could not read sections from the chosen server", t)
                _state.value =
                    PlexSignInState.Failed(R.string.plex_sign_in_error_server_unreachable)
            }
        })
    }

    fun chooseLibrary(section: Directory) {
        // Done is terminal, and the bump is what makes that true of the state as
        // well as of the flow: anything still outstanding is abandoned here, so no
        // late callback can publish a Failed over a finished sign-in.
        generation++
        api.musicSectionKey = section.key
        _state.value = PlexSignInState.Done
    }

    override fun onCleared() {
        super.onCleared()
        // Same two halves as retry(): the bump invalidates calls in flight, the
        // drain removes messages already posted.
        generation++
        handler.removeCallbacksAndMessages(null)
    }

    private fun createPin() {
        val issuedGeneration = generation
        creating = true
        authClient.createPin().enqueue(object : Callback<Pin> {
            override fun onResponse(call: Call<Pin>, response: Response<Pin>) {
                // Returning *before* touching `creating` is deliberate: the flag
                // now describes the request the current generation issued, and a
                // stale callback clearing it would let start() issue a third pin
                // while the retry's own createPin is still outstanding.
                if (issuedGeneration != generation) return
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
                    schedulePoll(issuedGeneration)
                } else {
                    _state.value = next
                }
            }

            override fun onFailure(call: Call<Pin>, t: Throwable) {
                if (issuedGeneration != generation) return
                creating = false

                Log.d(TAG, "could not create a pin", t)
                _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
            }
        })
    }

    private fun schedulePoll(issuedGeneration: Int) {
        // Every reschedule funnels through here, and the message it posts carries
        // the generation of the attempt that asked for it. The check is at the far
        // end, in poll(): two seconds separate posting from running, so testing
        // the counter here would say nothing about the moment the message is
        // actually consumed.
        handler.postDelayed({ poll(issuedGeneration) }, POLL_INTERVAL_MS)
    }

    private fun poll(issuedGeneration: Int) {
        // onCleared() and retry() drain this message out of the queue as well, but
        // chooseServer()/chooseLibrary() bump without draining, and a message
        // outliving its attempt is exactly what starts a second loop.
        if (issuedGeneration != generation) return

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

        // issuedGeneration was just checked against the field and nothing since
        // then can have moved it, so it is the value current at the instant this
        // call is issued -- which is what the callbacks below have to compare to.
        authClient.getPin(id).enqueue(object : Callback<Pin> {
            override fun onResponse(call: Call<Pin>, response: Response<Pin>) {
                // A response for an attempt that retry() or onCleared() has since
                // abandoned. Publishing here would drop a Failed(expired) over a
                // state retry() already moved past, and rescheduling here is the
                // second loop itself.
                if (issuedGeneration != generation) return

                // Same treatment as a dropped poll, for the same reason: a 429,
                // a 5xx or a 404 on a consumed pin is not worth abandoning a
                // sign-in over, and the bound above still ends the loop.
                if (!response.isSuccessful) {
                    Log.d(TAG, "pin poll returned HTTP ${response.code()}, retrying")
                    schedulePoll(issuedGeneration)
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
                    discoverServers(issuedGeneration)
                    return
                }

                // Assigned only when it actually changed. afterPinPoll returns
                // `current` by identity so a poll that changes nothing does not
                // re-emit, but setValue has no such short-circuit -- publishing
                // unconditionally would reload the QR image every two seconds.
                val next = PlexSignInFlow.afterPinPoll(pinState, awaiting)
                if (next === awaiting) schedulePoll(issuedGeneration) else _state.value = next
            }

            override fun onFailure(call: Call<Pin>, t: Throwable) {
                // Stale, so not "a poll of the live pin" at all: rescheduling here
                // is what puts a second loop alongside the one retry() started.
                if (issuedGeneration != generation) return

                // A dropped poll is not a failed sign-in -- the pin is still live
                // and the bound above is what eventually gives up.
                Log.d(TAG, "pin poll failed, retrying", t)
                schedulePoll(issuedGeneration)
            }
        })
    }

    private fun discoverServers(issuedGeneration: Int) {
        authClient.getResources().enqueue(object : Callback<List<Resource>> {
            override fun onResponse(
                call: Call<List<Resource>>,
                response: Response<List<Resource>>
            ) {
                if (issuedGeneration != generation) return

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
                if (issuedGeneration != generation) return

                Log.d(TAG, "could not discover servers", t)
                _state.value = PlexSignInState.Failed(R.string.plex_sign_in_error_network)
            }
        })
    }

    private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
