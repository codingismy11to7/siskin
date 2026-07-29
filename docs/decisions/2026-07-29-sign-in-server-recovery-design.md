# Recover from a bad server pick without re-linking

**Date:** 2026-07-29
**Status:** Approved

Fixes [#18](https://github.com/codingismy11to7/siskin/issues/18). Amends the
sign-in flow described in `docs/decisions/2026-07-28-plex-sign-in-design.md`,
which remains as written.

## Context

Picking a Plex server that has no music library shows the right message — *"That
server has no music libraries."* — and then makes you re-approve a PIN to get
out of it.

Found on a real account while verifying something unrelated. The account has
eight servers, seven of them shared; one has no music library and three were
unreachable from the test network. So this is not an exotic path.

### Why it happens

`PlexSignInViewModel.chooseServer` runs its work inside an `either` block and
funnels every failure to the same place:

```kotlin
attempt = viewModelScope.launch {
    either {
        val uri = ensureNotNull(probe.bestConnectionUri(resource)) {
            SignInError.Api(PlexTransportFailure.Unreachable(PlexHost.Server))
        }
        candidate = uri to resource
        val response = LibraryClient(api, uri, resource.accessToken).getSections()
            .mapLeft(SignInError::Api)
            .bind()
        val sections = ensureNotNull(
            LibraryClient.musicSections(response).toNonEmptyListOrNull()
        ) { SignInError.NoLibraries }
        _state.value = PlexSignInState.ChoosingLibrary(sections)
    }.onLeft { _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(it)) }
}
```

`Failed` has one exit — a retry button that calls `signIn()`, which begins with
`createPin()`. So a valid account token is discarded to recover from a statement
about a *server*.

### It is a design gap, not an oversight

The sign-in spec chose the shape deliberately:

> `Failed` is reachable from any state and carries one thing, a `@StringRes`
> message. The retry is not part of the state: it is a button on the fragment,
> which calls back into the ViewModel and restarts at `Working`.

That is right for a genuine failure — no network, plex.tv refusing the PIN, an
expired PIN. It is wrong for a recoverable choice. Choosing a server without
music is not sign-in failing; it is the user needing to choose again.

## Decision

Return to the server picker, carrying the message.

```kotlin
data class ChoosingServer(
    val servers: NonEmptyList<Resource>,
    @param:StringRes val messageRes: Int? = null
) : PlexSignInState
```

The default keeps the happy path and the single existing construction site
unchanged.

One state means one screen regardless of how the user got there: no second
failure screen, no extra tap, and the next thing to do is already in front of
them. On a car display that matters more than it would elsewhere.

### Every error inside `chooseServer` is server-scoped

This is what makes the change small. Only two error types can arise inside that
`either` block — `SignInError.Api(...)` from the probe or from `getSections`, and
`SignInError.NoLibraries` — and both are statements about the server just picked.
Neither says anything about the account token.

So `chooseServer`'s `onLeft` routes to the picker unconditionally. There is no
need to discriminate error types, and no risk of an account-scoped failure
quietly being treated as recoverable: the account-scoped errors (`NoPinCode`,
`PinExpired`, `NoServers`) are raised in `signIn` and `discoverServers`, whose
`onLeft` handlers are untouched and still produce `Failed`.

### The routing lives in the ViewModel

An earlier draft of this spec, written against the pre-Arrow code, put the
decision in `PlexSignInFlow` on the grounds that its KDoc said the ViewModel
"makes no decisions of its own". **That is no longer the arrangement.**
`PlexSignInFlow`'s current KDoc records the opposite direction of travel:

> The emptiness checks this object used to perform — `afterResources`,
> `afterSections` — are now `ensureNotNull` preconditions at the call site, where
> they read as requirements rather than as state-returning helpers.

`messageFor(SignInError)` stays where it is and is reused unchanged. Only the
choice of *which state to publish* moves, and it belongs beside the `either`
block whose failures it is interpreting.

### The empty picker cannot happen

`ChoosingServer` takes a `NonEmptyList`, so a picker with nothing in it is not
constructible. Nothing needs to guard against it.

The list is captured from the current state before `_state.value` is overwritten
with `Working`, so the state remains its single owner. If the current state is
somehow not `ChoosingServer`, the failure falls back to `Failed` — honest, and
unreachable through the UI, since `chooseServer` is only invoked from a tap on a
rendered row.

## Rendering

The layout already has an `error_text` view, but it sits *after* `choice_container`.
It moves above, so the explanation precedes the choices rather than trailing
them.

`retry_button` stays hidden in this state. The list is the recovery.

`PlexSignInFragment.render` already resets both `errorText` and `retryButton` to
`GONE` before its `when`, so no message can leak from one state onto another.

## Testing

`PlexSignInViewModelTest` already exists, drives the ViewModel through
`MockWebServer`, and has a constructor-injection seam:

```kotlin
class PlexSignInViewModel @JvmOverloads constructor(
    application: Application,
    private val api: PlexApi = PlexApi(),
    private val authClient: AuthClient = AuthClient(api),
    private val probe: ServerProbe = ServerProbe(...)
)
```

So the rejection path is directly testable, and gets a test: seed
`ChoosingServer`, make the chosen server yield no music sections, assert the
resulting state is `ChoosingServer` carrying the message rather than `Failed`.

The test that matters most asserts the **capture ordering**: moving the capture
below `_state.value = Working` makes the list permanently null and silently
reintroduces this exact bug, while every other test still passes.

`chooseServerPersistsNothing` already covers the credential side and must keep
passing unchanged — the recovery path must not write a partial session.

## Not in scope

**The "no servers on this account" case** (`SignInError.NoServers`). Raised in
`discoverServers`, and re-linking cannot help — the account is valid and simply
has no servers. Same shape of defect, deliberately left to keep this change to
what #18 reports.

**PIN expiry.** It genuinely needs a new PIN.

**[#24](https://github.com/codingismy11to7/siskin/issues/24)** — recreating the
activity while on a picker mints a new PIN, because `start()` only guards on
`attempt?.isActive`, which is false once sign-in has reached a picker. The same
failure as this one by a different route, tracked separately.

## A note on the first attempt

This fix was written once already, against `61796807`, and discarded. PR #21
adopted Arrow for typed errors midway through and deleted the surfaces that
version was built on — `PlexSignInFlow.afterSections`, the exception handling in
`chooseServer`, and the credential-clearing invariant it was carefully written not
to break.

Recorded because the lesson is about process rather than code: three per-task
reviews passed the work correctly against a base that had stopped existing, and
only the whole-branch review was positioned to notice. The rewrite is smaller
than the original, because typed errors and `NonEmptyList` had removed two of the
things it had to handle.
