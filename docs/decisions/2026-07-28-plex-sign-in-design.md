# Plex QR sign-in

**Date:** 2026-07-28
**Status:** Approved

## Context

The Plex API client layer landed in PR #7 and the browse tree was cut to three
tabs in PR #11. Neither touched authentication: Siskin still signs in through the
inherited Subsonic form, storing a list of servers with typed usernames and
passwords.

This spec replaces that with the Plex PIN flow, and is the first of the specs the
API layer deferred. It is deliberately *not* the whole Subsonic conversion.

### Why sign-in goes first

The remaining conversion is two sub-projects, not one. Sign-in is independent.
Browse and playback are not separable from each other — `AutomotiveRepository`
hands its results to `MappingUtil`, which builds the stream URI and the extras
bundle that `MediaManager`, `ReplayGainUtil` and `SessionMediaItem` all read, so
the moment browse returns Plex `Metadata` the mapping and playback follow in the
same change. Deleting `subsonic/` is then compiler-driven cleanup, exactly as the
three-tab sweep was.

Sign-in leads because nothing else is verifiable without it. Every server-side
Plex call needs a token and a discovered base URI, so building browse first means
pasting a token into preferences by hand and writing scaffolding that exists only
to be deleted.

The accepted cost is one PR in which the app browses nothing: the Subsonic
credentials are gone but `AutomotiveRepository` still asks Subsonic for them. With
no users on this fork, a broken window of one PR is cheaper than throwaway
scaffolding.

That cost is smaller than it looks, because **this flow verifies itself.**
Selecting a music library requires a successful `GET /library/sections` against
the chosen server. Reaching the library picker *is* the proof that discovery,
connection selection and server authentication all work — no browse tree needed.

## The flow

`CarSignInActivity` is unchanged: same `LoginHost` seam, same
`onLoginSuccess()` → `BrowseTreeInvalidator.invalidateRoot()` + `finish()`, same
deliberate absence of `distractionOptimized` in the manifest. It swaps
`LoginFragment` for a new `PlexSignInFragment`, driven by a `PlexSignInViewModel`
that owns the poll loop so it survives fragment recreation and cancels in
`onCleared`.

```
CreatingPin ──▶ AwaitingApproval(code, qrUrl, expiresAt)
                    │ poll every 2s
                    ├─ Authorized ──▶ ChoosingServer(List<Resource>)
                    │                       │ tap
                    │                       ▼
                    │                  ChoosingLibrary(List<Directory>)
                    │                       │ tap
                    │                       ▼
                    │                     Done ──▶ onLoginSuccess()
                    └─ Expired ──▶ Failed(retry)
```

`Failed` is reachable from any state, carries a message and a retry action, and
restarts at `CreatingPin`.

### The approval screen

Glide loads `Pin.qr` into an `ImageView`, tinted black on a white background with
padding for a quiet zone. Beside it: *"Scan the QR code — or go to plex.tv/link
and enter:"* followed by the code in large type. Both routes are shown at once
rather than one being a fallback for the other; a passenger with a phone camera
and a driver reading four characters aloud are equally likely.

Glide is already a dependency and `Pin.qr` is already modelled, so this adds
nothing. The alternative — bundling a QR encoder such as zxing to render the
`plex.tv/link` URL at native display resolution — buys control over size, quiet
zone and contrast, which is not nothing for a phone camera pointed at a dash
screen from arm's length. It was deferred until there was evidence it was
needed, and on-device verification against live plex.tv responses found none:
`Pin.qr` resolves to a usable image, so the encoder fallback does not get built.

**That verification also found `Pin.qr` is not a finished image — it is a
tintable mask.** Its transparent pixels are the QR's light modules and its
opaque white pixels are the dark ones. Loaded raw, composited on Siskin's light
theme it flattens to a single colour and is invisible; composited on black it
renders, but with inverted polarity and no quiet zone. Tinting the opaque
pixels black and placing the image on a white background with padding does
what compositing can't: an `ImageView` tint recolors non-transparent pixels
while preserving alpha, which is exactly this mask's semantics, so the white
background shows through the transparent light modules and produces a
standard-polarity QR with a real quiet zone. This is why the `ImageView` above
carries the tint and background rather than showing `Pin.qr` as loaded.

**The same verification pass caught a second problem, this time in `createPin`
rather than the layout.** `strong=true` was carried over from the API-layer
spec unquestioned; against live plex.tv it returns a 25-character code, not the
short one this screen's copy promises the user can type at plex.tv/link.
Nobody types 25 characters into a phone, so `strong` is dropped — a bare
`POST /pins` returns the 4-character code the copy above actually needs. The
grant it produces is still bound to this install's `X-Plex-Client-Identifier`,
which is what keeps a 4-character code acceptable rather than guessable. This
amends the API-layer spec the same way the token split below does: that spec's
Auth section states `POST /pins?strong=true` returns "a short code," which the
live response shows is not true — the short code comes from omitting `strong`,
not from setting it.

### Polling

Every 2 seconds through `PlexPinState.evaluate`, which already exists and already
covers the three outcomes. Its KDoc says, of a pin whose expiry cannot be parsed,
*"Never expire a pin we cannot date — the caller bounds the poll loop."* This is
that caller, so the bound is explicit: the pin's own `expiresAt` via
`AuthClient.expiresAtEpochSeconds`, plus a 15-minute hard cap from pin creation
for the unparseable case. Without the cap, an unparseable expiry polls forever.

## Both pickers always render

An account can expose several servers, and each server several music libraries —
"Music" and "Audiobooks" is a common pair. The user picks both, every time, even
when there is only one candidate.

This is not a reversal of "multi-server support is ripped out." Nothing is stored
as a switchable list and nothing is switched between; it is a one-time choice
recorded in preferences.

Auto-selecting when unambiguous was considered and rejected. **There is no
settings surface**, by the three-tab spec's decision, so a wrong pick is
unfixable short of reinstalling. Against that, two taps on the common path is
cheap, and always showing the choice means the user always sees which server and
which library they connected to.

`LibraryClient.musicSections()` already exists and already returns a list — the
API layer anticipated this. Servers are filtered to those whose `provides`
contains `server`, and the connection URI comes from
`AuthClient.bestConnectionUri`, which already prefers LAN over direct-remote over
relay.

## The token split

**This amends the approved API-layer spec.**

`Resource` carries an `accessToken` per server. `PlexApi` holds a single `token`
and `PlexIdentity` stamps it on both Retrofit instances. That works for a server
you own, where Plex accepts the account token — and fails for a shared server,
which requires that resource's own `accessToken`.

Showing the server picker makes a shared server reachable, so the token becomes
two:

| Value | Source | Used by |
|---|---|---|
| `accountToken` | the approved PIN | `PlexRetrofitFactory.plexTv()` |
| `serverToken` | the chosen `Resource.accessToken` | `PlexRetrofitFactory.server()` |

`PlexApi` also gains `musicSectionKey`, persisted alongside `serverUri`.
`PlexIdentity` needs no change: it already takes the token as a parameter, which
is what makes the split a two-line change rather than a rework.

Falling back to the account token when a resource has no `accessToken` is
correct — that is the owned-server case.

## What comes out

The Subsonic sign-in stack is a closed island: `LoginFragment` → `LoginViewModel`
→ `ServerRepository` → `ServerDao`/`Server`, plus `ServerAdapter` and
`ServerSignupDialog`. Nothing outside it references `Server`, and `LoginFragment`
is the only writer of the Subsonic credential preferences. It goes whole, along
with `SystemCallback` and `SystemRepository.checkUserCredential`, whose only
consumer it was.

**Two dependencies retire.** The three-tab spec kept `androidx.recyclerview`
("`ServerAdapter` is a `RecyclerView.Adapter`") and `androidx.coordinatorlayout`
("`fragment_login.xml` uses `AppBarLayout` scroll coupling") and justified both by
name. Those are now their only uses anywhere in the project, so both go. The
pickers are a vertical `LinearLayout` in a `ScrollView` with one `MaterialButton`
per choice, added in code — at one to five entries a `RecyclerView` earns nothing,
and skipping it is what retires the dependency.

`res/menu/` empties entirely: `login_page_menu.xml` was the last survivor of the
23 the three-tab sweep started from, and `LoginFragment`'s toolbar was the only
thing inflating it.

### What deliberately stays

`App.getSubsonicClientInstance`, `SubsonicPreferences`, `SystemRepository` and the
Subsonic credential keys in `Preferences` all survive, because
`AutomotiveRepository` still calls them until the browse slice. Deleting them here
would mean stubbing out `MediaLibraryServiceCallback.classifyFailure` and
restoring it two PRs later — a deliberately degraded error path in exchange for a
slightly smaller diff.

`CredentialGate` splits along the same line. `isSignedIn()` becomes Plex-shaped:
account token, `serverUri` and `musicSectionKey` all present. The server token
is deliberately *not* part of the gate — `Resource.accessToken` is null for a
server the account owns, so requiring it would read as signed-out forever.
`PlexApi.serverHeaders()` supplies the account-token fallback instead. The
Subsonic error codes 40/41/50 in `isAuthFailure` move into `SystemRepository`,
which becomes their only consumer, and die with it in the browse slice.

`ClientCertManager` is a surviving oddity worth naming: it reads
`Preferences.getServer()` to find a per-server client-certificate alias, and both
`App` and `subsonic/RetrofitClient` still call it. Plex has no client-certificate
story in this design. It is a question for the browse slice, not this one.

Room goes from version 22 to 23 with a `DropServerTable` auto-migration spec,
following the `DropTablesForPrunedFeatures` and `DropPlaylistTables` precedent
already in `AppDatabase`.

The nine `login_*` and `server_signup_dialog_*` strings go from `values/` and all
15 locale directories. New strings are English-only; translations arrive if
contributed.

## Testing

Four new tests, all pure — no Robolectric, and none of them relying on
`unitTests.returnDefaultValues = true` to pass while asserting nothing:

- **`PlexSignInStateTest`** — the flow as a function over PIN polls and discovery
  results. The one executable statement of the decisions in this spec.
- **Poll bounding** — an unparseable `expiresAt` still terminates at the
  15-minute cap. This is the failure mode `PlexPinState`'s KDoc warns about, and
  the only way to catch it is a test, since it manifests as a loop that never
  ends rather than a wrong answer.
- **Token routing** — plex.tv receives the account token, the server receives the
  resource token, and a resource with no `accessToken` falls back to the account
  token.
- **Resource filtering** — only devices whose `provides` contains `server`.

The five `isAuthFailure` assertions move from `CredentialGateTest` into
`SystemRepositoryTest`, following the code; `CredentialGateTest` is rewritten
against the Plex predicate. The other ten test classes stay green untouched —
**a red Plex test means the token split broke the API layer**, which is the
sharpest signal available that this change overreached.

## Verification

`./gradlew assembleDebug` and `./gradlew test`, then on the emulator: confirm the
browse error still offers the sign-in resolution, complete a real PIN approval end
to end, and confirm both pickers render.

Browse still fails afterwards. That is expected — `AutomotiveRepository` is still
Subsonic — and reaching the library picker is the proof that the Plex half works.

## Not in scope

Browse-tree mapping onto Plex libraries. Playback, streaming URLs and artwork.
Scrobbling via `/:/timeline`. Removal of the `subsonic/` package. Any settings
surface, including signing out. Renaming the `com.cappielloantonio.tempo` package.

## Licensing note

Phoebe, the author's other project, is already a Plex/Jellyfin AAOS client with a
working PIN sign-in, and it is a fork of an unlicensed repository while Siskin is
GPL v3 via tempo. This is the spec where the temptation is sharpest, because the
problem is identical and already solved over there. The discipline holds: transfer
findings, never source.
