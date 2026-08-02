# Shuffle follows the tap

**Date:** 2026-08-02
**Status:** Approved

## Context

Narrows the shuffle behaviour introduced by
[the car UI design sweep](2026-07-29-car-ui-design-sweep-design.md), which added
the two synthetic shuffle rows and the code that turns the player's shuffle on
when one is tapped.

## Why

Shuffle is sticky and nothing ever turns it off.

`enableShuffleIfShuffleRow` sets `player.shuffleModeEnabled = true` on a shuffle
row and has no counterpart. `Preferences.SHUFFLE_MODE` persists the flag and
`BaseMediaService` restores it into a fresh player at startup. So one tap on
"Shuffle this artist" leaves every later tap shuffled -- a track pressed inside
an album, a playlist opened from the top, a result chosen out of a search --
until the user notices and reaches for the toggle on Now Playing.

The emulator was sitting in exactly that state while this was being written.
`dumpsys media_session` reported the session's custom action as
`Disable shuffle mode`, which per `BaseSessionCallback.kt:277` is published only
when `shuffleModeEnabled` is true, and the last thing done to it was tapping
plain tracks inside an album (`Opening at 1 of 8 for 346887` in logcat). The tap
that most obviously means "play these in order" had left shuffle on.

Tapping a track is an explicit "play this one, then what follows it". Under
shuffle, what follows it is not what the list showed, and the position pressed
stops meaning anything -- which is the same reasoning that
[issue #29](https://github.com/codingismy11to7/siskin/issues/29) already forced
`openingPositionIn` to confront from the other side.

## The rule

**The tapped row decides shuffle mode.** A shuffle row turns it on; anything else
turns it off.

There is no third case, and that is a property of the tree rather than an
assumption. Tracks and the two shuffle rows are the only items in it with
`isPlayable` set; every other row -- the four tabs, artists, albums, playlists,
search results, the signed-out row, the library-picker rows -- is browsable and
not playable, so tapping one navigates and never reaches `onSetMediaItems` at
all.

That the message rows are *browsable* rather than inert is deliberate and worth
not undoing: an item with neither flag set is dropped from the list entirely,
measured on an AAOS API 33 emulator and recorded in both `MediaBrowserTree`'s
KDoc on `signedOutRow` and at `LibraryPickerRepository.kt:331`. They are
browsable purely so the car draws them.

The consequence for search is the useful one: an album or artist in search
results is browsable, so there is no "play this album" tap to get wrong. The only
playable search row is a track, which the rule already covers.

## What changes

`onSetMediaItems` already computes `isShuffleRow(firstItem)` into `shuffling` for
`openingPositionIn`, one line after calling `enableShuffleIfShuffleRow` for its
side effect. Those collapse into one: compute `shuffling` once and hand it to a
`setShuffleForTap(shuffling, player)` that assigns `player.shuffleModeEnabled`
outright.

It inherits the threading constraint documented on the function it replaces, and
that constraint is the reason this cannot move somewhere tidier: the player may
only be touched from the session's application thread, which is where the
override runs, while the queue future completes on whichever thread the coroutine
finished on.

Because the persisted preference is being kept (below), the existing
`onShuffleModeEnabledChanged` listener in `BaseMediaService` writes the new state
to `SharedPreferences` without any further wiring, and `BaseSessionCallback`
swaps the published command button on the same signal. Nothing else has to know.

## What deliberately does not change

### The add path keeps its enable-only call

`enableShuffleIfShuffleRow` stays exactly as it is, still called from
`onAddMediaItems`. Two functions rather than one shared helper, because they
genuinely differ and the names should say which is which.

**`onAddMediaItems` is not only a browse-tap path.** `MediaManager.continuousPlay`
appends instant-mix tracks through `browser.addMediaItems(...)`, which routes
through the same override. A total setter there would clear shuffle *mid-listen*
every time the queue topped itself up -- and continuous play fires precisely when
a queue is running low, which is the long shuffle-this-artist session it would
ruin. Enable-only is safe by construction: it can fire only on a shuffle row, and
continuous play never sends one. The asymmetry is the point, and forcing shuffle
off is the direction that carries the risk.

That reasoning is now a comment on the function. It is the only thing standing
between a future reader and "making it symmetric".

Leaving the add path alone also costs nothing measurable today. Every browse tap
observed on the API 33 AAOS emulator arrives at `onSetMediaItems` with
`startIndex = -1`; `onAddMediaItems` did not appear once across a logcat buffer
containing three separate track taps. The comment in the callback anticipating
"a car that adds rather than sets" describes a head unit nobody here has run.

### The persisted preference stays

Dropping `Preferences.SHUFFLE_MODE` was considered and rejected. It would make a
fresh player always start shuffle-off and delete a whole category of "shuffle is
mysteriously on", but the queue survives a restart and the mode it was playing in
should survive with it. A car that reboots at a gas station should resume the way
it was left.

### Queue resolution is untouched

Nothing here changes `resolveQueueForItem`. That keeps
[issue #70](https://github.com/codingismy11to7/siskin/issues/70) -- continuous
play possibly appending one track of a mix rather than the whole thing -- whole
and separately diagnosable, rather than half-disturbed by a change made for
another reason.

### Player-side shuffle stays player-side

[Issue #31](https://github.com/codingismy11to7/siskin/issues/31) -- app-side
shuffle versus the car's global toggle -- stays open.

Pre-shuffling the queue and leaving `shuffleModeEnabled` off would dissolve this
problem rather than fix it: no tap would ever need to clear anything, because no
tap would ever set anything. It was rejected here because it decides #31 as a
side effect of a bug fix, and because it reverses two choices the earlier specs
made on purpose -- `getArtistTracks` is left unshuffled so that turning the car's
toggle off mid-listen reveals the artist's *real* running order rather than one
this code invented. Pre-shuffling destroys that fallback: the toggle would have
nothing left to reveal.

Neither option dominates, which is what #31 already says. This change is
deliberately not the place to settle it.

## Testing

A new `MediaLibrarySessionCallbackShuffleTest`, beside the start-index one rather
than inside it -- same fixtures, different question. `session.player` is already a
Mockito mock in that file's setup, so the assertions need no new scaffolding.

Three cases:

- a track tap sets `shuffleModeEnabled = false`
- a shuffle-row tap sets it `true`
- `onAddMediaItems` with a plain track never sets it `false`

The third is the one worth keeping. It is the regression guard for continuous
play, and it fails loudly if someone later "simplifies" the two functions into
one.

No new user-facing strings, so no translations and no `MissingTranslation` risk.
