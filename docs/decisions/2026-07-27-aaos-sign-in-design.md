# AAOS sign-in flow

**Date:** 2026-07-27
**Status:** Approved

## Context

Siskin now runs as an Android Automotive OS media source: the browse tree renders
in the system media template, search works, audio plays. One gap keeps it from
being self-sufficient on a head unit — **there is no way to configure a server
from the car.**

`34cc46d3` stopped the crash (`Subsonic.getParams()` dereferenced a null
`getAuthentication()`), but stopping a crash is not a flow. Browsing an
unconfigured install now fails the request and the car draws a dead-end: no
message worth reading, no button, no way forward. The only way to sign Siskin in
is to have already done it somewhere else, which on a car is nowhere.

This is a real feature, not a manifest change. Two pieces are missing and neither
exists today:

1. **No error carries a resolution.** `onGetLibraryRoot` returns success
   unconditionally (`MediaLibraryServiceCallback.kt:46`) and children fail with a
   bare `LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)`
   (`MediaBrowserTree.kt:683`). A car media template only draws a **Sign in**
   button when the error carries `ERROR_RESOLUTION_ACTION_LABEL` and
   `ERROR_RESOLUTION_ACTION_INTENT` extras.
2. **No screen to point that intent at.** The only activity is `MainActivity` —
   the whole phone UI, with a login flow that is a `Fragment` plus a
   `MaterialAlertDialog`.

### Correcting the earlier framing

The 2026-07-26 notes described the missing work as "a resolution `PendingIntent`
on the browse error plus a `distractionOptimized` setup screen." **The second
half of that is wrong**, and the correction shapes this design.

AAOS's UX restrictions include `UX_RESTRICTIONS_NO_KEYBOARD` — text entry is
among the things restricted while driving. A sign-in form *is* text entry, so it
cannot be distraction-optimized in a compliant way. Marking it
`distractionOptimized="true"` would be claiming something untrue about the
screen.

The correct move is to **not declare the metadata at all**, which is also what we
want behaviorally. Any activity without `distractionOptimized="true"` is taken
over by the platform's `ActivityBlockingActivity` once the car is moving. That is
the Pocket Casts behavior — a big tablet-style sign-in screen, available parked
only — and it is the platform default rather than something to implement. No
`CarUxRestrictionsManager` subscription, no gear or speed checks, no
restricted-mode layout variant.

Two things fall out of this:

- The sign-in screen is an **ordinary Android screen**. No `car-ui-lib`, no
  distraction-optimized layout constraints (item count caps, string length
  limits).
- The open "two launcher icons" item is **cosmetic, not a safety problem**.
  `MainActivity` is already blocked by the platform while driving. It is clutter
  on the head unit launcher, nothing more. Out of scope here.

  It also cannot be removed *first*. Until this flow exists, that `LAUNCHER`
  activity is the only way to configure Siskin on a head unit — clunky, but
  tapping it lands on `LoginFragment` and works. Removing it beforehand would
  leave the app unconfigurable in a car. Sign-in lands first; the icon goes
  after.

## Decisions

### Host the existing `LoginFragment` rather than build a new form

Three options were weighed:

| Option | Verdict |
|---|---|
| Purpose-built single form (URL / user / password, one button) | Cleanest car UX, but drops multi-server support and duplicates validation |
| **Thin activity hosting the existing `LoginFragment`** | **Chosen** — least new UI code, keeps multi-server support |
| Point the intent at `MainActivity` | Nearly free, but strands the user in the phone UI after sign-in instead of returning to the browse tree |

The chosen option inherits the existing three-step flow — empty server list, "+"
in the overflow menu, six-field dialog, tap the row to authenticate. That is
clunkier than a single form. It is accepted because it keeps one login
implementation rather than two, and multi-server support comes along for free.

`AppTheme` is already `Theme.Material3.Light.NoActionBar`, so
`setSupportActionBar` works in a new host. `fragment_login.xml` has no
`layout-land` or `layout-sw600dp` variant, so the list will stretch on a wide
head unit; the `MaterialAlertDialog` is width-constrained by the theme and is
fine. Stretching is acceptable for a screen used once.

### Extend `AppCompatActivity`, not `BaseActivity`

`BaseActivity.onCreate` fires the battery-optimization dialog, initializes the
Cast context, starts the downloader service, and opens a `MediaBrowser`
connection. None of that belongs on a sign-in screen, and a battery-optimization
dialog on a head unit is actively wrong.

To keep theming consistent without inheriting the rest, `BaseActivity`'s
theme-selection block moves into `ThemeHelper` and both activities call it. No
behavior change; one less duplicated block.

### Decouple `LoginFragment` from `MainActivity` with a one-method interface

`LoginFragment` casts its host to `MainActivity` (`LoginFragment.java:58`), but
only one use is genuinely MainActivity-specific:

| Site | Need | Resolution |
|---|---|---|
| `:77` `setSupportActionBar` | any `AppCompatActivity` | cast to `AppCompatActivity` |
| `:110`, `:142` `getSupportFragmentManager` | any `FragmentActivity` | `getParentFragmentManager()` |
| `:133` `goFromLogin()` | MainActivity-specific | new `LoginHost` interface |

`LoginHost` declares `onLoginSuccess()`. `MainActivity` implements it by
delegating to its existing `goFromLogin()`; `CarSignInActivity` implements it by
finishing. `LoginViewModel` is already scoped to `requireActivity()` and
`ServerSignupDialog` never casts to `MainActivity`, so both work unchanged under
a new host.

### Return the error from the browse layer, classify re-auth lazily

Confirmed present in media3 1.9.2 (verified by unzipping the AAR, not assumed):
`SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED`,
`SessionError.ERROR_SESSION_SETUP_REQUIRED`,
`MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT` and
`..._INTENT_COMPAT`.

`onGetLibraryRoot` keeps returning success so the app still opens; the error
returns from `onGetChildren` with `LibraryParams` extras carrying the label and a
`PendingIntent` targeting `CarSignInActivity`.

Re-auth is in scope: a stale password is more likely than a fresh install once
the app is in daily use, and without this it is an opaque dead-end in the car.

The awkwardness is that **Subsonic signals a bad password as HTTP 200 with an
error payload**. `AutomotiveRepository` has roughly fifteen copies of
`if (successful && body != null && field != null) { … } else { ofError(ERROR_BAD_VALUE) }`
(e.g. `:150`, `:189`), so a rejected password lands in the same branch as an
empty library and is indistinguishable from it.

Editing fifteen branches is the wrong shape. Classification happens once, at the
browse entry point:

- **No credentials stored** → error + "Sign in", returned immediately with no
  network call.
- **Credentials stored, root children request failed** → run
  `SystemRepository.checkUserCredential` once to classify. Auth rejected → error
  + "Sign in again". Anything else (unreachable host, DNS failure) → generic
  error and existing behavior, so an offline server does not present a
  misleading sign-in button.

The "are we signed in" predicate currently lives inline at `MainActivity.java:161`.
It moves to one place that both the activity and the browse gate share, so the
definition of signed-in does not drift.

A Retrofit interceptor inspecting Subsonic error codes centrally was considered.
It is cleaner in principle but changes error handling for the entire app, which
is a much wider blast radius than this feature justifies.

## What the emulator actually showed

The three flagged assumptions, the driving-block result, and three defects
the design did not anticipate, answered against `emulator-5554`
(`sdk_gcar_x86_64`), not assumed.

### Assumption #1 — which error draws the button

Answered in Task 4, first try, no fallback needed. `onGetLibraryRoot` was left
untouched and keeps returning success; the error comes from `onGetChildren`
only, using `SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED`. The car media
template drew both the message and a tappable **Sign in** button on the first
run. `ERROR_SESSION_SETUP_REQUIRED` was never tried — it remains genuinely
untested, not "tried and found worse."

### Assumption #2 — is `notifyChildrenChanged` required

**Yes, required** — but the first attempt at answering this got a false
negative, worth recording precisely because it looked like a clean result at
the time. The original A/B (`notifyChildrenChanged` called vs. not) showed no
difference in either arm: the sign-in screen stayed stuck regardless. The
conclusion drawn then was "neither required nor effective." That was wrong.
media3 1.9.2's `MediaLibrarySessionImpl` guards `notifyChildrenChanged` on
`isSubscribed()`, and subscriptions are dropped the instant `onSubscribe`
errors. This app never overrode `onGetItem`, so the default `onSubscribe`
(which delegates to it) returned `RESULT_ERROR_NOT_SUPPORTED` for every
subscription, including the root — so every subscribe request was rejected
and torn down before the notify could ever matter. Both arms of the original
experiment were structurally identical: the notify never left the process
either way, so the test had no discriminating power. This was a latent bug in
the whole app, not specific to sign-in — `notifyChildrenChanged` could not
have worked anywhere.

Fixed by adding `MediaBrowserTree.getItem()` and an `onGetItem` override
(`MediaLibrarySessionCallback.kt`), so subscriptions survive. Re-run with both
arms carrying that fix, varying only the notify call: with `invalidateRoot()`
in place, the tree repopulates after sign-in without backing out of the media
source; with it removed, the screen stays stuck. `onGetItem` deliberately does
not gate on `CredentialGate.isSignedIn()` the way `onGetChildren` does — see
the KDoc on that override for why gating it would reintroduce this exact bug.

### Assumption #3 — does a `PendingIntent` launch the non-exported activity

Confirmed clean. `adb logcat -d | grep -iE "Permission Denial|not exported"`
returned nothing mentioning `CarSignInActivity` across the verification runs.
`dumpsys activity activities` showed the resolution `PendingIntent`, fired
from `com.android.car.media` (uid 1010204), landing on `u10
io.github.codingismy11to7.siskin.debug/com.cappielloantonio.tempo.ui.activity.CarSignInActivity`
as the top resumed activity, with the activity's own logcat lines ("Displayed
... CarSignInActivity: +...ms") confirming a normal launch. The activity
stayed `exported="false"`; no defensive export was needed.

### Driving blocks the sign-in screen — confirmed, this is the load-bearing result

This was the whole reason `CarSignInActivity` carries no `distractionOptimized`
metadata, and until this task it had never been exercised. Verified by
injecting VHAL events directly (`cmd car_service enable-uxr` is gated behind a
platform signature and fails even as root):

```
adb shell cmd car_service inject-vhal-event 0x11400400 8   # GEAR_SELECTION = GEAR_DRIVE
adb shell cmd car_service inject-vhal-event 0x11600207 30  # PERF_VEHICLE_SPEED = 30 m/s
adb shell dumpsys car_service | grep "DO changed"
```

`dumpsys car_service` confirmed the transition (`No DO -> DO changed from 0 to
16`, settling with `Port: 0x00 UXR: DO: true UxR: 16`) — deliberately not read
via `get-property-value`, which reads the raw VHAL-backed value and can look
unchanged even when the UXR state genuinely flipped.

Tapping **Sign in** from the car's browse-error screen while this state was
active did **not** show the sign-in form. `dumpsys activity activities`
showed `u10 com.android.systemui/.car.activity.ActivityBlockingActivity` as
the top resumed activity, and a `screencap` (actually read, not just
captured) showed the platform's lock-icon screen: "You can't use this feature
while driving," with "Close app" / "Debug info" buttons. Logcat corroborates
the mechanism: `CarSignInActivity` does start (`ActivityTaskManager: START
... CarSignInActivity`, `Displayed ... CarSignInActivity: +88ms`), and
`CarPackageManagerService` immediately covers it —
`is_root_activity_do=false` in the blocking intent's extras is what triggers
`ActivityBlockingActivity` to launch on top, matching a `blocked_activity` of
`CarSignInActivity`. The net effect the driver sees is the platform's block
screen, not the form; the app-level activity underneath is never visibly
reachable while restricted.

One correction worth recording for whoever runs this again: restoring parked
state with gear value `1` (as an earlier draft of the verification steps
suggested) does **not** restore `GEAR_PARK` — `1` is `GEAR_NEUTRAL` in AOSP's
`VehicleGear` enum, confirmed against `dumpsys car_service`'s
`GEAR_SELECTION` property config (`[4, 1, 2, 8, 16, ...]`) and against
`CarDrivingStateService`'s own state log, which stayed at `1` (`IDLING`) after
that injection instead of returning to `0` (`PARKED`). `GEAR_PARK` is `4`.
Re-injecting gear `4` + speed `0` produced `CarDrivingStateService: changed
from 1 to 0` and `CarUxRestrictionsManagerService: DO -> No DO changed from 16
to 0` — confirmed parked, restrictions lifted, and `CarSignInActivity`
(never destroyed, only covered) reappeared on its own once the blocking
activity had nothing left to block.

### Three defects the design did not anticipate

- **A theme crash.** `CarSignInActivity` initially crashed on
  `setContentView` because it inherited the application theme
  `AppTheme.SplashScreen`, whose parent is not an `AppCompat` descendant.
  `MainActivity` and `CrashActivity` avoid this by calling
  `installSplashScreen()`; the new activity did not. Fixed by setting
  `android:theme="@style/AppTheme"` directly on the activity's manifest entry.
  This provides the theme that applies before `onCreate` (which `setContentView`
  needs); `ThemeHelper.applyActivityTheme()` still runs in `onCreate` and
  applies the user's AMOLED/dark override on top.
- **The dropped-subscription bug**, described above under assumption #2 —
  found only because Task 6's first A/B result looked suspiciously clean
  (identical in both arms) rather than because it was anticipated.
- **The offline-classifier path.** The design assumed an unreachable server
  would simply fail and never reach the re-auth classifier. Instead,
  `CacheUtil`'s `offlineInterceptor` rewrites requests with
  `Cache-Control: only-if-cached` when offline; with no cached response,
  OkHttp synthesizes a **504 Unsatisfiable Request** and calls `onResponse`
  rather than `onFailure`. This produces a successful future carrying a
  non-success result, so the classifier runs in the in-car case. The Sign in
  button is suppressed only by the null-body guard
  (`SystemRepository.isRejection()`), which was untested until extracted and
  given unit tests, then confirmed live with the OkHttp cache cleared.

### What this did *not* verify

All of the above, including the clean-install walkthrough (chooser → Siskin →
Sign in → add server → save → browse), was exercised against a **local Python
HTTP stub returning canned Subsonic `ping` payloads** (`/tmp/.../
subsonic_stub.py`, reachable from the emulator at `http://10.0.2.2:4040`;
test scaffolding, not committed), **not a real Subsonic server**. The stub
answers every path with the same fixed payload, so the top-level browse tree
(Home tab, category rows: Downloads/Playlists/Podcast/Radio/Folder) populated
correctly after sign-in, but drilling into a category — e.g. the Albums tab —
produced the car UI's generic "Something went wrong" (a clean, non-crashing
error; confirmed via logcat that no exception was thrown), because the stub
has no real album/artist data to serve. That leaves genuinely untested:

- **Playback.** Not possible with this stub — it serves no audio, and there
  is no real Subsonic server available in this environment. Not exercised,
  not fabricated as a pass.
- **Real library content** beyond the top-level tree shape (albums, artists,
  playlists, folders with actual entries).
- **Cover art.**
- **Anything requiring a populated catalog** (search results, instant mix,
  "made for you", queue resolution against real tracks).

Evidence for "it works" in this task is the top-level browse tree populating
after signing in from a fresh install, confirmed by `dumpsys` activity
identity and by logcat showing `BrowseTreeInvalidator` /
`onGetChildren`/`onGetItem` calls actually firing — not a screenshot of the
sign-in screen rendering, and not a claim that playback or deep browsing was
exercised.

## Scope

**In:** unconfigured sign-in, re-auth on rejected credentials, the browse-layer
resolution path, the hosting activity, the `LoginFragment` decoupling.

**Out:** the two-launcher-icons item (cosmetic, established above); a
purpose-built car login form; server management beyond what `LoginFragment`
already does; `privacy.html`; CI workflow.

## Testing

Unit-testable alongside the existing `AutomotiveRepositoryTest`: the
credential-state predicate, the resolution-params builder, and the error
classifier. The activity and the car-side rendering are emulator-verified.
