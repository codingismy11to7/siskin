# A Radio tab starts the server's library stations

**Date:** 2026-11-01
**Status:** Approved
**Issue:** #296

Builds on the artist radio design
(`docs/decisions/2026-10-23-artist-radio-design.md`). Album radio is #299.

## Context

Plexamp's radio is a family of stations and Siskin offers one of them, Artist
Radio, which is off by default. A driver who never opens Settings would
reasonably conclude there is no radio. The four library stations are what most
people mean by Plexamp radio, and the artist work already built the play queue,
top-up and restart survival they need.

## What the server does

Measured on 2026-11-01 against a live PMS with two music sections: `Music`,
12,596 tracks, and `Lossless Music`, 3,681 tracks, which the artist radio design
measured as unanalysed.

- **Stations are a hub.** `GET /hubs/sections/{id}?includeStations=1` adds
  `music.stations.{id}`, `type: station`, on both sections. It holds Library
  Radio, Deep Cuts Radio, Time Travel Radio and Random Album Radio, each with
  `radio: "1"`, `type: playlist`, and a guid such as
  `tv.plex://station/deep-cuts`. Plex's own hub asks for a grid, but the only
  artwork is `icon: playlist://image.radio`, which is not a fetchable image.
- **Every kind of session gets the hub.** On the emulator it arrived with four
  stations for the owner, for a managed Home user on the same server, and for an
  account on a friend's shared server, the last two with `owned=false`. Fewer
  Discover rows on those sessions is their listening history, not a limit on the
  hub. The hub carries no `key`, so `isSafeHubKey` already drops it from Discover.
  Starting a station from an `owned=false` session is unmeasured.
- **Keys are stable.** `/library/sections/{id}/stations/{n}`, with `n` of 1, 8, 2
  and 3, did not change between requests. Unlike an artist station's key, it can
  ride in a browse id. Stability alone is not enough: a key names a section by
  number, so the id carries the library's scope too (see the list section below).
- **A station starts the way an artist station does.** The same `POST /playQueues`
  answered in under 100 ms with full track metadata, `Media` and `Part` included.
- **It arrives as a window, not five tracks.** 21 items, the selected one first.
  A plain `GET /playQueues/{id}` returns the same window; `window=100` widens it.
- **A timeline report moves the window.** A `stopped` report at `time=0` moved the
  selection to the item it named, and the next read covered 20 items either side
  of it. Time Travel was walked through all 80 items this way, each one reported
  and read back: no repeats, and item ids rising throughout.
- **Library stations are finite.** Unlike an artist station they report
  `playQueueTotalCount`: Library 1,337, Deep Cuts 9,143, Time Travel 80, and
  Random Album 12,596, the section's track count. Library on `Lossless Music`
  was 530. At the end the window simply stops: with two items left,
  `playQueueSelectedItemOffset` plus the items after the selection plus one equals
  the total, and reads still answer 200 after the last item.
- **Order means something on two of them.** Time Travel runs chronologically,
  its years never falling across the 80 items, and Random Album plays whole albums
  in track order.
- **A track or an album has no station.** `includeStations=1` returned no
  `Stations` on either, one sample from each section, so track radio and album
  radio cannot follow the artist path.

## Decisions

### Scope is the four library stations

Track radio has no server station and would need `/nearest` and a queue Siskin
owns. Genre, style, mood and decade radio have no known server station at all.
Both are separate work. Album radio is #299.

### A destination, third by default

`Constants.RADIO_ID` joins `BrowseTabOrder.DEFAULT_ORDER` third, so the default
root is Playlists, Artists, Radio, More. This moves Albums under More for anyone
who has never saved an order, because the order is only saved when someone
reorders tabs. That cost is accepted: under More, Radio would be as hidden as
Artist Radio is. Anyone with a saved order gets Radio last, under More, by the
existing append rule, so no tab they chose is demoted. Fourth was considered and
leaves Radio hidden; first changes what every default install opens on.

Discover does not gain the stations. It keeps its own hub read, so a station is
in one place.

### Rows come from the hub, with the server's titles

The tab reads the hub and keeps `type: station` hubs, in server order. A fixed
list would hardcode station numbers seen on one server and show rows a server
might not have.

Titles are the server's, untranslated. Matching guids to our own strings was
considered and costs four strings in five locales; the accepted cost is English
rows in a translated car if the server sends English, which has not been tested
on a server set to another language.

A server with no stations gets a message row, as an empty Discover does. The tab
title and that message are the only new strings, each with four translations.

### A list with one radio icon

Playable, not browsable, id `[radioStationID]<scope and key>`, built with `HubKey`
because a key names a section by number: server A's section 7 is not server B's.
A row cached across a server or library switch is refused at tap time, and the
car is told to re-fetch the tab on a switch. A refused tap fails the request
rather than answering with no tracks, which would stop whatever is playing. The
stored `station_key` stays the bare key: a server change discards the saved
queue, and a library change on one server keeps playing what was queued, as it
does for any other queue, so a station redraws from its own library until the
driver starts another. The row uses the media3 radio
icon Artist Radio uses. At 1024×768 all four rows fit until the mini player
appears, which covers the fourth. A grid gives four identical tiles and needs a
scroll to reach the fourth either way; an icon per station tells the rows apart
but needs four drawables.

### Starting and extending a station

`mixTracksFor` gains a branch for the new row, reusing `createPlayQueue`, the
`/identity` fallback and the Mix rows' failure mapping. Stations are not shuffled
by Siskin, as the artist design explains.

The car's shuffle mode still applies, which scrambles Time Travel and Random
Album. That is left alone: shuffle is the driver's and it is visible. Turning it
off on start changes their setting and would need restoring.

`StationTopUp` keeps its threshold and report-before-read order. The window size
differs from an artist station's, but the logic reads what follows the last
queued item either way.

### A station that runs out is drawn again

When a read finds nothing new and the window has reached
`playQueueTotalCount`, `extendStation` draws a fresh station from the same key,
as it does after a 404. The top-up's low-water mark means this happens with two
tracks left, so the new draw arrives before the music stops. Continuous play is
off by default, so handing over would
leave radio stopping dead after a few hours, which in practice is Time Travel.
The window follows the server's selection, so with the car's shuffle on a track
can play with the rest of the station out of the window's reach. A read that
finds nothing new on the player's last track therefore redraws wherever the
window sits.
The redraw returns Time Travel to its earliest era with a new selection, and
tracks still queued are dropped by the mediaId check. An artist station reports
no total, so it never takes this path.

A station small enough that all of it stays in the player, about 30 tracks or
fewer, can dedupe a redraw to nothing and stop at its last track. Accepted: the
smallest measured station is 80.

### Which station a track came from

`StationTag`'s seed becomes sealed, artist or library. The `queue` table gains a
nullable `station_key` in migration 2 → 3, and `station_artist_id` stays, so a
saved artist station keeps working untouched. Exactly one is set;
`Queue.stationTag()` treats anything else as no station.

Replacing both with `station_kind` and `station_seed` is more general but has to
rewrite existing rows. Storing the key in `station_artist_id` needs no migration,
but the column would lie and the kind would be decided by inspecting a string.

### Artist Radio stays off by default

The Radio tab answers whether Siskin has radio, and the reason for the default
still holds: the row pushes an artist's albums down.

## Testing

- Hub parsing and the play queue against MockWebServer, from captured JSON, with
  `playQueueTotalCount` present and absent.
- `PlexRadioRepository`: a library seed starts and extends; the redraw fires at
  the total and only there; a 404 redraws from the key.
- The Room migration, and a round trip through `Queue.toMediaItem()` for both
  seeds.
- `BrowseTabOrder` with the new destination, for no saved order and a saved one.
- The empty row, and server order preserved.
- On the AAOS emulator, on an `owned=false` session: start each station, skip
  until it tops up, force-stop and watch it resume.
