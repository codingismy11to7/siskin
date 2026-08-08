# Re-resolve the server address instead of pinning it at sign-in

**Date:** 2026-08-08
**Status:** Approved

Pays out the deferred item in
`docs/decisions/2026-07-28-server-connection-probing-design.md`, which stands as
written — this spec does not amend it, it finishes it.

## Context

The car started on the home LAN, drove away, and the music died. Browse stopped
working on every tab. Nothing recovered on its own, and the driver got out of it
only by switching Plex servers in the More tab and switching back — at speed,
having nearly parked to force-stop the app.

The probing spec predicted this a week and a half earlier, in as many words:

> **The stored URI goes stale.** A car signed in on the home LAN keeps
> `192-168-0-2` in preferences and drives away, and every subsequent call fails
> until the user signs in again. The fix is re-probing — at app start, or on the
> first failure against a stored URI, promoting the relay when the LAN is gone.

It was left for "the browse and playback work where those failures actually
surface". That work landed; this did not follow it.

## What actually breaks

`ServerProbe` races a server's advertised connections **once**, during sign-in,
and only the winner survives — `PlexSession` persists `serverUri` and the
candidate list is discarded. Nothing re-probes afterwards.
`PlexBrowseRepository.refreshClients()` rebuilds its clients only when the
*session object* changes, and a stale address is not a session change, so it
rebuilds nothing.

That single field feeds every path: browse through `LibraryClient` and
`SearchClient`, audio through `MediaUrlBuilder.streamUrl`, artwork through
`AlbumArtContentProvider`, and scrobbling. So "the music died and I could not
browse" is one defect, not two.

There is also no way out of it from inside the app. `PlexBrowseRepository`
raises on `PlexTransportFailure.Unreachable` so the future completes
exceptionally, and the "sign in again" affordance is keyed to 401/403 — which is
correct, because the credentials were never the problem. The app declines to
offer the one recovery it has, for good reasons, and offers nothing else.

Playback fails in a way worth spelling out, because it looks like a different
bug. `BaseMediaService.onPlayerError` classifies a network error as recoverable
and re-prepares every five seconds. That loop was written for precisely this
transition — a wifi-to-mobile switch, issue #682 in the upstream fork — and it
cannot help, because the URL it retries has the dead host baked into it. The
symptom is not silence; it is a player retrying a dead address forever.

### The workaround, and the better one nobody knew about

Switching servers and back worked, and re-picking **the same** library would
have worked too, on an account with exactly one server and one library.
`LibraryPickerRepository.getLibraries` builds a fresh `ServerProbe` on every
visit, and `selectLibrary` has no "already selected" branch — it rebuilds the
session unconditionally from the freshly probed address.

Re-picking the same server would also have cost less. `invalidatesQueue` is
keyed on `machineIdentifier`, so returning to the same server skips the
`QueueRepository().deleteAll()` and `BrowseTreeInvalidator.stopPlayback()`.
Going away and back tripped that twice and discarded a queue for nothing.

So the recovery machinery already exists, is already tested, and already runs on
every visit to that screen. What is missing is a trigger that is not a human
navigating a menu while driving.

## Decision

A `ServerAddressBook`: one authority on how to reach the current server, holding
every address the server advertises and which one currently answers.

```kotlin
class ServerAddressBook(
    private val api: PlexApi,
    private val probe: ServerProbe = ServerProbe(),
) {
    /** The address every server request uses. */
    fun current(): String?

    /** Seeds the address and the candidate list. */
    fun adopt(resource: Resource, uri: String)

    /** Re-races the known candidates, persisting the winner. Null when nothing answered. */
    suspend fun reprobe(staleAddress: String?): String?
}
```

It introduces no second source of truth for the address. `current()` reads
`PlexSession.serverUri`; `reprobe` writes `api.session = session.copy(serverUri
= winner)`. The candidate list is the only new persisted state, in its own
preferences key, **stamped with the `machineIdentifier` it came from** so a list
belonging to a server the user has since left can never be raced. When the stamp
does not match the session's server, the list is discarded rather than trusted,
and `reprobe` starts at step 2 below.

`adopt` is called from the two places CLAUDE.md names as the only session
writers — `PlexSignInViewModel.chooseLibrary` and
`LibraryPickerRepository.selectLibrary`. Both already hold the `Resource` and
the probed URI at that point, so neither gains a network call; they gain a line
that keeps the candidate list beside the address they were already persisting.

### Why moving `serverUri` alone is legal

`PlexSession`'s KDoc promises that `serverUri`, `musicSectionKey` and
`serverToken` "always move together", and warns what a mixed set would do: read
as signed in, then ask one server for another's section.

That invariant is not weakened here, because `machineIdentifier` is unchanged.
`machineIdentifier` says *which server*; `serverUri` says only *how to reach
it*. The same server at a new address keeps the same sections and accepts the
same token. What the comment has always meant is that the three must never
describe **different servers**, and it gets amended to say that rather than
deleted — the hazard it documents is real and this change does not remove it.

A test guards the distinction: after a reprobe, `machineIdentifier`,
`musicSectionKey` and `serverToken` must be untouched and only `serverUri` may
have moved. It goes red if that `copy` is ever turned into a whole-session
rebuild.

### Where it lives

`plex/api/server/`, with `ServerProbe` moved in beside it. The probing spec
pre-authorised exactly this:

> It is not really "auth", and a `plex/api/server/` package would say so more
> honestly; that is not worth a package for one file, and moving it later is a
> rename.

It is two files now.

## Triggers

**A network change.** `BaseMediaService.CustomNetworkCallback` guards on
`onCapabilitiesChanged` with `isWifi != wasWifi`. That is right for
`QueuePreloader` and wrong here: it fires on a transport flip, so home wifi to
office wifi does not trip it, and neither does a Plex container taking a new LAN
address. The re-probe hangs off **`onAvailable`** instead, which
`registerDefaultNetworkCallback` fires whenever the default network becomes a
different `Network`. The existing wifi-flip branch stays as it is for the
preloader.

`onAvailable` also fires once at registration, so service start gets a re-probe
for free — the third trigger the probing spec listed, at no cost.

**A failure.** A helper:

```kotlin
suspend fun <T> withAddressRecovery(
    call: suspend () -> Either<PlexTransportFailure, T>
): Either<PlexTransportFailure, T>
```

It runs `call()`, and on `Left(Unreachable)` re-probes and re-runs **only if the
address actually changed**. Retrying against an address that just failed buys a
second twenty-second timeout and nothing else.

It wraps the calls whose failure a user is waiting on, and there is one place to
put it: `PlexBrowseRepository.launchInto` already takes a
`suspend () -> Either<PlexTransportFailure, LibraryResult<…>>`, which is exactly
this signature. Applying it there covers every browse node in the class at once,
rather than wrapping call sites one at a time and leaving whichever gets
forgotten as the bug that survives.

`PlexMixRepository` gets it at its own call site, being a separate class.

Deliberately **not** `PlexScrobbler` or the heart tap in `BaseSessionCallback` —
those are fire-and-forget reports, so making them re-probe would spend a race on
something nobody is watching, behind a lock the browse path may be queuing for.

**Search is a known exception and stays one.** `PlexBrowseRepository.collect`
folds each tier's failure into an empty list, so `search`'s block is always
`Right` and never reaches the recovery path — a search against a dead address
returns no results rather than re-probing. That is pre-existing behaviour, it is
documented in `collect`, and changing it means changing what a partial search
failure means. Out of scope here; the next browse of any tab recovers the
address anyway.

No client-rebuilding changes are needed for the retry to see the new address.
`reprobe` writes a new `PlexSession` instance, so `refreshClients()`'s existing
`session != clientsSession` check fires by itself, and the call re-reads the
`libraryClient` property — which is already how `PlexBrowseRepository` is
written.

The helper branches on `Either` values and catches nothing, so it stays clear of
the rule in CLAUDE.md about broad catches inside an `either { }`.

**A player error.** `onPlayerError` already classifies `ERROR_CODE_IO_*` as
recoverable; that is the playback spelling of `Unreachable`, so it re-probes
before `player.prepare()`. It is not a suspend context, so it posts to the
service scope.

### What `reprobe` does

1. Race the **stored** candidates — direct tier first, relay held back, exactly
   as `ServerProbe` already tiers them. No plex.tv round trip, which is what
   makes this work on a LAN whose internet is down but whose Plex server is
   fine. `LibraryPickerRepository.getServers` already documents that case.
2. If nothing answers, fetch a fresh list from plex.tv, persist it, race that.
   Covers a server that changed its advertised addresses outright.
3. Still nothing: `null`.

The list is also refreshed whenever `getResources()` succeeds for other reasons,
so it converges without a poll loop. There is nothing to subscribe to: Plex
Media Server's websocket lives on the server, and is therefore unreachable
exactly when the address list is needed; plex.tv offers no push at all.

### Collapsing concurrent triggers

Four browse tabs failing at once, plus the network callback, must produce one
race rather than five. A `Mutex`, entered with the address the caller found
stale:

```kotlin
suspend fun reprobe(staleAddress: String?): String? = mutex.withLock {
    val now = current()
    if (now != staleAddress) return@withLock now   // someone already fixed it
    race()
}
```

Three of the four tabs then return instantly with what the first one found.

When a race fails outright, the book records the time and returns `null` without
racing again for a short cooldown (~10s). Without it, a genuinely offline car
pays a full race per tab, serially. **This number is the one arbitrary value in
the design** and is worth revisiting against a real dead-server case rather than
defending.

## Playback

A `ResolvingDataSource.Factory` rewrites scheme, host and port to `current()`
for any URI whose path starts with `/library/parts/`.

It wraps in `DynamicMediaSourceFactory`, around whichever factory that class
selects — **not** inside `DownloadUtil.getCacheDataSourceFactory`, even though
that is where a `ResolvingDataSource.Factory` already exists. The existing one
is only reachable on the caching path: `DynamicMediaSourceFactory` falls back to
`getUpstreamDataSourceFactory` when `Preferences.getStreamingCacheSize()` is
zero or less, and a resolver added there would silently not run for anyone who
has turned the streaming cache off. Wrapping the selected factory covers both
branches with one rule.

That prefix is the Plex part-key signature. Local files and `content://` artwork
do not match it, so the rule needs no candidate-set lookup and no state, and it
is correct whether the URI in the timeline was built against a live address or a
dead one.

The consequence is the one worth having: the five-second re-prepare loop starts
succeeding. It retries the same stale `MediaItem`, the resolver substitutes the
live origin at load time, and the track resumes from position.

Artwork needs no change at all. `PlexMediaMapper` puts a `content://` URI
carrying only the thumb path into the `MediaItem`, and `AlbumArtContentProvider`
composes the real URL at `openFile` time from `api.getServerUri()`. It already
resolves late rather than baking early — it is the existing precedent for what
the resolver does for audio.

### The cache key

`StreamingCacheKeyFactory` keys on `<server-origin><partKey>` and its KDoc
already names the cost:

> a change of *server address* -- LAN to remote, or either to a plex.direct
> relay -- still orphans the cache

It becomes `<machineIdentifier><partKey>`. That preserves the guard the origin
was there to provide — two servers can hand out the same part path for different
bytes — because `machineIdentifier` identifies a server more precisely than an
address it happens to answer on. A null `machineIdentifier`, from a session
written before that field existed, falls back to today's behaviour rather than
collapsing every legacy session onto one key.

`getStreamingCacheWriterFactory` constructs the same factory, so the preloader
and the player stay consistent for free.

Changing the key orphans whatever is in the streaming cache at upgrade. It is an
LRU cache, so that is one round of re-downloads rather than a leak. Recorded
here so it is not later mistaken for a caching bug.

## Testing

The probing spec's reasoning carries over unchanged:

> The bug being fixed is a *network* bug — an address that resolves, accepts no
> connection, and burns twenty seconds proving it. Tests that stub the network
> out would have had nothing to say about it.

So the book is tested against real sockets through MockWebServer, as
`ServerProbe` is.

Against real sockets:

- **The drive, reproduced.** Two live candidates, the first adopted, then shut
  down; `reprobe` adopts the second and persists it. This is the test the change
  exists for.
- **Collapsing.** N concurrent `reprobe` calls yield one race — the winner
  records exactly one `/identity` request, not N.
- **Already fixed.** A caller arriving with a now-stale address gets the current
  one back without issuing a probe.
- **Cooldown.** Two failed re-probes inside the window issue one race's worth of
  requests.
- **Escalation.** Stored candidates all dead, fresh list fetched from plex.tv,
  address from that list adopted.
- **Retry only on change.** `withAddressRecovery` must not re-run a call when
  the address did not move (one request, not two), and must when it did
  (exactly two).

Pure:

- **The session copy preserves identity**, as described above.
- **The candidate list is stamped** — a list from server A is ignored while the
  session points at server B.
- **Cache key** — one key across two origins with the same identifier and part
  path; different keys across identifiers; a null identifier falls back.
- **The resolver** — a `/library/parts/…` URI has its origin swapped; a
  `content://` artwork URI and a non-part path are untouched.

Two Robolectric hazards from CLAUDE.md apply. The book writes preferences, so
every test resets the candidate-list key and the session fields in `@Before`
rather than assuming absence; and anything touching `PlexApi` needs
`@RunWith(RobolectricTestRunner::class)`.

No new user-facing strings, so no locale work.

## Not in scope

**The dead-end message.** When every address fails, the car still shows its
generic failure rather than an explanation. Adding a message row would mean
overriding the browse-playback spec's deliberate choice to complete the future
exceptionally so "unreachable" reads differently from "rejected". With automatic
recovery in place, a total failure genuinely means the server is unreachable,
which is honest. Left alone on purpose.

**Account token rotation.** `MediaUrlBuilder` puts the token in the query
string, so a rotated token has the same staleness shape as an address. Different
trigger, different fix, not this bug.

## Verification

The failing case is a drive: sign in at home on wifi, leave, and both browse and
playback have to keep working without a visit to the More tab. On the bench,
dropping the head unit's wifi so it falls back to cellular is the same
transition.

Afterwards, `plex_server_uri` in preferences has to hold the address that
answers from the new network — the public or relay address, not the LAN one it
was signed in with.

The unit tests carry the logic; the `onAvailable` wiring and the ExoPlayer
resume are only observable on a device.
