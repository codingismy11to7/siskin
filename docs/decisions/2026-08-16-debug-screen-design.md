# The debug panel becomes a screen, and opens the server picker

**Date:** 2026-08-16
**Status:** Approved

## Context

The server picker — `PlexSignInState.ChoosingServer`, rendered by
`PlexSignInFragment` — cannot be looked at without signing in. `signIn()` mints a
PIN and blocks on `awaitApproval` before it ever publishes that state, and there
is no path around it: `ChoosingServer` has three publishers, all private to
`PlexSignInViewModel`, and the only intent extra read anywhere in `main` is
`EXTRA_FORCE_SIGN_IN`. So checking that the picker still renders correctly costs
a real PIN approval on a phone, every time.

That is a testing gap rather than a missing feature. More → Select Library
already switches server and library — `LibraryPickerRepository` serves "which
servers the account has, which music libraries each has, and committing the
choice" — and it stays exactly as it is. What is wanted is a way to reach
*that particular screen*, the one the sign-in flow draws, so that a regression in
it is visible without an authentication round trip.

The natural home is the debug panel behind the version line, which
`2026-08-14-server-address-debug-design.md` established. That panel is an
`AlertDialog`, and an `AlertDialog` has exactly three button slots. OK and
Re-probe hold two. Adding the route would spend the last one, and the next debug
affordance would force a restructure anyway.

That design anticipated this: *"An activity was rejected as pre-building for a
panel that does not exist yet. When the debug panel outgrows a dialog, that is
the moment to promote it."* This is that moment.

## Decision

**The debug panel becomes `CarDebugFragment`, a full screen pushed onto the back
stack from the version line, and it carries a row that opens the real server
picker.**

### A screen, not a fourth button

`CarHostActivity` already hosts three screens and gained state-driven routing in
#119, so a fourth destination costs nothing structural: the debug screen is pushed
with `addToBackStack` exactly the way Customize tabs pushes
`BrowseTabOrderFragment`, and the router's existing "a pushed screen owns the
container" guard leaves it alone, including across a uiMode flip.

A dialog with a third button was the cheaper option and was rejected on two
counts. It spends the final slot on the second-ever debug action, so the third
one pays for the promotion regardless. And `AlertDialog`'s buttons are
phone-sized, on a screen where every other control is deliberately 72dp because
the taps happen at arm's length in a car — `addChoice` zeroes MaterialButton's
insets for exactly this reason.

A custom view inside the dialog would have fixed both without touching
navigation. It was rejected as the halfway house: it accepts the cost of building
a scrollable action list while keeping the constraint — a dialog — that made the
list necessary.

The promotion also deletes the panel's most awkward code. `showAddressPanel`
currently replaces the neutral button's click listener *after* `show()`, because
`AlertDialog`'s dismiss-then-run contract would tear the dialog down during a
re-probe that can take tens of seconds. On a screen there is nothing to dismiss:
the row disables itself and the body updates in place, and that workaround and
its explanatory comment go away with it.

### What moves, and what does not

`buildAddressPanelBody` moves untouched, along with its tests. It is the one
framework-free piece of this feature — no `Context`, no resource lookup — which
is what lets it be asserted directly under `unitTests.returnDefaultValues`, and
none of that changes with its address.

The list stays static, reading `knownAddresses()` and probing nothing on open.
Re-probe keeps `force = true` and keeps reporting which of three things happened,
because a button whose success looks like nothing happening still reads as
broken.

More → Select Library is untouched. This is deliberate: the two are not
redundant, because this route exists to exercise the sign-in flow's picker, and
a second implementation that could regress independently would defeat that.

### Opening the picker pops first, then publishes

The router in #119 returns early while the back stack is non-empty. The debug
screen is *on* that back stack, so it has to remove itself before the state
changes:

1. `popBackStackImmediate()` — synchronous, deliberately. `popBackStack()` posts,
   so the router could still observe a count of one and refuse to move, leaving
   the debug screen on display while the state advanced underneath it.
2. `reopenServerPicker()` publishes `Working` synchronously and launches the
   fetch.
3. The router sees `Working` with an empty back stack and swaps in
   `PlexSignInFragment`, which draws the spinner it already draws for that state.
4. `getResources()` returns and `ChoosingServer` renders the picker.

The spinner is free, and so is failure handling: a transport failure or an empty
server list already map onto `SignInError.Api` and `SignInError.NoServers`, which
reach `Failed` and the sign-in screen's existing error-and-retry rendering.

`reopenServerPicker()` needs only the stored account token, which is why it works
without a PIN at all. `PlexApi.session`'s setter deliberately leaves
`accountToken` alone when clearing the session, so the token outlives the session
it was gathered with.

### `ChoosingServer` learns which journey it is on

Back out of the picker today and `backPressed()` cancels the attempt and
publishes `Disconnected` — the Connect screen. That is right when the journey was
signing in, and wrong when it was a look from Settings, which should leave the
session alone and return to it.

**`ChoosingServer` gains `returnsToSettings: Boolean = false`, carried forward
into `ChoosingLibrary` by `chooseServer` the way `servers` already is.**
`backPressed()` publishes `Connected` when it is set and `Disconnected` when it
is not.

This state machine already solves this problem this way, which is the argument
for it. `ChoosingLibrary` carries `servers` for no purpose other than letting
back return to a populated picker, and its KDoc records that this was chosen over
a parallel field, citing #18. A second navigational fact in the state follows
that precedent instead of setting a new one, and it cannot desync: a state that
is not the picker does not carry the field.

A `ViewModel` flag was the cheaper option and is the #18 shape exactly — it would
have to be cleared in `open()`, `signIn()` and `signOut()`, and a missed clear
fails silently and much later.

Two distinct states, `ChoosingServer` and `ChangingServer`, would let the
compiler force every decision. Rejected on ceremony: four exhaustive `when`
blocks — `render`, `handlesBackPress`, `backPressed`, and the router — would each
gain a branch, and three of the four would say "identical to the other one".

A separate picker owned by the debug screen, bypassing `PlexSignInState`
entirely, would make back trivially correct. Rejected because it would be a third
implementation of choosing a server and could regress independently of the one
this feature exists to watch.

The default of `false` leaves every existing construction site unchanged, and
`PlexSignInState`'s own KDoc — "describes the steps of signing in" — is stretched
slightly by this, though `messageRes` is presentation and already lives there.

## Testing

- **`reopenServerPicker()`**: publishes `Working`, then
  `ChoosingServer(servers, returnsToSettings = true)`; `Failed` on a transport
  failure; `Failed` on an empty media-server list. MockWebServer, as
  `PlexSignInViewModelTest` already drives this flow.
- **`backPressed()` for both journeys**: `returnsToSettings = true` reaches
  `Connected` with the session intact; `false` still reaches `Disconnected`,
  which is existing behaviour and the regression most worth catching. And
  `ChoosingLibrary` → back → `ChoosingServer` **with the flag carried forward**,
  the direct analogue of the existing `servers` carry-forward tests.
- **The pop ordering**: choosing server from the debug screen lands on
  `PlexSignInFragment`. This test has teeth — substituting `popBackStack()` for
  `popBackStackImmediate()` makes it fail, because the router still sees a
  non-empty back stack.
- **The entry point**: the version line pushes `CarDebugFragment`, driven through
  `CarHostActivity` as `CarSettingsFragmentTest` drives settings.

The re-probe's in-place rendering is not covered: it is presentation over values
that are already tested, which is the argument the 2026-08-14 design made for the
dialog and which the promotion does not change.

## Sequencing

Depends on #119, which introduces `CarSettingsFragment`, `CarScreenViews` and the
router this builds on. It lands after, on its own branch, rather than being
folded into that PR — #119 is a rename and a split with no behaviour change, and
carrying a feature would change what it is.

## What this does not buy

- **No new diagnostics.** The address report is the same report, on a larger
  surface. Everything `2026-08-14-server-address-debug-design.md` lists under
  "what this does not buy" — no history, no per-address reachability, nothing for
  a shared server beyond what plex.tv advertises — is still true.
- **No second way to change servers, by design.** Reaching the picker from here
  commits exactly as the sign-in flow commits, because it *is* the sign-in flow's
  screen. More → Select Library remains the way a user switches libraries.
- **Nothing for a moving car.** `CarHostActivity` still carries no
  `distractionOptimized` meta-data, so the platform blocks this screen in motion.
  It is readable at a standstill only, which is what lets it stay dense.
