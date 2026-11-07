# A Mixes tab plays the server's Mixes for You

**Date:** 2026-11-07
**Status:** Approved
**Issue:** #310

Builds on the radio stations design
(`docs/decisions/2026-11-01-radio-stations-design.md`), whose play queue,
top-up and redraw a mix reuses.

## Context

Plexamp's home screen leads with **Mixes for You**: personalized mixes named
after an artist, each drawing on several related artists chosen from listening
history. Siskin has nothing like it. Its Artist Mix shuffles one artist's own
tracks, and Artist Radio is a station seeded by one artist; neither spans a
neighborhood of artists the way a Plexamp mix does.

## What the server does

Measured on 2026-11-07 against PMS on `jorge`, section 7 (`Music`). The issue
records the requests Plexamp makes; these repeat them.

- **The row is a hub behind one parameter.** `GET /hubs/sections/{id}` with
  `includeMyMixes=1` adds `music.mixes.{id}`, `context: hub.music.mixes`,
  `type: playlist`, `more: false`, and no `key` — so `isSafeHubKey` would keep
  it out of Discover even if Discover asked. It held seven mixes; the issue saw
  six.
- **A mix is a smart playlist with no ratingKey.** `title` ("Anberlin Mix"),
  `leafCount: 0`, `icon: playlist://image.smart`, and a `key` that is a library
  filter rather than a metadata path:
  `/library/sections/7/all?artist.id=259987,270963&or=1&album.id=256469,…`.
  `artist.id` is sometimes empty and sometimes not; `album.id` lists 5 to 21
  albums, and the longest key was 195 characters.
- **Each mix names its artists.** A nested `Metadata[]` of 5 to 10 artists,
  exactly one with `centroid: true` — the mix's namesake — each with a real
  `thumb`.
- **The set re-rolls.** Two back-to-back fetches once returned the same seven
  mixes in the same order, but later fetches the same day each returned a
  different set, of four to eight mixes. Ids must survive a re-roll.
- **The artists' biographies are most of the response.** Each nested artist
  carries its full `summary`: the request answered 148 KB gzipped, and 13 KB
  with `excludeFields=summary`, which leaves every other field in place.
- **The key is a finite track set.** Following Andrew W.K. Mix's key with
  `type=10` returned 156 tracks, all by the seven artists the mix lists.
- **It plays as a station does.** `POST /playQueues?type=audio&shuffle=1` with
  `uri=server://{machine}/com.plexapp.plugins.library{key}` answered with a
  21-item window, full `Media` and `Part`, `playQueueShuffled: true`, and a
  `playQueueTotalCount` equal to the key's track count: 156 for Andrew W.K. Mix,
  271 for Anberlin Mix. With `shuffle=0` the same queue runs album by album.
  A `window=500` read returned all 156 of one mix but only 200 of the 271.
  No timeline was reported against these queues.
- **A shuffled queue numbers its items in play order.** Across a 200-item read
  of a shuffled 233-track mix, `playQueueItemID` rose at every step. The top-up
  and `hasRunOut` both rely on that, as they do for an unshuffled station.

## Decisions

### A destination, second by default

`Constants.MIXES_ID` joins `BrowseTabOrder.DEFAULT_ORDER` second, so a fresh
install's root is Playlists, Mixes, Radio, More, and Artists moves under More.
Plexamp treats this row as the first thing to play, and a destination is the
only placement that is one tap from the root without crowding another tab. An
existing saved order gets Mixes last, under More, by the append rule, for the
reason the radio design gives.

Top of Radio was considered: no new tab, but thirteen rows with the stations
below the fold. Discover's first row fits the data best — it already mirrors
hubs — but sits under More by default.

### Rows come from the hub, as a grid

A separate `getSectionHubsWithMixes` sends `includeMyMixes=1`, so Discover and
Radio keep their own reads and a mix appears in one place. It also sends
`excludeFields=summary`, since nothing reads a biography and this is the second
tab. `count` was not used: it shrinks the other hubs but did not bound the
mixes, and Plexamp's `count=6` is not known to be what limits its row. The tab
keeps hubs whose `context` is `hub.music.mixes` — not `type: playlist`, which
ordinary playlist hubs carry too — in server order, minus any mix whose key
fails `isSafeHubKey`.

Each mix is a playable grid tile:

- **Title** is the server's, untranslated, as station titles are.
- **Subtitle** is the non-centroid artists, comma-joined. A tile may truncate
  it; the photo is what tells the tiles apart.
- **Artwork** is the centroid artist's `thumb`, else the first artist's, else
  the playlist icon.

A grid was chosen over a list with a legible subtitle because six or seven
photos are recognizable at a glance and names in a subtitle are not, while
driving.

A server with no mixes — no listening history, or a hub that does not arrive —
gets a message row, as an empty Discover and Radio do. The tab title and that
message, title and hint, are the new strings, each with four translations.

### Ids carry the whole key

A tile's id is `[shuffleMixForYouID]` plus `HubKey.of(scope, key)`. The prefix
sits under `MIX_ID_PREFIX`, so a later build that drops mixes refuses a cached
tile rather than handing the player a row with no stream. The key names a section
by number, so the scope is needed for the reason a station's id needs it: a tile
minted under another library is refused at tap time, and the car is told to
re-fetch the tab on a server or library switch.

Because the key is self-contained, a tile the car still holds after the hub
re-rolls stays playable. Nothing looks the mix up again by name or position.

### A mix is started as a station

`mixTracksFor` gains a branch for that prefix that resolves the payload, refuses a
stale or unsafe key as `getRadioStationTracks` does, and starts
`StationSeed.Mix(key)` through `PlexRadioRepository`. `createPlayQueue` gains a
`shuffle` parameter: 1 for a mix, 0 for every existing seed, so what they send
is unchanged. `isStationRow` covers the new prefix, so Siskin does not shuffle
the result itself; the server's order is what the top-up follows.

Reading the key's tracks and shuffling them through the existing Mix path was
the alternative. It writes nothing to the server and needs no migration, but
plays a mix differently from Plexamp, and once the play queue was measured to
behave as a library station's does, the station path costs one parameter and
one seed.

### A mix that runs out is drawn again, reshuffled

The window and total are a library station's shape, so `StationTopUp` and
`extendStation` need no new logic. At the total, and after a 404, the existing
redraw posts the same key again; for a `Mix` seed it posts with `shuffle=1`, so
the next pass is a new order rather than the album-by-album one an unshuffled
redraw would give. Stopping at the end was considered — 156 to 271 tracks is
nine to sixteen hours — and declined for consistency with Radio, since
continuous play is off by default.

The car's shuffle toggle still applies on top and is left alone, as for
stations.

A mix of about 30 tracks or fewer can dedupe its redraw to nothing and stop at
its last track, as the radio design accepted for small stations. The smallest
measured mix is 156.

### Which mix a track came from

`StationSeed` gains `Mix(key)`, and migration 3 → 4 adds a nullable
`station_mix_key` to `queue`. `StationSeed.of` reads three columns; exactly one
set is a station, anything else is not. The stored key is bare, as
`station_key` is: a server change discards the saved queue, and a library change
keeps playing what was queued.

Storing a mix's key in `station_key` needs no migration, telling the two apart
by path — `/all?` against `/stations/`. That decides the kind by inspecting a
string, which the radio design declined for the artist column, and the redraw's
shuffle would hang on it.

## Unmeasured

Whether the hub arrives for an `owned=false` session — a managed Home user, or
an account on a shared server — and whether such a session can start a mix.
The empty row covers the first; the emulator check below records both.

## Testing

- Hub parsing from captured JSON: picked by context, server order kept, an
  unsafe key dropped, artwork falling back centroid → first artist → icon, the
  subtitle without the centroid, and the empty row for a missing or empty hub.
- A tile minted under another library is refused before any request, and a key
  with a long `album.id` list round-trips through `HubKey`.
- `PlexRadioRepository` with a `Mix` seed: the start posts `shuffle=1` and the
  other seeds still post 0; the redraw at the total and after a 404 posts
  `shuffle=1`.
- The 3 → 4 migration; `Queue.toMediaItem()` for all three seeds; two columns
  set reads as no station.
- `BrowseTabOrder`: Mixes second with no saved order, appended to a saved one.
- On the landscape AAOS emulator: open Mixes and see the grid with artist
  photos, start a mix, skip until it tops up, force-stop and watch it resume,
  and on an `owned=false` session record whether the hub arrives.
