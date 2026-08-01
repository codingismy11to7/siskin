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
`MediaLibraryServiceCallback`, using `car_sign_in_required`. It fires for every
request including the root, which is why a signed-out car shows no tabs at all.
Nothing has failed here; the user simply has not connected yet. This stops
returning an error and returns a single **info row** instead: one `MediaItem`,
neither browsable nor playable. A list row cannot be covered by a mini player,
and a first-run user sees words rather than an empty screen.

The row uses both lines the browse list already gives every item, the same way
an album shows its artist underneath its title:

| Line | Content |
|---|---|
| Title | `car_sign_in_required`, reused verbatim |
| Subtitle | a new string naming the settings icon |

Splitting it this way keeps the primary line in the app's existing voice and
puts the instruction where a second line belongs, rather than making one
sentence carry both the explanation and the direction.

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

1. `PlexApi().session = null`
2. `BrowseTreeInvalidator.stopPlayback()` — the credentials that were streaming
   the current track are gone, so playback cannot honestly continue
3. `BrowseTreeInvalidator.invalidateRoot()` — the car re-requests the root and
   gets the info row
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

`CarSignInActivity.onLoginSuccess()` already calls `invalidateRoot()` then
`finish()`, and `PlexSignInFragment` already calls it on `Done`. Nothing to
build.

Recorded anyway, because Pocket Casts marks the equivalent line "We have to
close after signing in to meet Google UX requirements" and Siskin is heading for
automotive review. Knowing this behaviour is a **requirement** rather than a
convenience is what stops someone later deciding the screen should stay open to
show a confirmation. It is also right for both entry points: an activity reached
from a settings gear should not linger once its job is done.

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

**The info row is the one untested assumption.** If a car drops items that are
neither browsable nor playable, the fallback is to make it browsable with itself
as its only child.

**New strings cost lint.** Every user-facing string added raises
`MissingTranslation` by one per locale against the baseline in `CLAUDE.md`.

The `Disconnected` heading and the info row's **title** both reuse
`car_sign_in_required` and cost nothing. Three strings are new: the info row's
**subtitle**, the **Settings** heading and the **Sign out** button. Neither of
the latter two exists in `strings.xml` today.

The subtitle is the one worth defending, since it duplicates a message we
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

## Not done here

Only the manifest doorway has been built and verified. Still to implement: the
`Disconnected` state, the minimal settings screen and its sign-out, the
entry-point extra that keeps the rejected-credentials path reaching sign-in, and
the info row.

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
