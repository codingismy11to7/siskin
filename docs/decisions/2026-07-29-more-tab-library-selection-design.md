# A More tab, and switching library from it

**Date:** 2026-07-29
**Status:** Approved

## Context

Siskin's browse root has been three fixed tabs since PR #11 — Playlists, Artists,
Albums. Choosing *which* server and library those tabs read from happens exactly
once, inside the sign-in flow: `PlexSignInViewModel.chooseServer()` probes the
advertised connections and holds the winner as a candidate, and `chooseLibrary()`
commits the whole `PlexSession` in one write.

There is no way to change that choice afterwards. An account with a music library
and an audiobook library, or with two servers, has to sign in again to move
between them — and sign-in means the QR/PIN dance, which is parked-only.

This spec adds a fourth root tab, **More**, whose only occupant for now is
**Select Library**.

## What the platform actually allows

The interaction was chosen against measured behaviour rather than documentation,
because the documentation is thin here and two of the four things we assumed
turned out to be wrong. Measured on the `sdk_gcar_x86_64` API 33 emulator against
`com.android.car.media` (`/system/priv-app/AAECarMediaApp`, versionCode 34).

The car states its own limits in the root hints, which arrive intact in
`onGetLibraryRoot`'s `LibraryParams.extras` — `LegacyConversions
.convertToLibraryParams` passes the whole `Bundle` through, stripping only
`KEY_ROOT_CHILDREN_SUPPORTED_FLAGS`:

```
android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS                       = 256
androidx.car.app.mediaextensions.KEY_ROOT_HINT_MEDIA_SESSION_API      = 1
androidx.car.app.mediaextensions.KEY_ROOT_HINT_MAX_QUEUE_ITEMS_WHILE_RESTRICTED = -1
androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT      = 4
androidx.media.utils.MediaBrowserCompat.extras.CUSTOM_BROWSER_ACTION_LIMIT = 8
```

| Claim | Measured |
|---|---|
| Root children limit 4 | **Enforced.** Five root nodes were sent; four rendered and the fifth vanished silently. |
| Content depth 3 | **Not enforced.** A synthetic chain rendered to depth 5 while driving at DO=255. |
| Custom browse actions | Supported, but **only on playable rows** — see below. |
| A playable row that plays nothing | **Opens Now Playing regardless.** |

**More is therefore the last tab.** Anything that later wants to be a root entry
has to live inside More or displace one of the three music tabs. That is the
single most consequential number here, and it is why More is specified as a
container from the outset rather than as a row that happens to be called More.

The depth limit not being enforced is what makes a four-level path
(More → Select Library → Server → Library) viable at all. Note it is *advertised*
as 3, so a head unit that does enforce it would truncate the leaf. The design
below survives that: see "If depth is enforced elsewhere".

### Why custom browse actions are rejected

They were the obvious fit — media3 1.9.2 supports them first-class via
`MediaLibrarySession.Builder.setCommandButtonsForMediaItems` for the action
catalogue and `MediaMetadata.Builder.setSupportedCommands` for the per-row opt-in,
with the tap arriving in `onCustomCommand` — and the result bundle can carry both
a toast (`..._RESULT_MESSAGE`) and a node to navigate to
(`..._RESULT_BROWSE_NODE`). Built end to end, it worked exactly as advertised:
tapping "Use this library" toasted "Now browsing Music (Basement)" and popped back
to the More root.

It is rejected because **the action affordance renders only on playable rows.**
Verified by A/B against otherwise identical items:

| Row flags | Action icon |
|---|---|
| `isBrowsable=true, isPlayable=false` | absent |
| `isBrowsable=false, isPlayable=true` | renders |

And a playable row is a live tap target whose body cannot be neutralised.
Returning an empty list from both `onSetMediaItems` and `onAddMediaItems` still
opened Now Playing — showing "No Title" and an empty transport, which is worse
than the "Source error" it replaced. The car navigates on the *tap*, not on
playback succeeding.

So the choice was between a library row that hijacks Now Playing when mistapped,
and one that does not. Two flows were considered to make the hijack meaningful —
body tap means "switch and shuffle this library", or simply accepting the dead
tap — and both were rejected as unintuitive. A row in a settings-shaped list
should not start music.

### Why not a settings screen

A settings surface is genuinely available and cheap: an activity with an
`android.intent.action.APPLICATION_PREFERENCES` filter, which is the documented
AAOS mechanism and what UAMP does. Declaring one made a gear icon appear in the
car media app's toolbar, and tapping it launched the activity. No new dependency.

It is the wrong home for this, for one measured reason: **it is parked-only.**
Under DO=255 the gear dims and taps do nothing — zero launches. Choosing which
library you are browsing is a browse-scope decision, not a preference, and it
should work while moving.

The Car App Library route (`androidx.car.app.category.SETTINGS`, which the
installed templates host recognises) *is* distraction-optimised and would work
while driving, but it means a new dependency and a second UI paradigm in an app
whose recent history is deleting them. It remains the right answer for a real
settings surface — sign out, replay gain, cache — and that stays out of scope.

## Decision: browsable rows, and selection is a side effect of browsing

```
More                        (root tab 4 of 4)
 └ Select Library
    ├ <server>
    │   ├ ✓ <library>
    │   └   <library>
    └ <server>
        └   <library>
```

Every node is browsable and nothing is playable. Tapping a library **commits the
selection**, then invalidates the *parent* server node so the car re-fetches and
re-renders the list it is already showing, with the tick moved.

The observed effect is that the user stays exactly where they are and the tick
moves — no screen push, no Now Playing, no toast machinery. The log shows why:

```
onGetChildren [library]        <- the tap
notifyChildrenChanged([server])
onGetChildren [server]         <- re-render supersedes the pending push
```

### The confirmation row is deliberate, not dead code

`getChildren(<library>)` still returns a single row reading
"✓ Now browsing <library>". In the observed behaviour the car never draws it,
because the parent invalidation supersedes the push.

**This is a race, not a guarantee.** The invalidation happens to win on the
emulator; on a slower head unit the push may land. Both outcomes are coherent —
win and you stay put with the tick updated, lose and you get a sentence
confirming what you just did — and that is the reason the row exists rather than
returning an empty list or an error, either of which would degrade into a blank
screen or a false failure.

A future reader will find a row that appears never to render. It must not be
deleted on that evidence.

That row is marked browsable purely so the car will draw it: **an item with
neither `isBrowsable` nor `isPlayable` set is dropped from the list entirely.**
Tapping it returns itself.

### The tick reflects the stored session, not what you tapped

The tick is not a record of a selection made during this navigation. It is drawn
from the persisted `PlexSession`, so **the library you are already using is ticked
the first time you ever open Select Library**, before anything has been tapped and
on a cold start after a reboot.

Stated explicitly because the mock got this wrong in a way that was invisible: it
tracked the selection in an in-memory field starting at null, so nothing was
ticked until you picked something. That reads as "no library selected" on the one
screen whose job is to tell you which library is selected.

There is a wrinkle in deciding *which* row matches. A section key is only unique
within a server — keys are small integers, so the same key almost certainly exists
on every server in an account — which means matching on `musicSectionKey` alone
would tick the wrong row under a server the user is merely browsing past. The
match is therefore on the pair: this server's URI **and** the section key.

That pair is not quite exact either. `serverUri` records the address that answered
when the session was committed, and `ServerProbe` may pick a different one now —
the LAN address then, the relay today. The consequence is a **false negative**: no
row ticked under a server that is in fact the current one. That is the safe
failure, and it is preferred over matching on the key alone, which fails the other
way and ticks a library the user is not using.

Making it exact means persisting the server's `machineIdentifier` (from
`Resource.clientIdentifier`) alongside the rest of the session and matching on
that. It is a small, self-contained addition — one more field on `PlexSession`, a
new SharedPreferences key, and existing installs simply have it absent and fall
back to URI matching until their next commit. **Do it as part of this work**: the
tick being right is most of the value of this screen, and a tick that disappears
when you switch networks is exactly the sort of thing that gets reported as a bug
years later with no way to reproduce it.

### The tick needs an explicit invalidation

The car caches a browse list and does **not** re-fetch it when you navigate back
into it. Without `notifyChildrenChanged` on the server node the tick renders from
stale data — it silently marks whatever was selected when that screen was first
loaded. This was observed before it was reasoned about.

`BrowseTreeInvalidator` gains an `invalidateNode(nodeId, childCount)` beside the
existing `invalidateRoot()`, carrying the same main-thread requirement.

## Wiring it to Plex

The mock's servers and libraries are invented. The real ones come from machinery
that already exists, and the flow deliberately mirrors sign-in rather than
inventing a second way to do the same thing.

| Node | Source |
|---|---|
| Select Library → servers | `AuthClient.getResources()`, narrowed by `AuthClient.mediaServers` and `ServerProbe.hasUsableConnection` |
| Server → libraries | `ServerProbe.bestConnectionUri(resource)`, then `LibraryClient.getSections()` narrowed by `LibraryClient.musicSections` |
| Library → commit | construct and persist one `PlexSession` |

### Nothing is persisted until the leaf

`PlexSession` is all-or-nothing by construction. Entering a server here does the
same as `chooseServer` does today: probe, and hold the winning URI and `Resource`
as an in-memory **candidate**. Only tapping a library writes.

This adds the **second** session write site in `app/src/main`. CLAUDE.md currently
records that `chooseLibrary` holds the only one, and that sentence stops being
true with this change — it must be updated in the same PR, not left to rot. The
invariant it was protecting is unchanged and now has two enforcers: a session is
constructed whole or not at all, and no partial state is ever persisted.

This matters more in browse than it does in sign-in. A browse tree is something
users wander through and back out of; if entering a server mutated the stored
session, backing out would leave a working install signed out, or — worse —
pointed at one server with another's section key. The existing candidate pattern
already prevents that, and this feature must not be the thing that reintroduces
it.

The candidate is per-navigation state, not a field that outlives the pick.

### After the switch

Two things must happen, and only one of them needs code.

`PlexBrowseRepository.refreshClients()` is self-healing — it rebuilds
`LibraryClient` and `SearchClient` whenever `api.session != clientsSession` — so
the pinned-at-construction clients pick up the new server on the next browse call
with no explicit call. Nothing to wire.

The three music tabs, however, are showing the old library's contents.
`BrowseTreeInvalidator.invalidateRoot()` after the commit is what makes the car
re-fetch them, exactly as `CarSignInActivity` already does after sign-in.

### The queue is only poisoned by a *server* change

Stream URLs are rebuilt from a stored `partKey` against the current `serverUri`
(see the browse/playback spec). So after switching servers, every queued
`partKey` is rebuilt against a server it did not come from — 404s, or a valid URL
for the wrong track.

The distinction that matters: **Plex `ratingKey`s are server-wide, not
section-scoped.** Switching library on the *same* server leaves the queue
entirely valid. Only a change of server invalidates it.

So the queue is cleared when, and only when, the committed `serverUri` differs
from the previous one. Clearing on every library switch would needlessly stop
playback for the common case of moving between music and audiobooks on one
server.

Playback is not stopped on a library switch. It is stopped on a server switch,
because the queue backing it has just been discarded.

### Degradation when plex.tv is unreachable

The server list needs `AuthClient.getResources()`, which is a plex.tv call. A car
on a LAN with a reachable Plex server but no internet can browse music perfectly
well and cannot enumerate servers.

Select Library therefore surfaces the transport failure as an error on *that*
node only. It must not be reported as "no servers", which would read as an empty
account, and it must not touch the stored session — the current library keeps
working. `PlexTransportFailure` already carries the host it failed against, so
"could not reach plex.tv" falls out of the type.

### If depth is enforced elsewhere

The leaf sits at depth 4 and the car advertises 3. If a real head unit enforces
what this emulator ignores, the library level is unreachable while driving.

The fallback is to drop the "Select Library" row and hang the servers directly
off More, which is depth 3. That is a strictly smaller change than it looks —
More's children become the server list instead of a single row — and it is
recorded here so it is recognised as a known remedy rather than rediscovered as
a redesign. It is not done now because More is a container that will gain
siblings to Select Library, and collapsing it pre-emptively would trade a real
structure for a hypothetical constraint.

## Not decided here

**What else lives in More.** Nothing else is specified, and nothing is queued up.
The point of the container is that whatever arrives later costs a row rather than
the last tab slot.

**Whether a single-server account should skip the server level.** It would save a
tap for the common case and make the path depth 3, but it makes the shape of the
tree depend on account contents, which is harder to reason about and to test.
Deferred until there is a reason beyond neatness.

## Verification

`./gradlew testDebugUnitTest` and `./gradlew assembleDebug`, per CI.

New unit tests, plain JUnit where the logic is pure:

- **The root has four children, in order, with More last.** `MediaBrowserTreeTest`
  already asserts a three-child root; it is extended rather than replaced, because
  that test is the executable statement of the tab decision and the tab budget is
  now load-bearing.
- **Tick placement** — the selected library renders ticked and no other does,
  including after the selection moves.
- **Tick on a cold read** — with a stored session and no selection made, the
  matching library is ticked. This is the case the mock silently failed, so it is
  written as a test rather than trusted to be obvious.
- **Tick disambiguates by server** — two servers exposing the same section key
  tick only the one belonging to the stored session.
- **Candidate isolation** — entering a server and abandoning the navigation leaves
  `PlexApi.session` byte-for-byte unchanged. This is the regression that would
  sign a user out, and it is the one test worth writing first.
- **Queue clearing predicate** — same server different library keeps the queue;
  different server clears it. Pure function over two `PlexSession`s, tested
  directly rather than through the browse tree.
- **plex.tv failure narrows to that node** — a `PlexTransportFailure` from
  `getResources` surfaces as an error on Select Library and does not clear the
  session.

On the emulator: switch library within a server and confirm the three music tabs
show the new contents and playback continues; switch server and confirm the queue
is discarded; back out of a server without picking a library and confirm the app
is still signed in and still browsing the original library.

Both switches are also exercised while driving — `cmd car_service enable-uxr true`
plus gear `DRIVE` and parking brake off, since **injecting speed alone does not
change the driving state** and produces a passing result that means nothing.

## Not in scope

Any settings surface, including sign out and the `APPLICATION_PREFERENCES`
activity. The Car App Library. Custom browse actions. Offline playback. Renaming
the `com.cappielloantonio.tempo` package.
