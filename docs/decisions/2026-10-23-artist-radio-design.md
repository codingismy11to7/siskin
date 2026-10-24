# An artist's page can start that artist's Plex radio station

**Date:** 2026-10-23
**Status:** Approved
**Issue:** #197

Builds on #202 (the Artist Mix setting and the artist-page invalidation it
added). Related: #204, which adds periodic timeline reports and has to carry the
play-queue fields this introduces.

## Context

A tester asked for Plexamp's artist radio: start from an artist, hear that
artist and things like it. Siskin's Artist Mix is the artist's own catalogue,
shuffled. Sonic similarity exists only in continuous play, seeded from the
current track and only at the end of a queue.

#197 framed the gap as a design question — `/nearest` is track-seeded, so an
artist radio would have to invent a seed strategy. That premise was wrong. The
server already has artist stations; Siskin only has to ask for one.

## What the server does

Measured against a live PMS library (two music sections: one fully
sonic-analysed, 12,481 tracks; one with no analysis at all, 3,681 tracks):

- **An artist's station is on its metadata.** `GET /library/metadata/{artist}?includeStations=1`
  returns `Stations: { size, Metadata: [ { key, title: "<Artist> Radio",
  radio: "1", ... } ] }`. The key is
  `/library/metadata/{artist}/station/{uuid}?type=10`. JSON carries it as an
  object, not an array, unlike most child containers.
- **The key is minted per request.** Two consecutive fetches returned
  different UUIDs, so a key cannot be stored in a browse row; it is fetched at
  tap time.
- **A station is started as a play queue.**
  `POST /playQueues?type=audio&uri=server://{machine}/com.plexapp.plugins.library{key}`
  answered in ~40 ms with 5 tracks from 5 artists, the seed among them. The
  tracks are full track metadata with `Media`/`Part`, so the existing mapper
  turns them into playable items unchanged.
- **The queue is open-ended and grows from the selected item.** No
  `playQueueTotalCount`. The first `GET /playQueues/{id}` topped it up from 5 to
  10; further reads added nothing. A `:/timeline` report naming the last item
  (`playQueueItemID` + `containerKey`) moved the selection there and the queue
  grew to 15 — the server keeps roughly five ahead of whatever it is told is
  playing. 15 tracks spanned 14 artists.
- **Each station is a fresh draw.** A second station for the same artist shared
  none of the first one's five tracks.
- **It works without sonic analysis.** An artist in the unanalysed section
  still had a station, and it returned related artists rather than an empty
  queue. Unlike `/nearest`, there is no "library was never analysed" case to
  absorb.

## Decisions

### Radio is a setting, off by default

"Show Artist Radio", directly under "Show Artist Mix". Off because every row on
an artist's page pushes the albums further down, and radio is an addition
rather than something every install has had. Toggling it invalidates the artist
pages the car has loaded, exactly as the Mix switch does.

### The row

"Artist Radio", after Artist Mix when both are on, with a radio icon; playable,
not browsable, id `[radioArtistID]<ratingKey>`. Like every Mix row, the id
carries only the artist, because the car rebuilds a tapped row from its id
alone.

No server probe gates the row: every artist sampled, analysed or not, had a
station. A station that cannot be started fails the tap like any Mix tap.

### The server owns the queue

Three approaches were considered:

- **Server-managed play queue** (chosen). Start the station's play queue, tell
  the server what is playing, read back what it adds.
- **Redraw on demand.** Draw a fresh station whenever the local queue runs low
  and append it. Stateless and restart-proof, but the seed artist recurs at the
  head of every batch and a small library can repeat over a long drive.
- **One batch, then hand over** to continuous play. Ten songs is not radio.

Redraw delivers most of the behaviour for much less state. The server-managed
queue was chosen anyway: it is what the station actually is, it is what Plexamp
does, and the server avoids repeats within a station. The state it needs is the
cost, and the restart decision below is what pays it.

### Starting a station

`mixTracksFor` gains a `[radioArtistID]` branch calling
`PlexRadioRepository.startArtistStation`, which fetches the station key, starts
the play queue against the session's `machineIdentifier` (fetching `/identity`
when a session predates storing one), and maps the tracks with three new
extras: the play-queue id, the track's play-queue item id, and the seed artist.

**Radio is not shuffled.** Every other Mix is reordered by
`resolveQueueForItem`; a station must play in server order, because the server
advances its window along that order.

**The station's state lives on its tracks**, not in preferences. Whatever is
playing says whether a station is active and which one: reporting reads the
queue id off the item it reports, the top-up reads it off the current item, and
starting any other queue replaces the tracks and the state with them. There is
no "active station" to set, clear or fall out of step with the queue.

A missing station, an empty queue, an HTTP failure and a transport failure map
to the same results as any other Mix row: an HTTP failure is a `LibraryResult`
error the car can act on; a transport failure completes the future
exceptionally so it reads as "unreachable".

### Keeping it going

The `playing` and `stopped` reports already sent for every track carry
`playQueueItemID` and `containerKey=/playQueues/{id}` when the track has a
play-queue item id. Nothing else about reporting changes; Siskin sends no
periodic progress, and does not need to for this — a report at each track's
start is what advances the station. #204 adds the periodic reports and must
carry the same fields.

On a track change, with a station active and two or fewer items after the
current one: report, then `GET /playQueues/{id}`, then append what is new —
sequenced, so the read happens after the server has extended the queue.

- **Deduplicated by `mediaId`**, as continuous play does. This is a
  correctness requirement, not tidiness: `QueueRepository.insertAll` silently
  drops a `mediaId` already in the saved queue, so appending a repeat would
  desynchronise the player from Room.
- **One top-up at a time**, guarded the way `continuousPlayIsRunning` guards
  continuous play, so rapid skips cannot append a batch twice.
- **Trimmed** to the existing tracks-to-keep preference, as continuous play
  trims, so a long drive does not grow the queue without bound.
- **A 404 means the server discarded the queue.** Draw a new station for the
  same artist, make it the active station, append from it.
- **Any other failure is retried on the next track change.** If the queue runs
  dry anyway, continuous play takes over when it is enabled, as for any queue.

### A station survives a restart

A head unit that kills the process at a fuel stop resumes from the saved
queue. Letting the station silently stop growing there was rejected.

- The `queue` table gains three nullable columns — `play_queue_id`,
  `play_queue_item_id`, `station_artist_id` — through Room's first migration
  (1 → 2), carried through `TrackFields` and `Queue.toMediaItem()`.
- A station belongs to one server, and reporting against or topping up from
  another would be the mixed-session failure `PlexSession` exists to prevent.
  Keeping the state on the tracks gets this for free: a server change already
  discards the saved queue and stops the player
  (`LibrarySelection.invalidatesQueue`), so station tracks never outlive their
  server.
- The alternative, an "active station" in preferences keyed by machine
  identifier, needs a mismatch check and a clear at every place a queue is
  replaced; state carried by the tracks needs neither.
- Restore needs no special path: `restorePlayerFromQueue` rebuilds the player,
  and its first track change runs the logic above, including the 404 redraw if
  the server has expired the old queue.

## Testing

- API layer against MockWebServer: the `includeStations` fetch and the
  `Stations` object parsed from captured JSON; the play-queue `POST` and its
  exact `uri`; the `GET` and its 404.
- `PlexRadioRepository`: a fresh key per tap, server order preserved, item ids
  carried, the `/identity` fallback, each failure's mapping.
- The top-up logic extracted from `BaseMediaService` into a unit testable
  without a player: threshold, report-before-read, dedup, single flight, trim,
  404 redraw, and a current item that is not a station track doing nothing.
- The Room migration, and a round trip through `Queue.toMediaItem()`.
- The artist page and Settings, mirroring the Artist Mix tests; Mix rows still
  shuffle and radio does not.
- On the AAOS emulator against the owner's server: start a station, skip until
  it tops up (timeline and `GET` visible in the log), force-stop mid-station and
  watch it resume and keep growing, toggle the setting from an artist page.
