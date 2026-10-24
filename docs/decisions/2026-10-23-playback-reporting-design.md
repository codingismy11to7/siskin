# Siskin reports playback to Plex while a track plays, not only at its edges

**Date:** 2026-10-23
**Status:** Approved
**Issue:** #204

Builds on #205 (artist radio), whose station tracks carry `playQueueItemID`
and `containerKey` on their timeline reports.

## Context

Siskin already scrobbles. A track that ends into the next is reported
`stopped` at its final position, and Plex — which decides on its own whether a
play counts, by comparing that position against the duration — marks it
played. Play counts and "last played" work.

Everything between a track's start and its end is silent:

- **No heartbeat.** One `playing` report when a track starts or resumes, then
  nothing until it ends. Plex's own clients report every ~10 s. The server's
  Now Playing shows Siskin frozen at the start position, and a long track can
  go quiet long enough to drop off the dashboard.
- **No pause.** Pausing stamps the local queue and tells the server nothing,
  so a paused car reads as still playing.
- **No report for a skipped track.** Only an automatic transition reports the
  outgoing track. Skipping at 3:50 of a 4:00 track sends no `stopped`, so Plex
  never sees a position it could count. Whether such a play counts is Plex's
  call; it can only make it if it is told.

The goal is both: a dashboard that follows the car, and play counts that
reflect what was heard.

## What is reported

| Event | Report |
|---|---|
| Track starts, playback resumes | `playing` (unchanged) |
| Every 10 s while playing | `playing` at the current position |
| Pause — stops playing while `STATE_READY` | `paused` at the current position |
| Automatic transition, end of queue | `stopped` at the final position (unchanged) |
| A seek that lands on a different item | `stopped` for the outgoing item at `oldPosition.positionMs` |

Every report still goes through `MediaManager.scrobble` → `PlexScrobbler.report`,
fire-and-forget, and unconditionally — #206 removed the scrobbling preference,
a Tempo leftover nothing could switch off. Station tracks
need nothing extra: `scrobble` already attaches the station tag through
`stationTagOf`, so a heartbeat for a station track carries the play-queue
fields the server uses to keep growing the station.

`scrobble`'s `boolean submission` becomes the state itself. Two states fit a
boolean; three do not, and `SearchClient` already defines `STATE_PAUSED`.

## Pause is not buffering

`onIsPlayingChanged(false)` fires for a pause, a buffering stall and the end of
the queue alike. Only the first is a pause, and the playback state is what
tells them apart: a stall is `STATE_BUFFERING` and the end of the queue is
`STATE_ENDED`, while a pause leaves the player `STATE_READY`.

`playWhenReady` looks like the obvious test and is not. A transient
audio-focus loss — a navigation prompt, a phone call — can stop the audio
through a playback-suppression reason while `playWhenReady` stays true. The
car has gone quiet either way, and the dashboard should say so.

So `paused` is sent only when playback stops in `STATE_READY`. Any time `isPlaying` is
false the heartbeat stops ticking, which means a stall is simply quiet. Plex
has a `buffering` state, but nothing on the dashboard depends on it, and
reporting every stall on a car's mobile connection would be noise.

## Where the heartbeat lives

A small Kotlin class owns the timer and the decisions above, and is a
`Player.Listener` of its own, registered beside `BaseMediaService`'s. That
listener already holds ReplayGain, the station top-up, continuous play and
shuffle fix-ups, and a timer threaded through it would be the hardest part to
test. The reports it already sends at a track's edges stay where they are.

The timer is a main-looper `Handler`, started on `isPlaying` true and removed
on false and on player release. Reading `currentPosition` has to happen on the
player's thread, and that thread is the main looper; a `Handler` puts the read
there with no dispatching. A coroutine ticker would work too, but it would need
a scope tied to the player's lifetime, which `PlexScrobbler` deliberately does
not have, and it would only end up posting back to the main thread anyway.

## Rejected: a `stopped` report on service destroy

When the car turns off, the last report is a heartbeat, and Plex ages the
session out on its own. A network call started from `onDestroy` races process
death, so it would land sometimes and never reliably. Nothing visible depends
on it.

## Testing

Robolectric tests on the reporter class, with a fake `Player` and a captured
report function, advancing the main looper:

- a `playing` report every 10 s while playing, none while paused or buffering
- `paused` on a pause, nothing on a stall or at `STATE_ENDED`
- `stopped` with the outgoing item and its last position on a skip
- no reports after release
