# Sign-in behind the car's settings gear

**Date:** 2026-08-01
**Status:** Approved

## Context

Siskin ran on a real head unit for the first time on 2026-08-01 — a Cadillac,
which is GM's "Google built-in" flavour of AAOS. The signed-out screen was the
first thing it showed, and it was wrong in a way the emulator cannot reproduce.

Signed out, the browse root returns `LibraryResult.ofError` carrying
`car_sign_in_required` plus a resolution label and a `PendingIntent`. The AOSP
car media app renders that as a centred message with a **Connect** button below
it. On the Cadillac the message rendered fine and the button landed *underneath
the mini player*, which that car displays permanently. It was reachable — the
button was occluded, not missing — but a first-run user has no reason to know
there is something under there.

The emulator cannot catch this. AOSP's car media app only shows the mini player
when something is playing, so a signed-out first launch has an empty lower half
and the button sits in clear space. This is the same class of finding as the
three in `2026-07-29-car-ui-design-sweep-design.md`: behaviour that lives inside
`com.android.car.media` and is invisible from our source.

The instinct to "redraw the screen" does not apply, because **we do not draw
that screen**. `CarSignInResolution` hands the car a message, a label and a
`PendingIntent`; the car's template decides where all three go. There is no
layout of ours to fix, and no amount of care on our side stops the next OEM
placing it somewhere else.

## Decision: the doorway is the settings gear, not the error resolution

Android's background activity-launch restrictions mean a media service cannot
start `CarSignInActivity` itself. Something in *the car's* UI has to fire a
`PendingIntent`. There are only three such surfaces:

1. the error resolution — what we use today, and what GM buries;
2. the session activity, already set in `BaseMediaService`;
3. an activity registered for `android.intent.action.APPLICATION_PREFERENCES`.

Pocket Casts' automotive module uses the third. It has **no launcher activity
at all** — same as Siskin — and instead declares an `AutomotiveSettingsActivity`
for `APPLICATION_PREFERENCES`, plus `automotive_app_desc.xml` containing
`<uses name="media"/>`. AAOS renders that as the gear in the top bar.

Verified on the emulator before committing to it. Adding the descriptor and the
intent-filter turns the previously inert gear into a live button that launches
`CarSignInActivity` full-screen. Two facts came out of that spike:

- **The gear lives in the top bar**, which is structurally out of reach of a
  mini player on any OEM. That is the property being bought here.
- **AAOS does not auto-launch the preferences activity.** The browse error still
  appeared first. Whatever made Pocket Casts appear to open its own UI
  unprompted on first launch is not `APPLICATION_PREFERENCES` doing it — most
  likely their error resolution points at that same activity, so tapping their
  sign-in button *is* what opened it.

The division of responsibility, which was assumed wrongly for part of this
investigation and is now measured:

| Surface | Drawn by | Can a mini player cover it? |
|---|---|---|
| Browse tabs, lists, error message, Connect button | the car | **yes — this is the bug** |
| The gear in the top bar | the car | no |
| Everything behind the gear | **us** | no |

## Decision: signed out is a state, not an error

`CarSignInResolution` has three call sites covering two different situations,
and they are not the same problem.

**No usable credentials** — the `onGetChildren` guard in
`MediaLibraryServiceCallback`, using `car_sign_in_required`. Nothing has
failed here; the user simply has not connected yet. This stops returning an
error, but what it returns instead took four rounds of emulator measurement
to land on, because the guard used to fire for *every* `parentId` including
the root, and the root turns out not to behave like an ordinary browse node
at all.

Four shapes were tried for the signed-out content at the browse root on an
AAOS API 33 emulator:

| Root child shape | Result |
|---|---|
| not browsable, not playable | dropped entirely -- no list child in the view hierarchy at all |
| playable only | dropped entirely |
| one browsable | becomes a single tab which the car auto-opens; message appears in `car_ui_toolbar_title` *and* as the row, and tapping recurses |
| two browsable | becomes two truncated tabs |

**The browse root is a tab bar, not a list.** Root children are rendered only
as tabs, and only when browsable -- there is no shape of root child that
renders as list content, so no amount of tuning the row itself was ever going
to fix this. Below the root, an ordinary list renders correctly: browsable
rows show title, subtitle and artwork as expected, which is not true at the
root at any child count.

The fix is a different node, not a different row: **signed out, the root
returns its normal four tabs** — Playlists, Artists, Albums, More, built by
`MediaBrowserTree.buildTree` exactly as when signed in, since that tree is
static and needs no credentials — and **every non-root `parentId` returns a
single info row** instead. The car auto-opens the first tab, so the user
lands on the row immediately, the toolbar reads "Siskin", and the app looks
like itself rather than broken.

| Line | Content |
|---|---|
| Title | `car_sign_in_required` — the situation |
| Subtitle (artist) | `car_sign_in_hint` — the action |

This collapses back into the single row the row started as, using both lines
the browse list already gives every item, the same way an album shows its
artist underneath its title. Two intermediate shapes were tried and rejected
along the way, both while this row was still being returned as a root child:
a single row split across a title and a subtitle (rejected because
`com.android.car.media` auto-drills into a root node whose only child is
itself browsable, rendering the message twice and recursing forever on tap),
and two separate rows to remove that auto-drill condition (rejected because,
per the table above, root children are tabs regardless of count, so two rows
became two truncated tabs instead of two list rows). Neither shape was wrong
in itself -- both are exactly how this row renders correctly today, one
level down. It was the location that was wrong. Zero new strings either way:
both already existed.

**Credentials rejected mid-use** — `classifyFailure` in the same file and the
equivalent in `LibraryPickerRepository`, using `car_sign_in_again`, reached when
a 401 surfaces as `ERROR_PERMISSION_DENIED`. This keeps the error result. It is
a genuine failure, it happens deep inside a node rather than at the root, and a
lone info row in the middle of an album list would read as a corrupt list rather
than an explanation. The occlusion costs less here too: the *message* renders
correctly on GM, only the button is buried, and a returning user already knows
the app and now has a gear that always works.

Keeping the second case also keeps `CarSignInResolution` and its KDoc alive.
That comment records that `ERROR_SESSION_AUTHENTICATION_EXPIRED` is one of only
two codes media3 replicates to a legacy `MediaBrowserCompat` client, and that
swapping it silently drops the button with no compile or test failure. Deleting
the type would delete a hazard note we would have to rediscover the hard way.

## Decision: an explicit `Disconnected` state

`PlexSignInState` is already a sealed state machine — `Working`,
`AwaitingApproval`, `ChoosingServer`, `ChoosingLibrary`, `Failed`, `Done` — and
the activity currently enters `Working` immediately, so opening it starts
creating a PIN whether or not the user asked for one. Reached from the gear
rather than from a deliberate "sign in" tap, that is wrong: the user may have
opened settings to look, not to authenticate.

A `Disconnected` state becomes the initial one:

```
Disconnected ──[Connect tapped]──▶ Working ──▶ AwaitingApproval ──▶ …
```

It renders the existing `tagline`, `car_sign_in_required` as the heading, and a
Connect `MaterialButton` in the idiom `retry_button` already establishes in
`fragment_plex_sign_in.xml`. Everything downstream is untouched — the QR, PIN,
server picker and library picker flow was exercised end to end on the real car
on 2026-08-01 and works.

This is deliberately the same words and the same button the car was drawing
badly. The content was never the problem; the placement was. Drawing it
ourselves is what fixes it.

## Decision: the gear is a settings screen, minimally

The gear is a *settings* affordance, so it has to answer for the signed-in case
too. Opening it with a live session and being shown a QR code would be absurd.

For now that screen is deliberately almost empty: a **Settings** heading and a
single **Sign out** button. Library switching stays in the More tab. Anything
more is a later change made with the car in front of us, not guessed at now.

Sign out is existing machinery plus a state change:

1. `PlexApi().session = null`, **and** `PlexApi().accountToken = null`. The
   session setter leaves the account token alone when clearing on its own —
   correct for the library-switch caller, `chooseLibrary`, where the account
   is not changing and the token's PIN grant is still good. Sign out means
   the account itself is being disowned, so it clears the token explicitly
   rather than relying on the setter it calls; otherwise the previous
   account's `X-Plex-Token` would silently ride along on the next
   `createPin()`/`getPin()` while `CredentialGate.isSignedIn()` already read
   false.
2. `BrowseTreeInvalidator.stopPlayback()` — the credentials that were streaming
   the current track are gone, so playback cannot honestly continue
3. `BrowseTreeInvalidator.invalidateTree()` — invalidates the root *and* each
   of the four tabs (see Consequences below for why the root alone stopped
   being enough), so the car re-requests all five and every tab answers with
   the info row
4. the screen returns to `Disconnected` rather than calling `finish()`

That last step is the opposite of the successful-sign-in case on purpose.
Someone who just signed out is plausibly there to sign in as someone else;
closing the screen would make them find the gear again to do it.

### The rejected-credentials collision

`CredentialGate.isSignedIn()` is `PlexApi().session != null`. In the
credentials-rejected path the session object still exists — it is simply no
longer accepted by the server. Choosing the screen off `CredentialGate` alone
would therefore show **Settings / Sign out** to a user whose actual problem is
that they need to re-authenticate.

So the activity distinguishes its two entry points.
`CarSignInResolution`'s `PendingIntent` carries an extra that forces the
sign-in flow whatever `CredentialGate` says; a launch from the gear carries no
such extra and picks its state normally. Without this, the one path that exists
specifically to recover a dead session is the one path that cannot.

## Decision: the activity finishes itself on success — already true

`CarSignInActivity.onLoginSuccess()` already calls `invalidateTree()` then
`finish()`, and `PlexSignInFragment` already calls it on `Done`. Nothing to
build.

Recorded anyway, because Pocket Casts marks the equivalent line "We have to
close after signing in to meet Google UX requirements" and Siskin is heading for
automotive review. Knowing this behaviour is a **requirement** rather than a
convenience is what stops someone later deciding the screen should stay open to
show a confirmation. It is also right for both entry points: an activity reached
from a settings gear should not linger once its job is done.

## Decision: a back button, since finishing is the only way out today

Found on a live emulator: everything above assumes the user reaches `Done` or
taps Sign out. Neither is guaranteed. `CarSignInActivity` is full-screen over
the car's media template, draws no toolbar, and — unlike the template it
covers — has no back affordance of its own. Opening Settings to look, or
tapping Connect and changing your mind partway through a PIN, leaves the
screen with nothing on it to press. Platform back
(`adb shell input keyevent 4`) still works, but a car's hardware back button
is an OEM feature the AAOS system bar makes no promise of, so nothing here can
depend on it existing. Pocket Casts — the app whose `APPLICATION_PREFERENCES`
pattern this whole design follows — draws its own button in a header above its
content for the same reason; that is the convention being followed here, not
a workaround invented for it.

The button lives in `activity_car_sign_in.xml`, not in `PlexSignInFragment`'s
layout: the fragment only ever renders one state at a time, but the missing
exit is a property of every state the activity hosts — the pickers and the PIN
screen included, not just Disconnected and Connected. An `ImageButton`
sibling of `car_sign_in_container`, pinned `start|top` over it, sized to
`plex_sign_in_choice_min_height` — the same head-unit tap target the pickers
already use — rather than a phone-sized icon button.

It calls `getOnBackPressedDispatcher().onBackPressed()`, not `finish()`
directly. With no `OnBackPressedCallback` registered anywhere in the activity
today, the dispatcher's default behaviour is to finish it, so the two read as
identical right now — but only one of them stays correct if a later change
adds a callback to pop one step of the sign-in flow instead of leaving
outright (back to the server list from a rejected choice, say). Routing
through the dispatcher means this button *is* the back button rather than
something that imitates it: hardware back, a gesture, and this tap all reach
the same place, so handling added for one automatically covers the other two
instead of the three slowly drifting apart.

Leaving mid-flow does not leak the PIN poll. `PlexSignInViewModel`'s `attempt`
job runs in `viewModelScope`, and the ViewModel is activity-scoped
(`ViewModelProvider(this)` in `onCreate`), so finishing the activity — by this
button or otherwise — clears its `ViewModelStore`, which cancels
`viewModelScope` and everything suspended inside `attempt`: the poll loop's
`delay`, and any `createPin()`/`getPin()` call in flight. This is the same
mechanism `chooseLibrary` and `signOut` already lean on to cancel a stale
attempt; leaving mid-flow was simply never a case that needed its own code.

Costs a fourth string, `car_sign_in_back_description`, against the three this
branch otherwise budgeted (see "New strings cost lint" below). Accepted: a
`contentDescription` is not optional on a button that is an icon and nothing
else, and none of the existing strings fit — this activity has never had
something on screen that needs describing rather than reading.

## Decision: back goes back a step, not straight out

This is the change the previous decision's own KDoc predicted: "any back
handling added later (e.g. popping one step of the sign-in flow instead of
leaving outright) applies to both the button and hardware/gesture back
automatically." That handling now exists.

The motivating case is a misclick: the server picker lists every media
server on the account, some of them shared and easy to tell apart only by
name, and picking the wrong one lands on *that* server's library picker with
no way back except abandoning the whole sign-in and re-approving a fresh PIN.
Back should undo the one step that was wrong, not the several that were not.

| From | Back goes to |
|---|---|
| `ChoosingLibrary` | `ChoosingServer`, the same server list, no message |
| `ChoosingServer` | `Disconnected` |
| `AwaitingApproval` | `Disconnected` (abandons the pin) |
| `Failed` | `Disconnected` |
| `Working` | `Disconnected` |
| `Disconnected` | leaves the activity — nothing to undo |
| `Connected` (settings) | leaves the activity — a leaf, not a step |

### `ChoosingLibrary` needed somewhere to go back to

`PlexSignInViewModel.chooseServer` already reads the server list out of the
current `ChoosingServer` state, once, immediately before overwriting it —
the ordering [#18](https://github.com/codingismy11to7/siskin/issues/18) (see
`docs/decisions/2026-07-29-sign-in-server-recovery-design.md`) depends on,
and the load-bearing comment above that read says explicitly that the state
is the list's *only* home and a parallel field would give it two owners.
Back into the library picker needs that same list, and the constraint rules
out the obvious fix of caching it in a second ViewModel field alongside the
one read.

So `ChoosingLibrary` now carries it: `ChoosingLibrary(sections, servers)`.
The single read inside `chooseServer` is unchanged — still one read, still
before the overwrite — and its result now does two jobs instead of one:
restoring the picker on rejection (unchanged, and still what the three #18
regression tests pin), and being threaded through into a successful
`ChoosingLibrary` so back has a list to hand back rather than an empty
screen. Nothing new owns the list; the state still does, in one more place.

### `messageRes` stays null coming back

`ChoosingServer.messageRes` already distinguished two ways of arriving:
`null` on the way in from a fresh sign-in, set when `chooseServer` bounces a
rejected pick back to the same screen. Arriving by pressing back is a third
path, and it reads like the first, not the second — the account token is
untouched, and nothing about either server said no to anything. The user
simply changed their mind. So the back transition constructs
`ChoosingServer(current.servers)` and lets `messageRes` default to `null`,
the same as the happy-path construction in `signIn()`. Making this
deliberate rather than incidental is also what keeps
`aServerWithNoMusicReturnsToThePickerInsteadOfFailing` and its two #18
siblings — which assert a message *is* set for an actual rejection — honest:
a future reader now has to notice both cases are handled, not assume they
collapsed into one.

### Every consumed transition into `Disconnected` cancels `attempt`

The PIN poll and the server probe/`getSections` call are both suspended
inside `attempt`, per the class KDoc on that field, and backing out of
`AwaitingApproval`, `ChoosingServer`, `Failed` or `Working` describes the
user abandoning whichever of those the current state represents. Leaving it
running risks it publishing a state over the `Disconnected` this call just
asked for — the same hazard `chooseLibrary`, `signOut` and
`open(forceSignIn = true)` already guard against by cancelling `attempt`
before changing state, and back reuses exactly that mechanism rather than
inventing a second one.

It also matters for `connect()`'s own two guards
(`attempt?.isActive == true` and `_state.value !is Disconnected`, see that
method's KDoc). Without cancelling first, a poll still technically `isActive`
could make the Connect button silently do nothing after backing out —
precisely the failure #24 already taught this file to guard against, just
reached from a new direction. Cancelling `attempt` and only then publishing
`Disconnected` is what leaves both guards open for the next Connect tap;
`backPressedFromChoosingServerReturnsToDisconnectedAndAllowsReconnecting` in
`PlexSignInViewModelTest` calls `connect()` a second time and asserts a
fresh `createPin()` to prove it.

### Where the decision lives

`PlexSignInViewModel` gets two new members, both `when`s over the sealed
`PlexSignInState` with no `else` branch, so a future case added to that
state fails to compile in both until it is given an explicit answer here:

- `handlesBackPress(state)` — true for the five states in the table above
  that consume the press, false for `Disconnected`, `Connected` and `Done`.
  `Done` is not in the table because it is not a state anyone is looking
  at — `PlexSignInFragment.render`'s `Done` branch calls
  `LoginHost.onLoginSuccess()` on the same tick it is published — so "leave"
  is as good an answer as any and "resume signing in" would be actively
  wrong.
- `backPressed()` — performs the transition and returns whether it consumed
  the press, so tests can assert the boolean directly instead of only the
  state that resulted.

`PlexSignInFragment` registers one `OnBackPressedCallback` in
`onCreateView`, constructed disabled — matching `Disconnected`, the
ViewModel's own initial state — and re-arms it from inside the same
`state.observe(viewLifecycleOwner) { ... }` block that already drives
`render()`, rather than a second observer: one state change, one place that
reacts to it. `handleOnBackPressed()` itself just calls
`viewModel.backPressed()`; the enabled flag is what stops it being invoked
at all for `Disconnected`/`Connected`/`Done`, letting the platform default
(finish) run instead of an enabled-but-inert callback swallowing the press.
This is the fragment-level version of the same split `connect()`'s own KDoc
already describes for the retry button: the primary defence is which states
even reach the handler, checked again inside the handler itself for anyone
who calls it directly.

`CarSignInActivityTest`'s existing back-button test
(`` `back button routes through the back dispatcher and finishes the
activity` ``) still passes unchanged: it only calls
`Robolectric.buildActivity(...).create()`, never `.start()`, so
`viewLifecycleOwner` never reaches `STARTED` and the observer that would
arm the callback never fires — the callback stays at its constructed
`false`, and the dispatcher's default still runs. That test's KDoc is
updated to say so explicitly rather than continue claiming no callback is
registered anywhere in the activity, which stopped being true the moment
the fragment added one.

Zero new strings, against the fourth this branch already spent on
`car_sign_in_back_description` above. `chooseServer`'s success path gained
one more "reached from a state that is not the picker" guard — needed only
because `ChoosingLibrary.servers` is now non-nullable — and it reuses
`SignInError.NoCandidate`'s existing message rather than adding a fifth
error case, the same way `chooseLibrary`'s three structurally identical
guards already do.

## Consequences

**`CarSignInActivity` becomes exported.** The platform cannot start it
otherwise. It reads nothing from the incoming intent and only ever displays a QR
code, so the exposure is that another app on the head unit can make a sign-in
screen appear. Accepted. The existing manifest comment claiming the activity "is
not exported and has no launcher intent-filter" must be **rewritten rather than
deleted** — its `taskAffinity` half is still load-bearing.

**Two launchers, one activity.** Until now only `CarSignInResolution`'s
`PendingIntent` started it, with `NEW_TASK|CLEAR_TASK`, and the distinct
`taskAffinity` is what keeps `CLEAR_TASK` scoped to that activity's own task.
The platform now starts it as well. `singleTop` plus the separate affinity
should hold, but tapping the gear while sign-in is already open is an explicit
case to check rather than assume.

**`auto_app_desc.xml` and `automotive_app_desc.xml` are different files for
different platforms** — Android Auto and AAOS respectively. PR #56 removes the
Android Auto opt-in and orphans the former. Both changes touch adjacent manifest
lines, so whichever lands second needs a rebase.

**The info row's shape was not one untested assumption but three, and the
emulator proved all three wrong in turn -- the third at a different level
than the first two.** A `MediaItem` that is neither browsable nor playable is
not merely inert on an AAOS API 33 emulator -- it is not rendered at all; see
"Verified on the emulator." The fallback anticipated for exactly that case --
make the row browsable with itself as its only child -- is what shipped
next, and was itself measured wrong: a single row browsable into itself is
precisely the shape `com.android.car.media` auto-drills into, doubling the
message and recursing forever on tap. Two rows was tried next to remove that
condition, and was *also* wrong, for a reason that had nothing to do with row
count: every one of these shapes was being tried as a **root** child, and the
root renders its children only as tabs, never as list content, regardless of
how many there are or how they're built. The fix that actually shipped is
not a fourth row shape but a different node -- stop returning this content at
the root at all. Signed out, the root now returns its normal four tabs, and
the single-row shape (the very first one tried, browsable rather than
inert) is what renders correctly for every *other* `parentId`, where an
ordinary list -- not a tab bar -- is what the car draws.

**Keeping four static tabs at the root means invalidation has to reach one
level deeper.** Before this decision, sign-in and sign-out changed the
*root's own* children -- the info row versus the four tabs -- so
`BrowseTreeInvalidator.invalidateRoot()`'s `notifyChildrenChanged(ROOT_ID,
...)` was, by itself, telling the car about a real difference. Once the root
always returns the same four tabs regardless of credentials, that call sees
byte-identical children on both transitions and gives the car nothing to act
on: the info row that moved one level down, inside each tab, is not the
root's problem to announce, and nothing else announced it either. Measured on
the emulator after sign-in: the browse tree kept showing the signed-out info
row inside the tab until the user backed out and re-entered it by hand.
`BrowseTreeInvalidator.invalidateTree()` is the fix -- `invalidateRoot()` plus
`invalidateNode()` for each of the four tab ids, called from both
`CarSignInActivity.onLoginSuccess()` and `onSignedOut()`. This is the
recurring cost of the tab-bar decision above, not a one-off oversight: any
future change to what a tab shows based on sign-in state will need the same
one-level-deeper invalidation, because the root's four children are now fixed
and can never carry that signal on their own.

**New strings cost lint.** Every user-facing string added raises
`MissingTranslation` by one per locale against the baseline in `CLAUDE.md`.

The `Disconnected` heading and the info row's **title** both reuse
`car_sign_in_required` and cost nothing. Three strings are new: `car_sign_in_hint`
(the info row's subtitle), the **Settings** heading and the **Sign out**
button. Neither of the latter two exists in `strings.xml` today.

`car_sign_in_hint` is the one worth defending, since it duplicates a message we
already have. Pointing at the settings icon means naming it, and
`car_sign_in_required` ("Your music is on Plex. Connect to start listening.")
does not. A row that says only "Your music is on Plex" leaves the user exactly
as stuck as the buried button did.

## Alternatives considered

**Return an empty root and let the car draw its own empty state.** Least code,
and the purest reading of the Pocket Casts pattern. Rejected because the wording
then belongs to the OEM, which is the exact class of problem this document
exists to remove.

**Keep the error result and rely on the gear alone.** Smallest possible change,
and defensible — the message renders everywhere and only the button is buried.
Rejected because it leaves first-run discovery depending on a gear icon the user
has no reason to associate with signing in.

**Move library switching behind the gear too**, shrinking or removing the More
tab. Superficially attractive because it is what Pocket Casts' settings screen
does, and rejected outright rather than deferred: choosing which library to
browse *is* browsing. It belongs in the main UI beside the other browse
choices, not behind a settings icon. Pocket Casts' screen is an account screen,
which is a different thing wearing the same gear.

**A launcher activity.** Would give us a full app UI on first launch. Rejected:
Siskin is a media source, Pocket Casts does not do it either, and it would
contradict the app's whole shape for the sake of one screen.

## Verified on the emulator

- `cmd package query-activities -a android.intent.action.APPLICATION_PREFERENCES`
  resolves `CarSignInActivity` once the intent-filter and descriptor are present.
- The gear changes from an inert outline to a live filled button.
- Tapping it puts `CarSignInActivity` in `topResumedActivity` and shows the QR
  and PIN full-screen.
- AAOS does **not** auto-launch it; the browse error appears first.

A second pass, once the info row itself existed, measured the assumption
flagged above as untested.

- Signed out, with the row built neither browsable nor playable, the browse
  screen rendered with no row at all -- no tabs (correct; the root returns a
  single item) and no visible list item either.
- A `uiautomator` dump of `com.android.car.media` showed
  `browse_content_area` with no list child whatsoever. The only thing in that
  region was `browse_mini_item_bar` / `browse_mini_control_bar`, the car's
  own empty mini player -- easy to mistake for the row at a glance, which is
  how this was first misread as present.
- No `MediaItem` text appeared anywhere in the hierarchy.
- Making the row browsable, with itself as its only child (the fallback
  named above), fixed it: the title and subtitle render, and drilling in
  shows the same message again rather than an empty screen or a crash.

A third pass on the same AAOS API 33 emulator found the single browsable row
itself was the wrong shape.

- With one row present, the message appeared twice: once as
  `car_ui_toolbar_title`, where "Siskin" belongs, and once more as the row
  itself underneath it.
- Tapping the row did not open a sign-in flow or repeat the message once --
  it recursed, re-entering the same single-child node on every tap.
- A view-hierarchy dump showed two stacked `browse_list` levels for what was
  meant to be one screen, confirming `com.android.car.media` auto-drills into
  any node whose only child is itself browsable.
- Replacing the single row with two -- `car_sign_in_required` and
  `car_sign_in_hint`, split across two `MediaItem`s instead of one item's
  title and subtitle -- removed the condition: with more than one child,
  there is nothing for the car to auto-drill into, and the toolbar keeps
  reading "Siskin".

A fourth pass, checking whether the two-row fix actually held up, found the
premise underneath all three previous passes was itself wrong: the root
does not render list content at all, at any child count.

| Root child shape | Result |
|---|---|
| not browsable, not playable | dropped entirely -- no list child in the view hierarchy at all |
| playable only | dropped entirely |
| one browsable | becomes a single tab which the car auto-opens; message appears in `car_ui_toolbar_title` *and* as the row, and tapping recurses |
| two browsable | becomes two truncated tabs |

- The two-row shape from the third pass, still being returned as a root
  child, rendered as two truncated tabs rather than two list rows --
  confirming the root does not draw a list at any child count, not just at
  one.
- Below the root, an ordinary list rendered correctly: a browsable row
  showed its title, subtitle and artwork exactly as designed, on the very
  first node one level down from the root.
- The fix: signed out, the root returns its normal four tabs -- Playlists,
  Artists, Albums, More -- built by the same static `buildTree` a signed-in
  car uses, and every non-root `parentId` returns the single-row shape from
  the second pass. The car auto-opens the first tab, landing the user on
  the row immediately with the toolbar correctly reading "Siskin".

## Not done here

Only the manifest doorway has been built and verified. Still to implement: the
`Disconnected` state, the minimal settings screen and its sign-out, the
entry-point extra that keeps the rejected-credentials path reaching sign-in, and
the info rows.

Two things on that list are already done and were nearly rebuilt: `finish()` on
`Done` (see above), and the Connect button's label — `car_sign_in_action` is
already the string `"Connect"`.

The `car_sign_in_again` path is unchanged by design rather than unfinished.

Explicitly deferred, not forgotten: real settings behind the gear — how Siskin
plays, not what it plays. Whether to transcode lossless, and bringing back the
ReplayGain toggle the fork dropped.

Library switching is **not** one of them. It belongs to browsing, it lives in
the More tab in the main UI, and it stays there. The gear is for choices about
how Siskin behaves, not for navigating the library.
