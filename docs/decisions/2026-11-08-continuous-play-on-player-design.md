# Continuous play on the service's own Player

Issue #320, superseding #96.

## Problem

When the queue reaches its last track, `BaseMediaService.extendQueue` builds a
`MediaBrowser` against the service's own session and hands its future to
`MediaManager.continuousPlay`. Nothing ever releases that browser, so every
continuous-play run leaves a connected controller behind for the life of the
process.

The browser is not needed. The service already holds the `Player` it is
extending, on the main thread a controller would have to use anyway. Going
through a self-connected controller is an upstream habit from when this code
ran in a UI process, and it brought three costs beyond the leak:

- **A callback chain.** `similarTracks(cb)` → `dedupAgainstQueue(cb)` →
  `enqueue` → `finish(onComplete)`, duplicated for the random tier. Every path
  had to reach `finish`, or `continuousPlayIsRunning` stayed true and Instant
  Mix was dead for the process; two KDocs and a test class existed to hold that
  rule by hand.
- **A deadlock class.** The browser future is completed by the main looper,
  which is also where the mix response resumed, so any blocking `get()` on it
  parked the only thread that could complete it.
- **A detour through `onAddMediaItems`.** Mix tracks were appended through
  `browser.addMediaItems`, so they passed through the session callback's
  queue-resolution branch and survived only because an in-process controller
  keeps `localConfiguration` (#70).

## Decision

Continuous play becomes a suspend function, and the browser goes.

**`ContinuousPlay`** (in `service/`) is shaped like `StationTopUp`: it takes
its two tiers as suspend lambdas, owns the running flag and the ten-second
throttle, and returns the tracks to append. The flag is set with a
`compareAndSet` and cleared in a `finally`, so the "every path must clear it"
rule is held by the compiler — cancellation included.

**`BaseMediaService`** launches it on the main-thread scope the station top-up
already uses, and does the player work itself through one append path the
station top-up now shares: trim played tracks beyond the keep count, then
insert at the next index (or the end) and mirror both into `QueueRepository`.
The trim comes first because an insert at or before the current index — the
next index after repeat-all wraps — shifts it, and a trim measured from the
shifted index would remove the tracks just added.

**A run whose queue was replaced drops its result.** If the current track is
no longer the seed when a request returns, a new queue started meanwhile, and
tracks picked for the old one do not belong in it — the same check
`StationTopUp.appendable` makes for stations. The old code had no such check.

**`PlexMixRepository`** returns `List<MediaItem>` from two suspend functions.
Its `TracksCallback`, its own `Dispatchers.Main` scope, and the per-request
construction they forced all go. Failures still come back as an empty list:
continuous play is best effort.

**`MediaManager`** becomes a Kotlin `object` holding what is left — `scrobble`
and the two queue-timestamp helpers — translated literally.

## Consequences

- The queue is deduplicated against the player as it is *after* the request,
  which is when the old listener read it too; a track queued while the request
  was in flight is still not added twice.
- The purge now happens only when tracks are appended, as the station top-up's
  does. Before, it ran at the start of every run, so a run that found nothing
  new still trimmed played tracks. The keep count is unchanged; a run that adds
  nothing leaves the queue as it was.
- `extendQueue` still checks whether a run could start before launching one,
  since `onTracksChanged` fires several times per track; it asks
  `ContinuousPlay` rather than repeating the flag and throttle itself.
- Mix tracks no longer pass through `onAddMediaItems`, so that branch serves
  browse taps only.

## Alternatives considered

**Translate `MediaManager` literally (#96).** #96's case was that it deletes the
reason `PlexScrobbler` exists. By now `PlexScrobbler` is an ordered queue that
snapshots the sender of a final "stopped" report and drops reports queued
before a session switch; none of that depends on its caller's language, so a
translation would buy one deleted comment and leave the leak in place.

**Keep continuous play on `MediaManager`.** Smallest diff, but it would keep
an object whose jobs are unrelated — scrobbling and queue extension — and
would leave continuous play shaped differently from the station top-up it now
duplicates step for step.

**Release the browser instead of removing it.** This fixes the leak alone and
keeps the callback chain, the deadlock hazard and the `onAddMediaItems`
dependency, all of which exist only because of the browser.

**Return `Either` from the mix repository.** Every failure already means "no
tracks" to the only caller, so an `Either` would be folded straight back down
at the boundary. Arrow stays at the client edge, where the failure still has a
host to name.
