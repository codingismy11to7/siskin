package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.os.UserManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.toNonEmptyListOrNull
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.HomeUserSwitch
import com.cappielloantonio.tempo.plex.LibrarySelection
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexIdentity
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.SwitchOutcome
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.CreatePinError
import com.cappielloantonio.tempo.plex.api.auth.CreatedPin
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.api.server.ServerProbe
import com.cappielloantonio.tempo.plex.auth.PendingAccount
import com.cappielloantonio.tempo.plex.auth.PlexPinState
import com.cappielloantonio.tempo.plex.auth.PlexSignInFlow
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.auth.SignInError
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.plex.models.refusesAccountToken
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.util.CredentialGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PlexSignInViewModel"

/**
 * Drives the Plex PIN flow.
 *
 * Living in a ViewModel is what lets the poll loop survive fragment recreation
 * instead of restarting and orphaning a pin.
 */
class PlexSignInViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val api: PlexApi = PlexApi(),
        /** Creates and polls the PIN; nothing else, since no account is pending yet. */
        private val authClient: AuthClient = AuthClient(api),
        /**
         * plex.tv as the pending account. Every call after the PIN goes through
         * this rather than [authClient], whose token is the stored one and may
         * be someone else's until the commit.
         */
        private val authClientFor: (String) -> AuthClient = { token -> AuthClient(api, token) },
        /** The probe is unauthenticated; the identity headers are courtesy, not access. */
        private val probe: ServerProbe =
            ServerProbe(
                headers = PlexIdentity.headers(api.clientIdentifier, api.appVersion, null, api.language, api.vehicle),
            ),
        /** Records the server's other addresses when [commit] writes a session. */
        private val addressBook: ServerAddressBook = ServerAddressBook.shared,
        /** The network half of becoming another Home member; see [HomeUserSwitch]. */
        private val homeUserSwitch: HomeUserSwitch = HomeUserSwitch(api),
        /**
         * Seam, not a feature. The poll loop's bounds are measured in wall-clock
         * seconds, which a StandardTestDispatcher cannot advance -- so without this
         * the hard cap and the backoff ladder are both untestable, and a test that
         * tried would hang rather than fail. Deleting it silently un-tests them.
         */
        private val nowMillis: () -> Long = System::currentTimeMillis,
    ) : AndroidViewModel(application) {
        private val _state = MutableLiveData<PlexSignInState>(PlexSignInState.Disconnected)
        val state: LiveData<PlexSignInState> get() = _state

        /**
         * Publishes a state directly, for tests that need to start from the middle
         * of the flow without driving every step that leads there.
         *
         * Deliberately not a general setter: [state] stays read-only to production
         * code, and every real transition still goes through the function that owns
         * it.
         */
        @VisibleForTesting
        internal fun setStateForTest(state: PlexSignInState) {
            _state.value = state
        }

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

        /** The server picked but not yet committed; chooseLibrary needs it to build a session. */
        private var candidate: Pair<String, Resource>? = null

        /**
         * Chooses the screen for how it was opened.
         *
         * [forceSignIn] exists because CredentialGate.isSignedIn() is only
         * `session != null`, and the credentials-rejected path arrives with a
         * session that still exists and is no longer accepted. Without this flag
         * the one entry point that exists to recover a dead session is the one that
         * cannot: it would land on the settings screen.
         *
         * Forcing is unconditional by construction, not by relying on connect()'s
         * own guard: it cancels whatever attempt is outstanding and resets to
         * Disconnected first, *then* calls connect(). connect()'s guard exists to
         * stop a caller starting a second sign-in over one already in flight --
         * see its own KDoc -- which is a different situation from this one:
         * forceSignIn means the credentials this ViewModel currently believes in
         * are wrong, whatever state that belief happens to be published as
         * (typically Connected, reached via a plain `open(false)` moments
         * earlier). Delegating straight to connect() would let its Disconnected
         * check silently swallow exactly the call this flag exists to guarantee.
         * Today the only caller is CarHostActivity.onCreate on a freshly
         * constructed ViewModel, where connect()'s guard would have been a no-op
         * anyway -- but nothing enforces that this stays the only caller, and a
         * future re-entry point (e.g. onNewIntent) must reach Working here even
         * when the ViewModel is already Connected.
         */
        fun open(forceSignIn: Boolean) {
            if (forceSignIn) {
                attempt?.cancel()
                _state.value = PlexSignInState.Disconnected
                connect()
                return
            }
            _state.value =
                if (CredentialGate.isSignedIn()) {
                    PlexSignInState.Connected
                } else {
                    PlexSignInState.Disconnected
                }
        }

        /**
         * Opens the server picker from the debug screen, with no PIN.
         *
         * This is `signIn()` without its first half. The account token is all
         * plex.tv needs to list an account's servers, and `PlexApi.session`'s
         * setter deliberately leaves that token alone when clearing a session --
         * so a signed-in app always has one, and there is nothing to approve.
         * That stored token is the pass's pending account.
         *
         * Publishes exactly the picker sign-in publishes, with nothing marking it
         * as having come from elsewhere. Getting back out of it is the fragment
         * back stack's job: the debug screen pushes the picker rather than letting
         * the router swap to it, so back pops to the debug screen the ordinary way
         * -- see [com.cappielloantonio.tempo.ui.fragment.CarDebugFragment]. An
         * earlier draft carried a `returnsToSettings` flag on the state instead and
         * hardcoded where back landed, which reimplemented the back stack badly and
         * could only name a destination the state machine happened to have.
         *
         * Failures land in Failed exactly as signIn's do. That is right here for
         * the same reason it is right there -- both errors this can raise are
         * account-scoped rather than about one server -- even though Failed's
         * retry re-enters the PIN flow rather than this entry point.
         */
        fun reopenServerPicker() {
            attempt?.cancel()
            val account = storedAccount() ?: return
            _state.value = PlexSignInState.Working

            attempt =
                viewModelScope.launch {
                    either {
                        val resources = authClientFor(account.token).getResources().mapLeft(SignInError::Api).bind()

                        val servers =
                            ensureNotNull(
                                AuthClient.mediaServers(resources).toNonEmptyListOrNull(),
                            ) { SignInError.NoServers }

                        _state.value = PlexSignInState.ChoosingServer(servers, account)
                    }.onLeft { _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(it)) }
                }
        }

        /**
         * Settings' Switch user: the Home picker, without a PIN, marking who is
         * signed in now. Pushed like [reopenServerPicker], for the same reason.
         *
         * Not knowing who is signed in fails the picker rather than leaving
         * nobody marked: an unmarked list lets the user "switch" to themselves,
         * which spends a switch and clears the queue for nothing.
         */
        fun reopenUserPicker() {
            attempt?.cancel()
            val account = storedAccount() ?: return
            _state.value = PlexSignInState.Working

            attempt =
                viewModelScope.launch {
                    val client = authClientFor(account.token)
                    // Both answered before either is bound: a raise inside an
                    // async would cross the coroutine boundary.
                    val (home, current) =
                        coroutineScope {
                            val home = async { client.homeUsers() }
                            val current = async { client.currentUser() }
                            home.await() to current.await()
                        }
                    either {
                        val users = home.mapLeft(SignInError::Api).bind().toNonEmptyListOrNull()
                        val uuid = current.mapLeft(SignInError::Api).bind().uuid
                        _state.value =
                            if (users == null || uuid == null) {
                                // Settings offers this only for a Home of two or
                                // more, and every account has a uuid, so either
                                // missing is plex.tv misanswering.
                                PlexSignInState.Failed(R.string.plex_sign_in_error_network)
                            } else {
                                PlexSignInState.ChoosingUser(users, account, uuid)
                            }
                    }.onLeft { _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(it)) }
                }
        }

        /**
         * The stored account, as the pending one for a pass that starts signed
         * in. Normally present -- these entry points are reached from settings
         * -- and Failed rather than a dead screen when it is not.
         */
        private fun storedAccount(): PendingAccount? {
            val token = api.accountToken
            if (token == null) {
                Log.d(TAG, "picker reopened with no account token on record")
                _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(SignInError.NoCandidate))
                return null
            }
            return PendingAccount(token)
        }

        fun chooseUser(user: HomeUser) {
            val picker = _state.value as? PlexSignInState.ChoosingUser ?: return
            if (user.uuid == picker.currentUuid) return
            if (user.isProtected == true) {
                _state.value = PlexSignInState.EnteringPin(user, picker.users, picker.account, picker.currentUuid)
                return
            }
            switchTo(user, null, picker.users, picker.account, picker.currentUuid)
        }

        fun enterPin(pin: String) {
            val pad = _state.value as? PlexSignInState.EnteringPin ?: return
            switchTo(pad.user, pin, pad.users, pad.account, pad.currentUuid)
        }

        private fun switchTo(
            user: HomeUser,
            pin: String?,
            users: NonEmptyList<HomeUser>,
            from: PendingAccount,
            currentUuid: String?,
        ) {
            attempt?.cancel()
            _state.value = PlexSignInState.Working

            attempt =
                viewModelScope.launch {
                    // Only settings keeps the saved server -- sign-in always
                    // runs the pickers -- and only settings sets currentUuid.
                    val saved = if (currentUuid != null) api.session else null
                    homeUserSwitch
                        .switch(from, user, pin, saved)
                        .fold(
                            { error ->
                                val message = PlexSignInFlow.messageFor(error)
                                _state.value =
                                    if (pin != null && (error == SignInError.WrongPin || error == SignInError.RateLimited)) {
                                        PlexSignInState.EnteringPin(user, users, from, currentUuid, message)
                                    } else {
                                        PlexSignInState.ChoosingUser(users, from, currentUuid, message)
                                    }
                            },
                            { afterSwitch(it) },
                        )
                }
        }

        @OptIn(UnstableApi::class)
        private fun afterSwitch(outcome: SwitchOutcome) {
            when (outcome) {
                is SwitchOutcome.NeedsServer -> {
                    _state.value = PlexSignInState.ChoosingServer(outcome.servers, outcome.account)
                }

                is SwitchOutcome.NeedsLibrary -> {
                    candidate = outcome.serverUri to outcome.resource
                    _state.value = PlexSignInState.ChoosingLibrary(outcome.sections, outcome.servers, outcome.account)
                }

                is SwitchOutcome.Committable -> {
                    commit(outcome.serverUri, outcome.resource, outcome.sectionKey, outcome.account)
                }
            }
        }

        /**
         * The Connect button.
         *
         * Two guards, inherited from start(). The fragment's own dispatch is the
         * primary defence -- it only calls connect() when the state is
         * Disconnected, and routes anywhere else to retry() -- but this checks the
         * same thing again rather than trusting the caller, since a test (and any
         * future caller) can call this directly. The isActive check alone is not
         * enough: once signIn() has published a picker it has run to *completion*,
         * so isActive is false, and without the state check a second call here
         * would slip past it and start a second sign-in over a live one --
         * discarding an account token that is still good. Same failure mode as
         * #24, which is what put both guards on start() in the first place.
         * PlexSignInViewModelTest has a test that calls this twice in a row and
         * asserts the picker state survives.
         */
        fun connect() {
            // Asked before anything is attempted: on a guest profile the platform
            // will refuse to add the account at the end of a successful PIN flow,
            // and a failure that late is indistinguishable from Plex being down.
            val users = App.getContext().getSystemService(UserManager::class.java)
            if (users?.hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS) == true) {
                _state.value = PlexSignInState.SignInNotAllowed
                return
            }

            if (attempt?.isActive == true) return
            if (_state.value !is PlexSignInState.Disconnected) return
            _state.value = PlexSignInState.Working
            signIn()
        }

        fun retry() {
            _state.value = PlexSignInState.Working
            signIn()
        }

        /**
         * Whether [backPressed] would act on this state rather than doing
         * nothing. [com.cappielloantonio.tempo.ui.fragment.PlexSignInFragment]'s
         * `OnBackPressedCallback` stays enabled exactly when this is true, so
         * [PlexSignInState.Disconnected] -- which has nowhere in the flow to go
         * back to -- falls through to the platform default (finishing the
         * activity) instead of an enabled-but-inert callback swallowing the press
         * and going nowhere.
         *
         * [PlexSignInState.SignInNotAllowed] answers false too: it is reached
         * directly from [PlexSignInState.Disconnected] by [connect] before
         * anything is attempted, so -- like Disconnected itself -- there is no
         * in-flow step underneath it to undo.
         *
         * [PlexSignInState.Connected] answers false for the same reason, and is
         * the one case where the answer is not what enables anything:
         * [com.cappielloantonio.tempo.ui.fragment.CarSettingsFragment] is the
         * screen that state routes to and it registers no callback at all, so back
         * reaches the platform default there by having nothing in its way. The
         * branch stays because this function is a statement about the states, not
         * about one fragment's wiring -- and because a settings screen that later
         * did want a callback should find the answer already written down.
         *
         * [PlexSignInState.Done] is in that group
         * too: it is a one-tick pass-through to [com.cappielloantonio.tempo.interfaces.LoginHost.onLoginSuccess]
         * (see [com.cappielloantonio.tempo.ui.fragment.PlexSignInFragment.render]'s
         * `Done` branch), never a state a user is looking at, so "leave" is as
         * good an answer as any and "go back into signing in" would be actively
         * wrong.
         *
         * A `when` over the sealed state rather than deriving this from
         * [backPressed]'s own branches: keeping both exhaustive means the
         * compiler forces a decision here whenever [PlexSignInState] grows a new
         * case, instead of a new state silently defaulting to whichever side is
         * convenient.
         */
        fun handlesBackPress(state: PlexSignInState): Boolean =
            when (state) {
                is PlexSignInState.Working,
                is PlexSignInState.AwaitingApproval,
                is PlexSignInState.ChoosingServer,
                is PlexSignInState.ChoosingLibrary,
                is PlexSignInState.ChoosingUser,
                is PlexSignInState.EnteringPin,
                is PlexSignInState.Failed,
                -> true

                PlexSignInState.Disconnected,
                PlexSignInState.SignInNotAllowed,
                PlexSignInState.Connected,
                PlexSignInState.Done,
                -> false
            }

        /**
         * Back within the sign-in flow: undoes one step instead of leaving the
         * screen outright. The motivating case is a misclick -- landing in the
         * library picker for the wrong server and wanting to correct it without
         * redoing the whole PIN flow.
         *
         * | From | Goes to |
         * |---|---|
         * | [PlexSignInState.ChoosingLibrary] | [PlexSignInState.ChoosingServer], the same server list, no message |
         * | [PlexSignInState.EnteringPin] | [PlexSignInState.ChoosingUser], the same member list, no message |
         * | [PlexSignInState.ChoosingUser], [PlexSignInState.ChoosingServer], [PlexSignInState.AwaitingApproval], [PlexSignInState.Failed], [PlexSignInState.Working] | [PlexSignInState.Disconnected] |
         * | anything [handlesBackPress] reports false for | nothing |
         *
         * These are the *flow's* answers, and they assume the flow is what the
         * user is in. The debug screen reaches this picker without being in it, and
         * back there must return to the debug screen instead -- which is why that
         * route pushes the fragment onto the back stack and
         * [com.cappielloantonio.tempo.ui.fragment.PlexSignInFragment] stops
         * claiming the press for [PlexSignInState.ChoosingServer] when it was
         * pushed. Nothing about that reaches this function: the back stack owns
         * that navigation, and this stays a statement about the flow.
         *
         * The library-picker case reuses the server list [PlexSignInState.ChoosingLibrary]
         * now carries rather than re-deriving it, and deliberately omits
         * `messageRes`: arriving here by pressing back is the user correcting
         * their own pick, not Plex rejecting anything, so there is nothing to
         * report -- see the KDoc on [PlexSignInState.ChoosingServer.messageRes].
         *
         * Every other consumed case lands on Disconnected and cancels [attempt]
         * first: that job describes the PIN poll or the server probe/getSections
         * call the user is abandoning by backing out, and letting it run to
         * completion could publish a state over the Disconnected this call just
         * asked for. Cancelling also drops `attempt?.isActive` to false, which
         * together with the state now reading Disconnected is what lets
         * [connect] be pressed again immediately instead of its own guards
         * silently eating the retry.
         */
        fun backPressed(): Boolean {
            val current = _state.value ?: return false
            return when (current) {
                is PlexSignInState.ChoosingLibrary -> {
                    _state.value = PlexSignInState.ChoosingServer(current.servers, current.account)
                    true
                }

                is PlexSignInState.EnteringPin -> {
                    attempt?.cancel()
                    _state.value = PlexSignInState.ChoosingUser(current.users, current.account, current.currentUuid)
                    true
                }

                is PlexSignInState.ChoosingUser,
                is PlexSignInState.ChoosingServer,
                is PlexSignInState.AwaitingApproval,
                is PlexSignInState.Failed,
                is PlexSignInState.Working,
                -> {
                    attempt?.cancel()
                    _state.value = PlexSignInState.Disconnected
                    true
                }

                PlexSignInState.Disconnected,
                PlexSignInState.SignInNotAllowed,
                PlexSignInState.Connected,
                PlexSignInState.Done,
                -> {
                    false
                }
            }
        }

        /**
         * Drops the session and returns to [PlexSignInState.Disconnected] rather
         * than closing the screen. Someone who just signed out is plausibly here to
         * sign in as someone else; closing would make them find the gear again.
         *
         * Clears both `api.session` and `api.accountToken` -- deliberately more
         * than the session setter does on its own. `PlexApi.session`'s setter
         * leaves the account token alone when clearing, and that is correct for
         * its other caller, the library-switch case in `chooseLibrary`: the
         * account is not changing, so the PIN grant backing that token is still
         * good and plex.tv calls still need it. Sign out means the opposite --
         * the account itself is being disowned -- so leaving the token behind
         * would mean the next createPin()/getPin() silently carries the previous
         * account's X-Plex-Token, and CredentialGate.isSignedIn() would read
         * false while a real credential still sat in the system account.
         *
         * Stopping playback and invalidating the browse tree belong to the host --
         * see LoginHost.onSignedOut -- because this class has no business knowing
         * about the media session.
         */
        fun signOut() {
            attempt?.cancel()
            api.session = null
            api.accountToken = null
            _state.value = PlexSignInState.Disconnected
        }

        fun chooseServer(resource: Resource) {
            // Read before the overwrite below, because the state is the only place
            // this list lives -- a parallel field would give it two owners. If this
            // line ever moves under the assignment it becomes permanently null and
            // #18 is silently back; PlexSignInViewModelTest's three recovery tests
            // are what hold it here. Serves two purposes below: restoring the
            // picker on rejection (unchanged), and now also carried forward into a
            // successful ChoosingLibrary so backPressed() has a list to return to
            // -- still the state's one read, not a second copy of it. The pending
            // account is read with it, for the same reason.
            val picker = _state.value as? PlexSignInState.ChoosingServer

            // Picking a server supersedes the poll loop and any earlier pick: an
            // outstanding probe or sections call describes a server the user is no
            // longer signing in to.
            attempt?.cancel()
            _state.value = PlexSignInState.Working

            attempt =
                viewModelScope.launch {
                    either {
                        // Nothing is written here. The session is committed whole in
                        // chooseLibrary, so an abandoned sign-in leaves the previous one
                        // untouched rather than half-cleared.
                        //
                        // A null picker means chooseServer was reached from a
                        // state that is not the picker, which the UI cannot
                        // currently do; onLeft turns it into Failed.
                        val from = ensureNotNull(picker) { SignInError.NoCandidate }

                        // Checked before the probe, which can spend seconds racing
                        // addresses of a server that would be refused anyway.
                        ensure(!resource.refusesAccountToken) { SignInError.ServerTokenMissing }

                        // The probe answering with nothing is the same failure a dead call
                        // is, so it reports as the same thing rather than via a second case.
                        val uri =
                            ensureNotNull(probe.bestConnectionUri(resource)) {
                                Log.d(TAG, "no advertised connection answered for ${resource.name}")
                                SignInError.Api(PlexTransportFailure.Unreachable(PlexHost.Server))
                            }

                        candidate = uri to resource

                        // Built from the candidate directly: nothing is persisted until
                        // chooseLibrary commits a whole PlexSession, so there is no
                        // window in which a reader could see a mixed set.
                        val response =
                            LibraryClient(api, uri, resource.accessToken) { from.account.token }
                                .getSections()
                                .mapLeft(SignInError::Api)
                                .bind()

                        val sections =
                            ensureNotNull(
                                LibraryClient.musicSections(response).toNonEmptyListOrNull(),
                            ) { SignInError.NoLibraries }

                        _state.value = PlexSignInState.ChoosingLibrary(sections, from.servers, from.account)
                    }.onLeft { error ->
                        // Unconditional, and that is the whole decision: every failure
                        // raised in the block above is about the server just picked --
                        // the probe finding nothing, getSections failing, no music
                        // section. None of them says anything about the account token,
                        // so none of them justifies Failed, whose only exit re-creates
                        // the pin. The account-scoped errors (NoPinCode, PinExpired,
                        // NoServers) are raised in signIn and still land in Failed
                        // through its own onLeft.
                        //
                        // Failed remains the fallback for the one case with nothing to
                        // go back to: chooseServer reached from a state that is not the
                        // picker, which the UI cannot currently do.
                        val message = PlexSignInFlow.messageFor(error)
                        _state.value =
                            if (picker != null) {
                                PlexSignInState.ChoosingServer(picker.servers, picker.account, message)
                            } else {
                                PlexSignInState.Failed(message)
                            }
                    }
                }
        }

        @OptIn(UnstableApi::class)
        fun chooseLibrary(section: Directory) {
            // Done is terminal, and cancelling is what makes that true of the state as
            // well as of the flow: nothing still outstanding can publish a Failed over a
            // finished sign-in.
            attempt?.cancel()

            // Normally unreachable: section rows are only drawn for ChoosingLibrary.
            val account =
                (_state.value as? PlexSignInState.ChoosingLibrary)?.account ?: run {
                    Log.d(TAG, "chooseLibrary called outside the library picker")
                    _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(SignInError.NoCandidate))
                    return
                }

            // Normally unreachable -- chooseServer is what populates candidate, and
            // ChoosingLibrary only exists after it succeeded -- but publishing a
            // Failed state here rather than returning silently means a stuck screen
            // reads as an error the user can retry rather than a dead button.
            val (uri, resource) =
                candidate ?: run {
                    Log.d(TAG, "chooseLibrary called with no candidate server on record")
                    _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(SignInError.NoCandidate))
                    return
                }

            // Normally unreachable, same as the candidate check above:
            // LibraryClient.musicSections filters out blank keys before this
            // screen ever sees a Directory, so `section` always has one. Routed
            // through the same failure path anyway -- these checks guard
            // structurally identical "the state chooseLibrary needs is not there"
            // situations, and a silent return here would be the one that leaves
            // the screen stuck with no retry affordance while its siblings do not.
            val key =
                section.key ?: run {
                    Log.d(TAG, "chooseLibrary called with a section that has no key")
                    _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(SignInError.NoCandidate))
                    return
                }

            commit(uri, resource, SectionKey(key), account)
        }

        /**
         * The flow's only credential write. Everything before it -- the PIN's
         * token, a switch's, the pickers' choices -- lives in the states, so
         * `previous` is still the session the user had before the flow began.
         */
        @OptIn(UnstableApi::class)
        private fun commit(
            uri: String,
            resource: Resource,
            key: SectionKey,
            account: PendingAccount,
        ) {
            // Recorded before the session write below so a re-probe has a list to
            // race the moment this session exists, rather than only after the
            // first recovery escalates all the way to plex.tv.
            addressBook.adopt(resource, uri)

            val previous = api.session
            val next =
                PlexSession(
                    accountToken = account.token,
                    serverUri = uri,
                    musicSectionKey = key,
                    serverToken = resource.accessToken,
                    machineIdentifier = resource.clientIdentifier,
                    owned = resource.owned != false,
                )

            // Same guard LibraryPickerRepository.selectLibrary applies to its own
            // commit -- this call site needed it too once the debug screen's
            // "Choose server" row started reaching chooseLibrary while signed in
            // and possibly playing, rather than only fresh off a PIN. On that
            // journey `previous` is a live session, so a server change here can
            // leave Room holding rating keys from the server that was just
            // replaced and ExoPlayer's timeline still pointed at its URLs. A
            // fresh sign-in has `previous == null` and clears nothing.
            //
            // A changed account token clears it too, even on the same server:
            // the player's loaded items carry the previous token in their URLs,
            // and after a Home switch that token is another member's. A PIN
            // mints a new token too, so even a same-account re-sign-in clears
            // it. That is accepted: a re-sign-in is rare and usually follows a
            // refused token.
            val write = {
                // The one write. Every value lands together or not at all.
                api.session = next
                _state.value = PlexSignInState.Done
            }
            val accountChanged = previous != null && previous.accountToken != next.accountToken
            if (accountChanged || LibrarySelection.invalidatesQueue(previous, next)) {
                Log.d(TAG, "server or account changed; discarding the saved queue")
                QueueRepository().deleteAll()
                // Written once the player has stopped, so the outgoing
                // track's final report goes out as the previous session.
                BrowseTreeInvalidator.stopPlayback(then = write)
            } else {
                write()
            }
        }

        private fun signIn() {
            attempt?.cancel()

            attempt =
                viewModelScope.launch {
                    either {
                        val created =
                            authClient
                                .createPin()
                                .mapLeft {
                                    when (it) {
                                        is CreatePinError.Transport -> SignInError.Api(it.failure)
                                        CreatePinError.NoPinCode -> SignInError.NoPinCode
                                    }
                                }.bind()

                        val awaiting = PlexSignInFlow.afterPinCreated(created)
                        _state.value = awaiting

                        // Held, not written: a forced re-sign-in arrives with a
                        // session still saved, and backing out of a picker must
                        // leave it whole.
                        val account = PendingAccount(awaitApproval(created, awaiting).bind().authToken)
                        val client = authClientFor(account.token)
                        _state.value = PlexSignInState.Working

                        // A Home that will not list is not a reason to fail a
                        // sign-in that has already succeeded: carry on as the
                        // account that approved the PIN.
                        val home =
                            client
                                .homeUsers()
                                .onLeft { Log.d(TAG, "home listing failed, continuing as the signed-in account: $it") }
                                .getOrNull()
                                ?.toNonEmptyListOrNull()
                        if (home != null && home.size > 1) {
                            _state.value = PlexSignInState.ChoosingUser(home, account)
                            return@either
                        }

                        val resources = client.getResources().mapLeft(SignInError::Api).bind()
                        val servers =
                            ensureNotNull(
                                AuthClient.mediaServers(resources).toNonEmptyListOrNull(),
                            ) { SignInError.NoServers }

                        _state.value = PlexSignInState.ChoosingServer(servers, account)
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
            awaiting: PlexSignInState.AwaitingApproval,
        ): Either<SignInError, PlexPinState.Authorized> =
            either {
                val startedAt = nowEpochSeconds()

                while (true) {
                    delay(PlexPinState.pollDelayMillis(nowEpochSeconds() - startedAt))

                    ensure(
                        PlexPinState.shouldKeepPolling(
                            startedAt,
                            nowEpochSeconds(),
                            awaiting.expiresAtEpochSeconds,
                        ),
                    ) { SignInError.PinExpired }

                    // Recovered, not bound: a 429, a 5xx, a 404 on a consumed pin or a
                    // dropped connection is not worth abandoning a sign-in over.
                    val pin =
                        authClient
                            .getPin(created.id)
                            .onLeft { Log.d(TAG, "pin poll failed, retrying: $it") }
                            .getOrNull() ?: continue

                    when (
                        val pinState =
                            PlexPinState.evaluate(
                                pin.authToken,
                                AuthClient.expiresAtEpochSeconds(pin),
                                nowEpochSeconds(),
                            )
                    ) {
                        is PlexPinState.Authorized -> {
                            return@either pinState
                        }

                        PlexPinState.Expired -> {
                            raise(SignInError.PinExpired)
                        }

                        // Nothing changed, so nothing is published: re-emitting the same
                        // state would reload the QR image on every poll.
                        PlexPinState.Pending -> {}
                    }
                }

                // Unreachable: the loop only leaves via return@either or raise.
                error("poll loop fell through")
            }

        private fun nowEpochSeconds() = nowMillis() / 1000L
    }
