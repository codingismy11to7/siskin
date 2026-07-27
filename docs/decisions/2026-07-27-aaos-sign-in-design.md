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

## To verify on the emulator, not assume

The standing note-to-self about conclusions drawn from a single noisy sample
applies directly. These are assumptions, flagged as such:

- Whether the resolution button renders from `onGetChildren` or must come from
  `onGetLibraryRoot`, and whether `ERROR_SESSION_SETUP_REQUIRED` draws it or only
  `ERROR_SESSION_AUTHENTICATION_EXPIRED` does. Default to the authentication code
  unless setup-required is demonstrated to work.
- Whether the car re-requests children after `CarSignInActivity` finishes, or
  whether `notifyChildrenChanged` must be called on the session. Assume it must
  be called, and verify.
- Whether a `PendingIntent` launches a non-exported activity. It should, since
  the system sends it with the creating app's identity. Keep the activity
  non-exported and confirm, rather than exporting defensively.

Evidence for "it works" is a populated browse tree after signing in from a fresh
install — not a screenshot of the sign-in screen rendering.

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
