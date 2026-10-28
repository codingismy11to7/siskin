# A dead track is skipped; a run of them stops playback and says so

**Date:** 2026-10-27
**Status:** Approved
**Issue:** #178

## Context

A Playlist Mix stopped partway through, in the car, and stayed stopped. The
queue held a track whose file had moved on the server. Plex still listed the
track and served its metadata, but answered **404** for the file. From the
driver's seat it looked like the app had crashed.

`onPlayerError` in `BaseMediaService` re-prepares in place for three network
codes (#682: a WiFi↔mobile switch is a source error, and skipping would be
wrong there). It returns without doing anything for every other code. A 404
arrives as `ERROR_CODE_IO_BAD_HTTP_STATUS`, so the player goes idle on the dead
track. Pressing Play retries that same track, fails again, and stays stopped.

How the 404s appeared is part of the design. Jorge has `autoEmptyTrash=true`
and no scheduled or filesystem-triggered scans. A moved directory therefore
leaves its tracks in the library, each one answering 404, until the next manual
scan purges them. The window is real and temporary. Re-creating it (rename a
directory over ssh, do not scan) is how this was tested; see **Verification**.

### What the car shows

The issue's first open question was whether `com.android.car.media` shows a
playback error at all. On the API 33 emulator it does. Decompiling
`AAECarMediaApp.apk` gives the rules:

- The text comes from the session's `PlaybackState`: our error message if
  there is one, else a stock string for a standard error code, else a generic
  default whenever the state is `STATE_ERROR`.
- An error from the session that is playing is a **toast** of about 3.5 s.
  It becomes a **dialog** only when the state's extras carry
  `ERROR_RESOLUTION_ACTION_INTENT` *and* the car is not UX-restricted, i.e.
  only when parked.
- The full-screen error (`Mode.FATAL_ERROR`) comes only from the browse side,
  and only when the browse tree has no children. Playback cannot reach it.

So while driving, a toast is the most any playback error can get. What Siskin
controls is the wording: media3's default is "Source error". This is the AOSP
car media app. GM's head unit runs its own media UI, which may differ.

### Why the wording stays "Source error" for now

Replacing media3's message turned out to be harder than it looked, so it was
split out to #270. In media3 1.11:

- **`MediaSession.setPlaybackException(…)`** replaces the message, but while it
  is set the session's player commands are cut down to `GET_*` and `RELEASE`
  (`MediaSessionImpl.createPlayerCommandsForCustomErrorState`). The car gets a
  `PlaybackState` with no actions at all: no Play, no Next, and so nothing that
  could ever clear the error.
- **`player.stop()` plus `sendError(SessionError)`** fails too. `stop()` keeps
  the player's own error, since only `prepare()` clears it, and media3 ignores a
  non-fatal session error whenever the player has an error of its own
  (`MediaSessionLegacyStub.createPlaybackStateCompat`).

What does work is a `ForwardingPlayer` that substitutes the player's error. That
is a player wrapper with an ordering rule attached, and #270 asks whether a 3.5 s
toast is worth it.

## Decision

`PlaybackFailurePolicy`, a plain Kotlin object with no Android types, decides
what each failure means. `onPlayerError` gathers its inputs and carries out the
result.

### The decision table

First matching row wins:

| Error | Outcome |
|---|---|
| `IO_NETWORK_CONNECTION_FAILED`, `IO_NETWORK_CONNECTION_TIMEOUT`, `IO_UNSPECIFIED` | **Reprepare**: the #682 path, unchanged and not counted |
| `IO_BAD_HTTP_STATUS` with 401 or 403 | **Stop**: never skipped, not counted, not logged |
| `IO_BAD_HTTP_STATUS` with 404 or 410 | track failure |
| `PARSING_CONTAINER_MALFORMED`, `PARSING_CONTAINER_UNSUPPORTED` | track failure |
| `DECODING_*` | track failure |
| `DECODER_INIT_FAILED`, `DECODER_QUERY_FAILED` | **Stop**: the head unit's codecs, not the file; not counted, not logged |
| anything else | **Stop**: today's behaviour, not counted, not logged |

A track failure, where `n` counts failures in a row including this one:

- `n < limit` and there is a next item: **Skip**
- otherwise: **Stop**, logged

`limit` is a setting, 3 by default; see **The Failures tab**.

The count resets when the player reaches `STATE_READY`. A dead file fails
before READY, and a track that buffers successfully always reaches it. It also
resets when a new queue replaces the old one (a transition with reason
`PLAYLIST_CHANGED`), so a dead first track in a freshly chosen playlist is
skipped even right after a stop. Our own skips (reason `SEEK`) and station
top-ups do not count as a new queue. Pressing Play or Next after a stop does
not reset the count, so a server that is still dead stops again on the next
failure rather than skipping two more first.

READY is a proxy, and it can be fooled. A file that is corrupt partway
through, or one whose start the precache already holds, reaches READY before
it fails, so it resets the count and is skipped as a first failure. That is
accepted. The precache covers only the next couple of tracks, so after those an
unreachable file fails before READY and the cap engages. A track that played
partway did play something, so skipping it is not racing through the queue.
Waiting for some seconds of real playback before resetting would close the gap,
at the price of a timer and another number to tune.

A dead *last* track stops even when continuous play or a station would have
carried on, because both extend the queue only from a track that loads. That
was true before this change too, and it is #272.

`hasNext` is `player.hasNextMediaItem()`, which follows the repeat mode. Under
repeat-all there is always a next item, and the cap is what stops a queue made
entirely of dead tracks from cycling.

401/403 is its own row, though it ends exactly like the catch-all, so that it
can never become a track failure. Skipping on an expired credential would burn
the queue while sign-in is what is needed.

### Carrying out an outcome

- **Reprepare**: the existing re-probe and 5 s-throttled `prepare()`.
- **Skip**: log the track, then `seekToNextMediaItem()` and `prepare()`.
  `playWhenReady` is still set, so playback continues.
- **Stop**: nothing beyond logging a track failure. The player stays in its
  error state, the car shows "Source error", and Play retries the same track,
  as it does today.

An isolated dead track therefore shows nothing at all: it is skipped and the
next track plays. A stop looks exactly as it does today, but it now comes only
after `limit` failures in a row, at the end of the queue, or for a cause that
skipping cannot help.

`PlexScrobbler` reports only actual playback, so a skipped track is never
reported to Plex as played.

### The failed-track log

`FailedTrackLog` keeps the last **20** track failures, newest first, as one
JSON string under a single preferences key, written with `apply()`. An entry
holds the `ratingKey`, title, artist, album, the reason (`HTTP 404` or the
error code name), the outcome (skipped or stopped), the time, and the server's
`machineIdentifier`. Only track failures are logged, including the one a stop
landed on.

### The Failures tab

A new debug-screen tab. At the top is the **failure limit**, a choice of
**2 / 3 / 5 / Never** with 3 as the default, stored in preferences. Below it are
the log's entries, with a Clear failures button at the end of their heading, so
it stays put as entries arrive. It is disabled, not hidden, while the list is
empty. The strings are `debug_`-prefixed and
untranslated.

The tab's rail item carries a number badge: the failures added since the tab
was last open. It is a count of failures ever added, compared with that count
at the last look, rather than a comparison of timestamps. A head unit can boot
with its clock behind, and a failure stamped earlier than one already seen
would otherwise never show as new. Opening the tab clears the badge, and so
does Clear. The badge and the list update while the screen is open, since
playback carries on behind it.

The limit lives here rather than in Settings because it is a diagnostic knob,
not something a driver should need. A choice row is used rather than a number
field because a keyboard on the head unit is awkward, and may be blocked while
driving.

**Never means never.** It turns off the protection the cap exists for: a dead
disk skips through the whole queue, and under repeat-all it loops through dead
tracks forever, sending a request on every pass. A dead last track still stops
when repeat is off, because there is nothing to skip to. A "stop after one full
pass" guard was considered and left out: it is code whose only purpose is to
protect someone who chose Never on a debug screen.

## Alternatives considered

- **Logic inline in `onPlayerError`.** The same behaviour, testable only
  through a player. The handler already carries the #682 hazard, and a table in
  its own object keeps that split readable.
- **A `LoadErrorHandlingPolicy`.** It can retry a load within one item, but it
  cannot advance the queue, so a listener would still be needed.
- **Probing another track on the first 404** to tell a dead file from a dead
  server. It would be right one track sooner and costs a request on every
  failure. The counter is already right within two tracks, and is never wrong
  about a server that is up.
- **A toast for every skip.** Rejected: an isolated dead track should go
  unnoticed while driving. The Failures tab is where to find out afterwards.
- **5xx as a track failure.** Rejected: a server erroring is not a fact about
  the track, and a flaky 503 would burn tracks that a retry would have played.
- **Our own messages, and a sign-in dialog for 401/403.** Deferred to #270; see
  **Why the wording stays "Source error" for now**.

## Not decided here

- **`IO_UNSPECIFIED` (2000)** stays recoverable. It is a catch-all, and nothing
  in #178 involved it. `onPlayerError` already logs the error with its full
  cause chain, so the next real occurrence will show what lands there.
- **The default of 3** is a judgement: isolated rot never reaches it, and a
  dead disk reaches it within about three requests. The setting exists so that
  real use can correct it.

## Verification

Unit tests:

- `PlaybackFailurePolicyTest` (plain JVM): every row of the table, the counter
  either side of each limit, Never skipping indefinitely while a next item
  exists, a failure with no next item, and that 401/403 and the network codes
  are never counted.
- The executor, against a mocked `Player`: a 404 cause leads to
  `seekToNextMediaItem()` and `prepare()`; a stop calls neither; reaching READY
  resets the count; a new queue resets the count.
- `FailedTrackLogTest` (Robolectric): round trip, ordering, the cap of 20, and
  Clear; the limit setting reads back 3 when unset.

On the emulator, against four playlists of the managed account on jorge, with
the dead tracks created by a renamed directory:

| Playlist | Expected |
|---|---|
| dead mid-queue | plays through; one *skipped* entry |
| dead run (5 dead) | stops on the third dead track; Next moves on |
| dead first | skips to the second track |
| dead last | stops on the dead track |
