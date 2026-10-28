# A dead last track extends the queue before it stops

**Date:** 2026-10-27
**Status:** Approved
**Issue:** #272

## Context

\#178 made a dead track (404/410, or a file that cannot be parsed or decoded)
skip to the next item, and a run of them stop playback. See
`2026-10-27-skip-dead-tracks-design.md`. It left one case where a single dead
track still ends playback: the **last** item in the queue.

Continuous play ("Keep the music playing") and artist stations both extend the
queue from `onTracksChanged`, which is too early for a dead track:

- **Continuous play** starts from `onTracksChanged` when the current track has
  no next item. `onTracksChanged` fires for the dead track too, before its
  error, so continuous play does start. But its tracks arrive after the error,
  by which time the policy has found no next item and stopped, and nothing
  resumes when they land.
- **Stations** report the current track and read the queue back, also from
  `onTracksChanged`. The top-up normally keeps several items queued ahead, so
  this only happens when a top-up returned nothing and the rest of the queue is
  dead.

## Decision

### The policy gains EXTEND

`PlaybackFailurePolicy.decide` takes one more input, `canExtend`. For a track
failure, where `n` counts failures in a row including this one:

| Condition | Outcome |
| --- | --- |
| `n ≥ limit` | Stop |
| there is a next item | Skip |
| no next item, `canExtend` | **Extend** |
| no next item, otherwise | Stop |

**The dead last track counts toward the limit, and the limit-th failure stops
without fetching anything.** A dead disk with continuous play on stops after
the same few requests it does today. Neither extending regardless of the count,
nor resetting the count when the queue grows, would do that: either one lets a
dead server keep fetching and failing for as long as continuous play keeps
answering.

`canExtend` is worked out by the service, since it needs Android types: the
failing item belongs to a station, or continuous play is on. It deliberately
ignores `isInstantMixUsable()`. Continuous play has usually *just* started,
from the dead track's own `onTracksChanged`, so its ten-second throttle reads
"unusable" at the moment its tracks are on their way. The Extend's own call
then does nothing, and the resume picks up the tracks the first call fetched.
When continuous play really did run and find nothing, the flag waits for
tracks that never come, which looks exactly like a stop.

Under repeat-all there is always a next item, so Extend never occurs there.

### Resuming once the queue grows

On Extend, the handler starts the same extension `onTracksChanged` would and
sets an `awaitingExtension` flag, recording how many items follow the dead
track. `onTimelineChanged` passes control back to the handler. If the flag is
set, the player is still in error and the queue has grown past the dead track
-- more items after it in queue order, which shuffle does not change -- the
handler clears the flag and skips onto the next item in play order, exactly as
Skip does: `seekToNextMediaItem()` then `prepare()`. When shuffle placed every
new item earlier in play order there is no next item, and it seeks to the first
one added instead. Both extensions append at the end of the queue, and
continuous play's purge only removes items before the current one, so a purge
alone never counts as growth.

The flag clears wherever the failure count resets: on reaching `STATE_READY`,
and when a new queue replaces the old one. It also clears on every later
player error, so only a fresh Extend sets it again. Otherwise an extension
fetched before a stop at the limit, or before a 401, would land afterwards and
skip past that stop.

The resume also checks that the current item is still the dead track, and
clears the flag when it is not. ExoPlayer reports a new queue's timeline before
its `PLAYLIST_CHANGED` transition, and `setMediaItems` keeps the player's
error. A queue the driver picked while the flag was set would otherwise look
like the extension landing, and lose its first track.

Resuming from the timeline rather than from the extension's completion is the
point of this design. Continuous play appends through a `MediaBrowser`, so its
items reach the player after its completion callback has already run. A
callback that retried the skip would race that append and could find no next
item yet. The timeline is where both paths' items actually arrive, whichever
one supplied them.

An extension that comes back with nothing never grows the timeline, so the
player stays stopped on "Source error", as it did before. Two known causes of
that are accepted:

- A station top-up still in flight from the previous track makes
  `StationTopUp`'s single-read guard return nothing.
- Continuous play is already running.

Pressing Play while an extension is in flight retries the dead track. It fails
again and counts again, consistent with #178's rule that Play after a stop does
not reset the count.

### A dead station track is reported

The station top-up reports the current track before it reads the queue back,
because the server extends a station's window only once it hears which item is
playing. On a dead last station track, that means telling Plex a track it
cannot serve is playing, at position 0. Plex does not count that as played,
though it may appear briefly in the server's "now playing". It is the only
report that moves the server's window past that item.

The dead track can reach the top-up twice, from its own `onTracksChanged` and
from the Extend, in either order: on auto-advance the error arrives before
`onTracksChanged`, on Next after it. Each call reports before it reads, so Plex
hears the dead track twice, at position 0, which it does not count as played.
Skipping the second report was tried and dropped: the second call could then
read the queue back while the first report was still in flight, before the
server had extended the station, and find nothing.

### The extension is shared code

The extension in `onTracksChanged` (station top-up, then continuous play when
there is no next item) moves into one function in `BaseMediaService`. Both
`onTracksChanged` and the handler call it, so a dead last track gets exactly
what a track that loads would have.

### The Failures log records what actually happened

An Extend is logged as *stopped* when the failure happens, because nobody knows
yet whether the extension will bring anything back. If the handler resumes
playback, `FailedTrackLog.markNewestSkipped(ratingKey)` changes that entry to
*skipped*. It rewrites the stored list without bumping the added count, so the
badge does not count the failure twice. It does nothing when the newest entry
belongs to another track.

## Alternatives considered

- **Logging an Extend as *skipped* up front.** It is simpler, but the entry is
  wrong whenever the extension comes back empty, which is the case the Failures
  tab is there to explain.
- **A third log state, *extending*.** A change to the stored JSON's schema, for
  a case that is already rare.
- **Starting continuous play earlier**, with one item left rather than none, so
  a dead last track always has something after it. It avoids the error path,
  but it changes when continuous play fires for every queue, still fails on two
  dead tracks at the end, and does nothing for stations.
- **Re-reporting the last good station track** instead of the dead one. It
  avoids a false "now playing", but the server would not extend past an item it
  thinks is still current.

## Verification

Unit tests:

- `PlaybackFailurePolicyTest`: Extend appears only with no next item, under the
  limit, and with `canExtend`. At the limit it is Stop.
- `PlaybackFailureHandlerTest`:
  - Extend calls `extend` and logs *stopped*.
  - When the timeline grows while the player is in error, it skips, prepares
    and marks the entry *skipped*.
  - A timeline change without growth does nothing, and neither does a purge
    before the current item.
  - With shuffle, growth with no next item seeks to the first item added.
  - READY or a new queue clears the flag, so a later timeline change does not
    skip.
- `FailedTrackLogTest`: `markNewestSkipped` flips the newest matching entry,
  leaves the unseen count alone, and does nothing when the newest entry is
  another track's.

On the emulator, #178's "dead last" playlist on jorge:

| Continuous play | Expected |
| --- | --- |
| on | plays on into similar tracks; one *skipped* entry |
| off | stops on the dead track; one *stopped* entry |

Auto-advance into the dead last track (the track before it playing to its end)
was also verified on the emulator: the error arrives after the transition, with
the dead track current, and playback resumes.

The station path is not exercised end to end by any test. The handler tests use
a fake extend.
