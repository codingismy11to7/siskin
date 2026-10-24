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
| A track becomes current | `playing` or `paused`, whichever the player is doing |
| Playback starts or resumes | `playing` |
| Every 10 s while playing | `playing` at the current position |
| Pause — silent while `STATE_READY`, including on becoming ready after a stall | `paused` at the current position |
| End of queue | `stopped` at the final position |
| Leaving a track: an automatic transition, a seek to another item or back to the start of the same one, or the queue being replaced | `stopped` for the outgoing item at `oldPosition.positionMs` |

A track that becomes current while paused and still loading is reported once
it is ready, as a pause. A station track is reported by the station top-up
instead, in the state the reporter gives it, because the top-up has to know
the report landed before it reads the play queue back.

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

So `paused` is sent only when the player is silent in `STATE_READY`. Any time
`isPlaying` is false the heartbeat stops ticking, which means a stall is simply
quiet. Plex has a `buffering` state, but nothing on the dashboard depends on
it, and reporting every stall on a car's mobile connection would be noise.

A pause pressed *during* a stall changes only `playWhenReady`; `isPlaying` was
already false and stays false when the stall ends. So the move to
`STATE_READY` is checked too, or that pause would never be reported.

While a track is still loading there is no `STATE_READY` to go on, and
`playWhenReady` is the only signal of whether it is about to play.

## One listener owns every report

`PlaybackReporter` is a `Player.Listener` of its own, registered beside
`BaseMediaService`'s, and sends every timeline report. That listener already
holds ReplayGain, the station top-up, continuous play and shuffle fix-ups, and
a timer threaded through it would be the hardest part to test.

An earlier cut left the reports at a track's edges in `BaseMediaService` and
put only the heartbeat, pause and skip in the new class. Review found that the
split was where the gaps were: the service reported every new track as
`playing` without asking whether the player was, and the two listeners divided
the discontinuity reasons between them so that a replaced queue fell through
both. With one owner, the state comes from the player in one place.

The timer is a `Handler` on the player's application looper, started on
`isPlaying` true and removed on false and on release, which also detaches the
listener. Reading `currentPosition` has to happen on the player's thread, and
the `Handler` puts the read there with no dispatching. A coroutine ticker would
work too, but it would need a scope tied to the player's lifetime, which
`PlexScrobbler` deliberately does not have, and it would only end up posting
back to the player's thread anyway.

## Reports are sent in order

The server keeps whichever state it heard last. With a report every 10 s, a
pause routinely follows a heartbeat by moments, and two requests in flight
together can land in either order — leaving a paused car showing as playing.
`PlexScrobbler` sends from one queue, drained by a single coroutine, so each
report waits for the one before it. A `Mutex` would not do: coroutines started
separately on `Dispatchers.IO` can reach it in any order.

The same coroutine caches its `SearchClient`, rebuilding it when the server
address or token changes. Building a Retrofit per report was tolerable at a
few reports a track and is not at one every 10 s.

## Rejected: a `stopped` report on service destroy

When the car turns off, the last report is a heartbeat, and Plex ages the
session out on its own. A network call started from `onDestroy` races process
death, so it would land sometimes and never reliably. Nothing visible depends
on it.

## Testing

Robolectric tests on the reporter class, with a fake `Player` and a captured
report function, advancing the main looper:

- `playing` at once and every 10 s while playing, none while paused or buffering
- `paused` on a pause and on becoming ready after a stall, nothing on a stall
- a new track reported in the state the player is in
- `stopped` with the outgoing item and its last position for each way of
  leaving a track, and at the end of the queue
- no reports after release

`PlexScrobbler` is tested against a `MockWebServer` whose first response is
held back, to show that the second report waits for it.
