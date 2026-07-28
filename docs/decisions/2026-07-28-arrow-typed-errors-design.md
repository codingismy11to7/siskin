# Typed errors with Arrow

**Date:** 2026-07-28
**Status:** Approved

## Context

The Plex layer throws. `AuthClient.getSections(): PlexResponse` does not disclose
that it can fail, and `PlexSignInViewModel` pays for that with five near-identical
`catch (IOException) / catch (HttpException)` pairs — roughly fifty of its two
hundred and fifty lines. Each arm logs, publishes a `Failed` state, and returns.

This spec adopts `arrow-core` to make failure part of the signature.

**Revised after #17.** An earlier draft argued this should land *before* browse and
playback were written against the clients. That work has since merged, so the
premise is gone and the honest framing is different: there are now five files with
suspend call sites into the clients — `PlexSignInViewModel`, `PlexBrowseRepository`,
`PlexMixRepository`, `BaseSessionCallback` and `PlexScrobbler` — and changing the
client signatures touches all of them.

The change got larger, but the case got stronger, because #17 produced the exact
comment-enforced invariant typed errors exist to remove. `PlexBrowseRepository`
narrows its catch to `HttpException` on purpose and defends it in KDoc:

> "a transport failure … is a reachability problem and is left to propagate …
> Widen this catch and a flaky network starts telling the user their credentials
> expired."

That is a correctness property held in place by a comment and a carefully chosen
catch clause. `PlexFailure.Http(Server, 401)` versus `Unreachable(Server)` makes it
a type distinction the compiler checks instead.

`MediaManager.java` and `MediaBrowserTree` reference client *constants* only
(`STATE_PLAYING`, `SORT_TITLE`), never suspend functions, so no Java interop
problem arises. `PlexScrobbler` already exists as the Kotlin bridge for exactly
that reason.

## The case is narrower than it first looks

Worth recording honestly, because the obvious pitch oversells it.

Two goals motivated the question: typed errors at the API boundary, and making
illegal states unrepresentable. **Arrow only serves the first.** The largest
correctness hazard in the codebase — the one `CredentialGate` documents in a
twenty-line comment — is fixed by a data class, and Arrow contributes nothing to
it. `NonEmptyList` is the one exception, and it covers two call sites.

The decisive argument for adopting it anyway is not line count. It is that the
author thinks in FP, and the codebase is read far more often than it is written.
Code its owner reads fluently is safer code, and on a single-maintainer project
that is a legitimate engineering input rather than a matter of taste.

The counter-argument, recorded so it is not relitigated from scratch: the Plex
API-layer spec rejected a generated SDK on the grounds that hand-rolling produced
a *smaller* result, and the same instinct argues against a runtime dependency to
delete fifty lines of `catch`. That reasoning was about four hundred operations
and several hundred generated model classes. A small library whose idioms the
author actively wants to use is a different trade, and the earlier reasoning does
not transfer.

## Dependency: `arrow-core` only

Version 2.2.3.

### `arrow-fx-coroutines` is rejected, but it was close

The first pass dismissed it as "structured concurrency already covers this." That
was wrong, and the real reason is more specific.

`arrow-fx-coroutines` ships `racing { }`, whose semantics nearly match
`ServerProbe.race()` exactly: race N blocks over a list, a `condition` lambda
decides what counts as success, losers are cancelled, structured concurrency
guaranteed. `race(condition = { it != null }) { if (answers(uri)) uri else null }`
is very close to the probe as written.

It still does not fit, for two reasons:

1. **It hangs when everything fails.** A block that fails the condition calls
   `awaitCancellation()`; if every block fails, `racing` never returns. That is
   the case `ServerProbe` explicitly handles — the `launch { probes.joinAll();
   winner.complete(null) }` arm, whose comment reads "Nothing else would ever
   complete `winner` if every probe fails, and awaiting it would hang for the
   life of the sign-in." A car on a network where no advertised address answers
   is the ordinary failure path here, not an edge case. Arrow's KDoc offers
   `onTimeout` as the escape hatch, but `onTimeout` does not exist in the API —
   it appears only inside the doc comment, and `RacingScope` declares just
   `raceOrFail`. The documented example does not compile.
2. **`@ExperimentalRacingApi`** — "This API is work-in-progress and is subject to
   change."

819 KB and an unstable API to replace twenty tested lines that handle a case the
library cannot express. `ServerProbe` stays as written.

The library's other half, `Resource`/`resourceScope`, has no customer: the only
resource-shaped thing on this path is an OkHttp `Response`, already handled by
`response.use { }` in `ServerProbe.answers`.

**Revisit if** the browse tree fans out — fetching several hub rows concurrently
to build a level — where `parMap` genuinely beats hand-rolled `async`/`awaitAll`.

### Also rejected

- **`arrow-optics`.** Needs KSP added to a build that currently has no KSP, and
  Gson cannot construct `Option` without a custom adapter. Arrow 2.x steers
  toward Kotlin nullables regardless.
- **`arrow-resilience`.** Its `Schedule` looks like a fit for the PIN poll loop,
  but the stop condition is a server-supplied absolute timestamp plus a hard cap,
  already extracted as the tested pure function `PlexPinState.shouldKeepPolling`.
  `Schedule` would obscure that rather than simplify it.

## Cancellation: verified, not assumed

`PlexSignInViewModel` uses coroutine cancellation as a correctness mechanism, not
merely as cleanup. `chooseLibrary()` cancels precisely so that nothing still
outstanding can publish a `Failed` over a finished sign-in. `raise` short-circuits
by throwing. Both mechanisms are exception-based, so they had to be checked
against each other.

Read from arrow-core 2.2.3 sources rather than recalled:

- `RaiseCancellationException` extends `kotlin.coroutines.cancellation.CancellationException`
  on Android and the JVM.
- `fold` — which `either { }` is built on — catches `RaiseCancellationException`
  first and calls `raisedOrRethrow(raise)`, which tests `this.raise === raise`.
  A short-circuit belonging to a different scope is **rethrown**.
- A genuine coroutine cancellation falls to the `catch (e: Throwable)` arm, where
  `nonFatalOrThrow()` rethrows it, because `NonFatal` on Android and the JVM is
  `is VirtualMachineError, is ThreadDeath, is InterruptedException, is LinkageError, is CancellationException -> false`.

**A cancellation thrown into an `either { }` propagates straight out and is never
converted into a `Left`.** The ViewModel's invariant survives adoption unchanged,
and there is nothing to design around.

### The hazard that does exist

On the JVM, `kotlin.coroutines.cancellation.CancellationException` is a typealias
for `java.util.concurrent.CancellationException`, which extends
`IllegalStateException`. So `catch (e: Exception)`, `catch (e: RuntimeException)`
and `catch (e: IllegalStateException)` all silently swallow a `raise`. The failure
is not loud at the catch site: the scope is left unresolved, surfacing later as a
`RaiseLeakedException` or a wrong value. Arrow's own message says it — "This
swallows the exception of Arrow's Raise, and leads to unexpected behavior."

An earlier draft claimed the broad catches were all off the Plex path. **#17 made
that false.** There are now five on it, and all of them are deliberate:

| site | catches | why |
|---|---|---|
| `PlexBrowseRepository.launchInto:257` | `Throwable` | media3 waits on the future; anything uncaught leaves the tab spinning. Its KDoc says it must catch cancellation at `release()` time too. |
| `PlexBrowseRepository.collect:220` | `Throwable` | one failed search tier must not lose the other two |
| `PlexMixRepository:91`, `PlexScrobbler:45`, `BaseSessionCallback:403` | `Throwable` | fire-and-forget side effects that must not crash playback |

These are correct as written, and the rule must not be stated so broadly that it
condemns them. `launchInto` in particular *has* to swallow cancellation: a
cancelled browse that neither completes nor fails its future hangs the car's UI.

The precise rule is narrower:

**Rule: no broad catch lexically inside an `either { }` block.** Everywhere else a
broad catch is fine, because a `raise` never crosses those boundaries — clients
return `Either`, and the only `either { }` in this design is inside
`PlexSignInViewModel`, which catches nothing. The five sites above are all outside
one.

The plan should verify this rather than assume it, since it is the one rule whose
violation fails quietly rather than at compile time.

**Rule: do not `raise` across a coroutine-builder boundary.** `DefaultRaise`
identity-checks its scope token, so a `raise` inside a `launch`/`async` nested in
an outer scope takes the foreign-scope path. `ServerProbe.race()` is exactly that
shape; it returns `String?` and stays that way. Raise at the caller, never inside
the race.

## Two error types

```kotlin
// plex/PlexFailure.kt -- what the API did wrong; no Android, no R
enum class PlexHost { PlexTv, Server }

sealed interface PlexFailure {
    /** Which side failed. plex.tv and a media server fail for different reasons
     *  and are worth reporting differently. */
    val host: PlexHost

    data class Unreachable(override val host: PlexHost) : PlexFailure
    data class Http(override val host: PlexHost, val code: Int) : PlexFailure

    /** plex.tv returned a PIN with no id or code. */
    data object NoPinCode : PlexFailure {
        override val host = PlexHost.PlexTv
    }
}
```

```kotlin
// plex/auth/SignInError.kt -- everything that can end a sign-in
sealed interface SignInError {
    data class Api(val failure: PlexFailure) : SignInError
    data object PinExpired : SignInError
    data object NoServers : SignInError
    data object NoLibraries : SignInError
}
```

Split in two because "the API did not give us what we asked for" and "this
account has no media servers" are different kinds of statement. Folding them
together would put `R.string` references or flow concerns into `plex/`, which is
Android-free apart from `Log`.

### Why `PlexFailure` carries the host

This is the design's load-bearing decision, and the first draft got it wrong.

That draft had a bare `Network`/`Http` failure and translated per call site, on
the theory that the same transport failure means different things at different
points in the flow. Walking the actual translation sites disproves it. Three of
the four carry no flow context whatsoever — they encode **which host was being
talked to**. A `Network` failure creating a PIN is "could not reach plex.tv"
because `createPin` is a plex.tv call, not because of where the flow had got to.

The layer already knows this. It is the premise of the two-Retrofit-instance
split, which the API-layer spec defends precisely on these grounds: a single
instance with per-call `@Url` "loses the compile-time distinction between 'this
is a plex.tv call' and 'this is a server call'." Putting the host in the failure
carries that distinction into the error channel, where it was previously
reconstructed by hand at each call site.

Two consequences:

- **`SignInError` loses `ServerUnreachable`**, which was only ever "`Unreachable`,
  but on the server." Six cases become four.
- **Every translation becomes the same call** — `.mapLeft(SignInError::Api)`, at
  all three sites. There is no per-site judgment left to get wrong, and no
  comment needed to justify one.

`messageFor` stays total over the six existing `plex_sign_in_error_*` strings,
with no `else` branch:

| `SignInError` | string |
|---|---|
| `Api(Unreachable(PlexTv))`, `Api(Http(PlexTv, _))` | `…_network` |
| `Api(Unreachable(Server))`, `Api(Http(Server, _))` | `…_server_unreachable` |
| `Api(NoPinCode)` | `…_pin` |
| `PinExpired` | `…_expired` |
| `NoServers` | `…_no_servers` |
| `NoLibraries` | `…_no_libraries` |

This preserves a deliberate current behavior: a dropped connection and a 401 from
`getSections` both read as "could not reach that server," which today's comments
argue for explicitly. Now it falls out of the host rather than being asserted.

`NoPinCode` stays in `PlexFailure` despite having exactly one producer. It is a
statement about what plex.tv did wrong, not about the sign-in flow, and a sealed
hierarchy should be honest rather than minimal.

`Http(host, code)` finally delivers what the API-layer spec promised and the
current code cannot express: `Http(Server, 401)` means a stale token and should
drive re-authentication, while `Unreachable(Server)` means retry later. Today
both become "could not reach that server." The browse tree needs that
distinction more than sign-in does.

## Clients return `Either`

One narrow adapter replaces every `try`/`catch` in the layer:

```kotlin
internal suspend fun <T> plexCall(host: PlexHost, block: suspend () -> T): Either<PlexFailure, T> =
    try {
        block().right()
    } catch (e: IOException) {
        PlexFailure.Unreachable(host).left()
    } catch (e: HttpException) {
        PlexFailure.Http(host, e.code()).left()
    }
```

Deliberately catching the same two types the ViewModel catches today, and nothing
broader — see the swallowing hazard above.

Each client passes its own host as a constant: `AuthClient` is `PlexHost.PlexTv`,
`LibraryClient` and `SearchClient` are `PlexHost.Server`. That is a fixed property
of the client, mirroring which `PlexRetrofitFactory` method built its service, so
it is never a per-call decision.

```kotlin
suspend fun getSections(): Either<PlexFailure, PlexResponse> =
    plexCall(PlexHost.Server) { service.getSections() }
```

### Why not `Raise`-scoped clients

`Raise<PlexFailure>.getSections()` reads better at first glance, but as a member
function it needs an extension receiver, forcing `with(libraryClient) { … }` at
every call site. The alternative is Kotlin 2.2 context parameters, still behind
an experimental compiler flag. An experimental language feature is not worth it
in a shipping head-unit app to remove `.bind()`. Revisit if context parameters
stabilise.

## The ViewModel

One happy path, one failure handler:

```kotlin
attempt = viewModelScope.launch {
    either {
        val created = authClient.createPin().mapLeft(SignInError::Api).bind()
        val awaiting = PlexSignInFlow.afterPinCreated(created)
        _state.value = awaiting

        val authorized = awaitApproval(created, awaiting).bind()
        api.accountToken = authorized.authToken
        _state.value = PlexSignInState.Working

        val resources = authClient.getResources().mapLeft(SignInError::Api).bind()
        val servers = ensureNotNull(AuthClient.mediaServers(resources).toNonEmptyListOrNull()) {
            SignInError.NoServers
        }
        _state.value = PlexSignInState.ChoosingServer(servers)
    }.onLeft { _state.value = PlexSignInState.Failed(PlexSignInFlow.messageFor(it)) }
}
```

Intermediate states are still published inside the block as side effects; only the
failure path is unified. `AwaitingApproval` must reach the screen while polling.

### `PlexSignInFlow` shrinks

Its stated job is "every 'what happens next' decision as pure functions over API
results," and most of those decisions become `ensure`/`ensureNotNull` at the call
site, where they read as preconditions rather than as state-returning helpers.

- `afterResources` and `afterSections` **go away** — each was an emptiness check
  plus a state construction, now `ensureNotNull(… .toNonEmptyListOrNull())`.
- `afterPinPoll` **goes away** — expiry is `raise(SignInError.PinExpired)`, and
  its other job, returning `current` unchanged so a no-op poll does not re-emit
  and reload the QR image, is now served by simply not assigning `_state`.
- `afterPinCreated` **survives**, simplified: with a refined `CreatedPin` it can
  no longer fail, so it just builds `AwaitingApproval`.
- `messageFor` is **new**, and becomes the object's main remaining purpose.

The testability argument that justified the object still holds — `messageFor` and
`afterPinCreated` stay pure and unit-tested — but the object is smaller, and the
plan should expect to delete tests for the functions that no longer exist rather
than to port them.

### Retryable versus terminal — the one real hazard

`awaitApproval` deliberately does **not** treat a failed poll as a failure. It
continues, because "a dropped poll is not a failed sign-in — the pin is still
live." `Raise` models terminal failure only. If `getPin()` short-circuited the
enclosing `either { }`, a single network blip on car Wi-Fi would abandon the
sign-in — a real regression in exactly this app's environment.

The recovery point is one line:

```kotlin
private suspend fun awaitApproval(…): Either<SignInError, PlexPinState.Authorized> = either {
    val startedAt = nowEpochSeconds()
    while (true) {
        delay(POLL_INTERVAL_MS)

        ensure(PlexPinState.shouldKeepPolling(startedAt, nowEpochSeconds(), awaiting.expiresAtEpochSeconds)) {
            SignInError.PinExpired
        }

        // A dropped poll is not a failed sign-in -- the pin is still live.
        val pin = authClient.getPin(created.id).getOrNull() ?: continue

        when (val s = PlexPinState.evaluate(pin.authToken, AuthClient.expiresAtEpochSeconds(pin), nowEpochSeconds())) {
            is PlexPinState.Authorized -> return@either s
            PlexPinState.Expired -> raise(SignInError.PinExpired)
            PlexPinState.Pending -> {}
        }
    }
}
```

This is an improvement on the status quo rather than a concession to it. Today the
distinction is `continue` versus `return@launch` buried inside catch arms; here it
is one visible line.

### A refined PIN

`createPin()` raises `NoPinCode` when the response lacks an id or a code, and
returns `CreatedPin(id: Long, code: String, qr: String?, expiresAtEpochSeconds: Long?)`
with non-null required fields, rather than the all-nullable Gson `Pin`.

That deletes this, from the ViewModel:

```kotlin
// afterPinCreated only returns AwaitingApproval for a pin with an id.
val pinId = pin.id ?: return@launch
```

The comment exists because the type cannot say what the code knows. `Pin` stays
as the Gson wire model; `CreatedPin` is the validated one.

### `NonEmptyList` at the two emptiness checks

`ChoosingServer` and `ChoosingLibrary` carry `Nel` instead of `List`, so the
emptiness check cannot be skipped downstream and the fragment cannot be handed an
empty list to render.

```kotlin
val uri = ensureNotNull(probe.bestConnectionUri(resource)) {
    SignInError.Api(PlexFailure.Unreachable(PlexHost.Server))
}
val response = library(uri, resource.accessToken).getSections().mapLeft(SignInError::Api).bind()
val sections = ensureNotNull(LibraryClient.musicSections(response).toNonEmptyListOrNull()) {
    SignInError.NoLibraries
}
```

`LibraryClient.musicSections` keeps returning `List` — it is a pure filter with
its own tests, and the narrowing belongs at the call site that knows emptiness is
fatal.

Note that a probe finding no reachable address raises the same
`Unreachable(Server)` a failed call would, so both reach the user as "could not
reach that Plex server" without a second code path. `ServerProbe` itself still
returns `String?` rather than an `Either`, per the coroutine-builder rule above.

## `PlexSession`, and why it drags the clients with it

`CredentialGate` carries a twenty-line comment ending: "A mixed set would be
reported here as a working sign-in, and browse would then query one server for
another's section." Three loose nullable strings that must describe one
connection, held together by `chooseServer()` clearing them in the right order —
itself explained by a further fifteen-line comment.

```kotlin
data class PlexSession(
    val accountToken: String,
    val serverUri: String,
    val musicSectionKey: String,
    val serverToken: String?,   // null for an owned server; those accept the account token
)
```

`PlexApi.session: PlexSession?` reads and writes it as a unit — one `edit()`
commit, all four keys or none. `CredentialGate.isSignedIn()` becomes
`api.session != null`. Both comments delete, because the hazard stops existing.

**A read-only session type would not do.** The hazard is not "some fields are
null"; it is a section key from server A alongside a server URI from server B,
all non-null and all wrong. Only committing them together fixes it.

That forces a second change. `chooseServer()` currently writes `serverUri` into
preferences mid-flow because `LibraryClient(api)` reads it back out through
`PlexRetrofitFactory.server(api)`. If the session is only committed once the user
picks a library, mid-flow clients cannot read preferences — so `LibraryClient` and
`SearchClient` take the server URI and token as explicit constructor arguments.

An earlier draft justified this as retiring the API-layer spec's deferred sharp
edge — "a client built before discovery, or before a server switch, is stuck."
**#17 already fixed that**, from the other end: `PlexBrowseRepository` caches its
clients against `clientsUri` and rebuilds them whenever `api.serverUri` changes,
and `PlexRetrofitFactory.normalize` falls back to an unreachable placeholder base
URL so pre-discovery calls fail through the error path instead of throwing at
construction. That justification is spent, and explicit constructor arguments rest
solely on the atomic-commit argument above.

They do still follow from it, and #17 does not weaken that: `refreshClients()`
reads `api.serverUri` directly, so it is a *third* reader of the loose fields and
moves to `api.session` with the rest. Its cache key becomes the session's
`serverUri`, and the rebuild-on-change behaviour is preserved unchanged — it
solves a different problem (one long-lived repository outliving sign-in) than
`PlexSession` does (never persisting a mixed set).

The placeholder base URL composes well with typed errors rather than fighting
them: a call issued before discovery fails as `Unreachable(Server)`, which is
exactly what it is.

## Value classes for the confusable pair

`@JvmInline value class SectionKey(val value: String)` and `RatingKey`. Both are
`String` today, both index into Plex, and `getSectionContent(sectionKey)` versus
`getChildren(ratingKey)` swap silently — a compile error becomes a runtime
mystery. The browse tree will be thick with both.

Token newtypes are skipped: tokens are never confusable with keys in practice and
flow only into header construction.

## Testing

The shape shifts more than the count grows.

- Client tests assert `Either.Left(PlexFailure.Http(401))` rather than expecting
  a throw — clearer, and it exercises the 401-vs-503 distinction that has no
  current expression.
- `messageFor` exhaustiveness is compiler-enforced; one cheap test that every
  `SignInError` maps to a distinct non-zero resource guards against two cases
  pointing at the same string.
- "A dropped poll does not fail the sign-in" becomes directly testable, because
  `getPin` returns a value instead of throwing. It is effectively untestable
  today.
- The pure-function tests (`musicSections`, `mediaServers`,
  `expiresAtEpochSeconds`, `PlexPinState`) are unaffected.

## Scope

**In:** the `arrow-core` dependency; `PlexFailure` and `plexCall`; `Either`
returns across `AuthClient`, `LibraryClient` and `SearchClient`; `SignInError` and
`messageFor`; the `PlexSignInViewModel` rewrite including the poll-loop recovery
point; `CreatedPin`; `Nel` in `ChoosingServer` and `ChoosingLibrary`;
`PlexSession` with atomic persistence; explicit server arguments for
`LibraryClient` and `SearchClient`; `SectionKey` and `RatingKey`; the tests above.

**In, added after #17** — the four other consumers with suspend call sites, which
the client signature change forces:

- **`PlexBrowseRepository`** — `resultFor` switches on `PlexFailure` instead of
  catching `HttpException`, so `errorFor`'s 401/403 → `ERROR_PERMISSION_DENIED`
  mapping keys off `Http(Server, code)` and a transport failure reaches
  `launchInto` as a `Left` rather than a thrown `IOException`. Its
  `refreshClients()` cache key moves to the session.
- **`PlexMixRepository`**, **`BaseSessionCallback.rate`**, **`PlexScrobbler`** —
  fire-and-forget call sites; each `Either` is logged and discarded, preserving
  today's behaviour that a failed scrobble or heart never disturbs playback.

**Out:** `arrow-fx-coroutines`, `arrow-resilience`, `arrow-optics`; `MediaManager.java`
and `MediaBrowserTree`, which use client constants only; the `!!` sites off the
Plex path; the existing broad catches listed above, which are correct as written.

### Sequencing

Three units now, not two. The first is larger than it was, because the client
signature change cannot be confined to sign-in any more:

1. **Typed errors** — dependency, `PlexFailure`, `plexCall`, `Either` returns,
   `SignInError`, `messageFor`, the ViewModel rewrite, `CreatedPin`, `Nel`, **and
   the four consumer call sites**. Not splittable: the signature change breaks
   them the moment it lands.
2. **Session** — `PlexSession`, atomic persistence, explicit server arguments on
   `LibraryClient` and `SearchClient`, `refreshClients()` moving to the session.
3. **Value classes** — `SectionKey` and `RatingKey`. Independent of both, and the
   easiest to drop if the first two run long.

Order matters between 1 and 2: the second changes client constructors, and doing
it after the first means touching each signature once rather than twice.

## To verify during implementation

- **The release APK delta from `arrow-core` after R8**, and whether any keep rules
  are needed. Only the unshrunk figure is known (495 KB for `arrow-core-jvm`
  2.1.2), which is not the number that matters for a build using
  `minifyEnabled` and ABI splits.
- **`arrow-core` 2.2.3 against Kotlin 2.2.10 and AGP 9.2.1**, and that no
  experimental compiler flag is pulled in by the stable `Either`/`either { }`
  surface.
