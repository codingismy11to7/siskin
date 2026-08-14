# The version line opens a server address panel

**Date:** 2026-08-14
**Status:** Approved

## Context

The car felt slow on the home LAN one evening, and there was no way to find out
why. The question — "am I reaching the server over the LAN, or going out to the
internet and coming back?" — has an answer the app holds and does not show.

`PlexSession.serverUri` is the address every request uses, and
`ServerAddressBook` keeps beside it every address the server advertises. Neither
appears anywhere in the UI. The only surfaces that mention a server at all are
sign-in and the library picker, and both are about *choosing* one rather than
reporting on the one in use.

The out-of-app routes are worse. There is no adb on a real head unit, so the
preferences file that holds the address is unreachable; `ServerProbe` builds its
own bare `OkHttpClient` with no logging interceptor, so its requests never
appear in logcat even when a log is obtainable. What is left is reading the
Plex server's own console from another machine to see which address the car came
in on — which works only for a server you administer, and not at all for a
shared one.

So the app knows, and the driver cannot ask.

## Decision

**Tapping the version line in Settings opens a dialog listing the addresses.**

### Why the version line and not a Settings row

The first shape considered was a Settings row showing the address, tapping
through to the detail. It was rejected on what it would do to Settings rather
than on cost.

Settings holds three toggles and Sign out. Every row changes something, and a
row that only reports would be the first that does not — teaching the screen a
second grammar for one entry, and inviting a press that expects to switch
servers. Past that, "show the address" is the first of a category: this is debug
information, and debug information accumulates. Each future addition would land
in the screen a driver uses to change three settings.

The version line is already the one purely informational thing on that screen,
already sits below Sign out where nothing else competes, and already answers a
"what am I running" question. Hanging "what am I connected to" off it groups the
two facts that belong together and leaves Settings' meaning intact. It gives
debug information a home that can grow without any of it leaking into Settings.

A single tap, not a tap-seven-times reveal. The audience is the person who built
the app; obscurity buys nothing, and undiscoverable-by-default is already
achieved by it being a tap on a version number.

### A dialog, not an activity or a new sign-in state

`PlexSignInState` describes the steps of signing in. A debug panel is not one,
and adding a branch for it would put diagnostics inside the state machine that
governs credentials.

A dialog also keeps `render()` free of new held state, which its existing
comment is explicit about avoiding — the screen rebuilds `choice_container`
every pass so rows read preferences rather than holding them.

An activity was rejected as pre-building for a panel that does not exist yet.
When the debug panel outgrows a dialog, that is the moment to promote it.

### The list is static

The dialog reads what is already persisted — the stored candidates and
`current()` — and probes nothing on open.

Probing every address on open was the alternative, and it is strictly more
informative: it distinguishes "the LAN address is down" from "the LAN address
lost the race", which is the distinction the motivating evening turned on. It
was rejected because the re-probe button below covers the same ground with code
that already exists, where per-address probing would need `ServerProbe.answers`
made public and a new result type to carry the outcomes.

`ServerAddressBook` gains one read-only accessor for the stored candidate list.
That is the only non-UI addition.

### A re-probe button, and why it is not a server switcher

The dialog carries a button that calls `reprobe`, re-racing the known addresses
and adopting the winner.

This is deliberately an action in a panel that otherwise only reports, and the
line it must not cross is switching *servers*. It does not: `machineIdentifier`
says which server, `serverUri` says only how to reach it, and `adoptAddress`
moves `serverUri` alone while carrying `machineIdentifier`, the section key and
the server token forward from whatever session is current. The address book
design draws that distinction already and a test guards it. Choosing a different
server or library remains solely the library picker's job, in the More tab.

Two properties of `reprobe` decide the button's shape:

**The cooldown must be bypassable.** `reprobe` returns null without probing when
the last attempt failed within `FAILURE_COOLDOWN_MS`. That is correct for its
automatic callers — the comment records that it exists to stop a car with no
usable network paying a full race per browse tab, serially — but it is wrong for
a human pressing a button while parked, which is precisely the situation after a
failure. `reprobe` gains `force: Boolean = false`, skipping the cooldown check.
Defaulting false leaves every existing caller unchanged.

**The successful case is invisible.** When the same address wins again, the
adopt is a no-op and the list redraws identically. A button whose success looks
like nothing happening reads as broken, so the dialog reports which of three
things occurred: the address moved, the address is unchanged, or nothing
answered.

### Debug strings are `debug_`-prefixed and untranslatable

The dialog's text — title, button, the three outcomes — is marked
`translatable="false"` and named `debug_*`.

Siskin ships five complete locales and `MissingTranslation` is a real defect
here, a check the repository was trained out of ignoring once already. Marking a
string untranslatable is therefore the obvious loophole, and this is a
deliberate use of it rather than an unnoticed one: the text is diagnostic
output, read by the app's author, in vocabulary — probe, candidate, address —
that is not user-facing copy. Addresses themselves are data and were never
translatable.

The prefix is what keeps the exemption auditable. Every `translatable="false"`
string should be a `debug_` one and every `debug_` string should be
untranslatable, so the check is a grep and a `car_settings_` string wearing the
attribute stands out as the thing to question.

Kotlin literals in the dialog were the alternative, sidestepping
`MissingTranslation` entirely since it only inspects resources. Rejected: it
scatters display text into code and departs from how every other string in the
app is handled, to save five lines.

## Testing

`ServerAddressBook`'s new accessor and `reprobe`'s `force` flag are the testable
surface, and both are unit-testable against the existing fixtures:

- the accessor returns the stored candidates, and returns nothing when the
  stored list's `machineIdentifier` stamp does not match the session's
- `force = true` probes despite an armed cooldown; `force = false` still does
  not, which is the existing behaviour and must stay
- the existing guarantee that a reprobe moves `serverUri` and nothing else
  already has a test; it covers the button's path unchanged

The dialog itself is presentation over those values.

## What this does not buy

- **No history.** The panel reports the current state, not what the address was
  an hour ago, so a transient failure that has already resolved leaves no trace.
- **No per-address reachability.** A static list cannot say why a given address
  is not in use. Pressing re-probe answers it indirectly, by moving or not.
- **No help on a shared server's address list** beyond what plex.tv advertises,
  which is the same list the app has always raced.
- **Nothing for a moving car.** `CarSignInActivity` carries no
  `distractionOptimized` meta-data — a sign-in form cannot be compliant while
  AAOS restricts keyboard input — so the platform blocks the screen while the
  car is in motion. This panel is readable at a standstill only, which is what
  allowed it to be as dense as it needs to be.
