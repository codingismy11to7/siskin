# Decade, Style and Mood Radio replace Decades

**Date:** 2026-11-06
**Status:** Approved

Builds on the radio stations design
(`docs/decisions/2026-11-01-radio-stations-design.md`), which found no server
station for decade, style or mood. There is one; that search did not ask for it.

## Context

Decades is its own destination, and a decade plays a random sample of its
tracks that Siskin shuffles. The Radio tab plays the server's stations, which
the server extends as they play. Plexamp files decades under its radio, beside
Style Radio and Mood Radio, and Siskin has neither of those.

## What the server does

Measured on 2026-11-06 against PMS on `jorge`, section 7 (`Music`), by reading
Plexamp's requests out of the server log and then repeating them.

- **The directories are one parameter away.** Plexamp asks for
  `/hubs/sections/{id}` with `includeStations=1&includeStationDirectories=1`.
  The second parameter adds three items to the same `music.stations.{id}` hub,
  after the four stations: Style Radio, Mood Radio and Decade Radio, each
  `type: directory`, `radio: "1"`, with keys `/library/sections/7/stations/style`,
  `…/mood` and `…/decade`.
- **A directory lists finished stations.** `GET` on a directory key returns
  `Metadata` in the shape the four stations already have — `type: playlist`,
  `radio: "1"`, a server title such as "1990s Radio" — each with a key to play:
  `/stations/6/{decade}` for a decade, `/stations/7/{tagId}` for a style or a
  mood. Guids name the kind: `tv.plex://station/decade/1990`,
  `tv.plex://station/style/148896`, `tv.plex://station/mood/145840`.
- **One response, no paging.** Decade returned 6 rows (1970–2020), Style 33,
  Mood 50. The library's tracks carry 226 moods, so the server lists a subset.
  Plexamp requested items 0–39 and got all of each.
- **They start like the library stations.** The same `POST /playQueues`, with a
  21-item window and a `playQueueTotalCount`, unshuffled.
- **Plexamp's extra parameters change nothing measured.** It appends
  `includeSharedContent=1&maxDegreesOfSeparation=-1` to the station URI. With
  and without them, 2020s Radio totalled 37 and Aggressive Radio 419.
- **A decade station is a fixed subset of the decade.**

  | decade | station total | tracks with that `album.decade` |
  |---|---|---|
  | 1970 | 33 | 137 |
  | 1980 | 67 | 227 |
  | 1990 | 324 | 1,902 |
  | 2000 | 906 | 6,998 |
  | 2010 | 366 | 3,027 |
  | 2020 | 37 | 166 |

  Each total was the same on two draws. How the server chooses is not exposed.

## Decisions

### Decade radio is the server's station

A decade row starts `/stations/6/{decade}` and Decades as a destination is
deleted, along with the decade shuffle row, its browse list and the cached-list
replay behind it. The station is a smaller pool than the decade: 1970s Radio is
33 tracks of 137. That is accepted for what the station gives — it extends
itself, survives a restart and needs no Siskin-side draw — and because it is
what Plexamp plays for the same tap.

Feeding Siskin's own whole-decade draw through the station top-up was the
alternative. It keeps every track in play but needs a top-up with no server
queue behind it, which nothing has yet. It stays available if the small
stations feel thin in the car.

### The tab renders whatever the hub sends

`getRadioStations` asks for `includeStationDirectories=1` and keeps the hub's
order. A `directory` item becomes a browsable row; a station stays playable.
Matching on type rather than on Style, Mood or Decade by name means a fourth
directory a later server adds appears without a release.

A directory row's id is a new prefix plus `HubKey.of(scope, key)`, for the
reason a station's is: its key names a section by number. Opening one minted
under another library returns a "No stations here" row rather than querying
the new server, the same refusal `followHubKey` gives a stale Discover hub. It
names no cause: "No stations on this server" would blame a server that has
them. Its children are built by the code that builds the four stations, so a
decade station and Library Radio are the same row to everything downstream.

### Decades keep their composite grid

Decade Radio's children render as a grid, each tile the composite of four
covers the Decades grid already draws. The row's decade is read from its guid,
`tv.plex://station/decade/{decade}`, rather than from the key: the guid names
the kind, and `6` in the key is only a number the server chose. A decade guid
whose suffix is not four digits gets the radio icon; the provider would refuse
it anyway.

The grid hint goes on the Decade Radio row itself, which is the one place the
type-blind rule above gives way: a directory's guid is empty, so the row is
recognised by its key ending `/stations/decade`. Misrecognising one costs a
layout — a list where a grid was wanted — and nothing else.

The composite's URI carries the library scope and the decade, exactly as
before, so existing cached composites are reused and the hourly bucket still
changes artwork without changing ids.

Style and Mood are lists with the radio icon. Nothing draws a composite for a
tag, and a grid of 50 identical icons is worse than a list of 50 names.

### Style and Mood are shown whole, in server order

33 and 50 rows, unfiltered. AAOS cuts how far a list scrolls while driving;
that is the car's rule to apply, and Plexamp shows the same lists.

### A small station still stops

The radio stations design accepted that a station of about 30 tracks or fewer
can dedupe its redraw to nothing and stop at its last track, on the grounds that
the smallest measured station was 80. 1970s Radio is 33 and 2020s Radio 37,
just above that line, and a smaller library will have decades below it. That
stays accepted: the end of a small station stopping is honest, and padding it
with repeats was considered and declined.

### Removing a destination needs no migration

`BrowseTabOrder.resolve` drops a saved id this build does not know, so an order
that held Decades loses it and the next destination takes its place. A car
still holding a Decades or decade browse id from before the upgrade gets the
same error any unknown id gets. A stale Decade Mix row is refused outright:
treated as a track, it would be handed to the player with no stream and saved
as the whole queue.

## Testing

- Hub parsing with directories present: order kept, directories browsable,
  stations playable, and an unsafe directory key dropped.
- A directory listing from captured JSON: decade rows carry the composite URI,
  style and mood rows the radio icon, and a malformed decade guid falls back.
- A directory id minted under another library is refused before any request.
- `BrowseTabOrder` with Decades removed from a saved order.
- On the AAOS emulator: open Decade Radio and see the grid with composites,
  start a decade, a style and a mood, and skip until each tops up.
