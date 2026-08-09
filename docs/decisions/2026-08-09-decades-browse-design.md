# Decades in the More tab

**Date:** 2026-08-09
**Status:** Approved

## Context

Adds a browse axis the fork has not had. Playlists, Artists and Albums are the
three root tabs the car allows content in; the root is capped at four and the
fourth is More, so anything new nests there — see `MediaBrowserTree.buildTree`.
It renders above the library picker that
[the More tab library selection design](2026-07-29-more-tab-library-selection-design.md)
put under More, making More's second entry and its first row.

Composite artwork for the decade rows is deliberately out of scope and is filed
as [#84](https://github.com/codingismy11to7/siskin/issues/84); this ships as a
list whose rows carry no artwork of their own.

## Why

A music library accumulates decades the owner never browses by. Artists and
Albums are both alphabetical walks through forty thousand tracks, and neither
answers "play me something from the eighties" — the question that actually gets
asked in a car, where the driver cannot read a grid.

The name is *Decades*, not *Decade Radio*. "Radio" everywhere else in music
software means an endless stream generated from a seed. This is a bounded
random sample of a fixed set, which is a shuffle, and calling it radio would
promise behaviour it does not have.

## What the server already knows

Everything needed to enumerate decades exists server-side, so nothing here is
hardcoded. Measured against PMS 1.43.3 on a live 40,565-track library:

```
GET /library/sections/{key}/decade?type=9
```

returns exactly the decades present, already titled and newest-first, in ~0.14s:

```
2020s 2010s 2000s 1990s 1980s 1970s 1960s 1950s
```

No min/max year computation, no hardcoded list, no gaps to reason about — a
decade with nothing in it is simply not returned.

The shape of that library, which is what the design is sized against:

| | 2020s | 2010s | 2000s | 1990s | 1980s | 1970s | 1960s | 1950s |
|---|---|---|---|---|---|---|---|---|
| albums | 8 | 763 | 1089 | 717 | 105 | 45 | 45 | 5 |
| tracks | 117 | 10267 | 17649 | 10134 | 1268 | 529 | 529 | 72 |

The track row sums to 40,565, exactly the section's total, so every track lands
in a decade on this library. (The 1960s and 1970s really are both 529 — verified
separately, different content.)

### Three silent failures worth writing down

Each of these answers **HTTP 200** while doing nothing, which is the reason they
are recorded here rather than left to be rediscovered:

- **Decades are enumerated from albums (`type=9`).** The filter does not exist
  for tracks: `filters?type=10` lists only mood, genre, userRating and
  audioCodec.
- **Tracks filter on `album.decade`, not `decade`.** `type=10&decade=1980`
  returns 200 with zero results; `type=10&album.decade=1980` returns the 1,268
  tracks. `type=10&year=1985` is empty for the same reason.
- **`sort=random` takes no seed.** `sort=random:12345` is accepted and ignored —
  two identical calls return different tracks. Client-side caching is the only
  way to stabilise a draw.

This is the same class of trap as `sectionID` versus `librarySectionID` in
`SearchService.getPlaylists`, and gets the same treatment: the measurement lives
in the KDoc.

## The rule

**A decade is a random sample, not a listing.**

The 2000s holds 17,649 tracks against a `ConstantsAA.MAX_ITEMS` cap of 500. No
arrangement of the browse tree can show a decade completely, so the node does not
pretend to: it asks for `sort=random` and shows 500 of them.

The alternative — the first 500 in library order — was rejected because it is
worse than incomplete, it is *consistently* incomplete. The same sliver of the
alphabet, forever, with the rest of the decade unreachable by any sequence of
taps.

Two consequences follow, and both are acceptable.

**The list resamples when re-entered.** Measured on an AAOS API 33 emulator by
counting `onGetChildren` per `parentId`:

| action | re-requests the node? |
|---|---|
| scrolling the list | **no** |
| leaving the tab and returning | **yes** |

`MediaLibraryServiceCallback.onGetChildren` ignores `page` and `pageSize`
entirely and hands `MediaBrowserTree.getChildren` only the id, so every request
is a full re-fetch. For the existing stable nodes that is invisible. For a random
one it means a track spotted and then navigated away from is gone on return. The
list does *not* reshuffle mid-scroll, which is the failure that would actually
matter, and that is ruled out by measurement rather than assumed.

**A decade cannot be exhausted in one sitting.** 500 tracks is roughly 33 hours,
so the cap is not felt during a drive; it is only felt across drives, as
variety, which is what the node is for.

## What gets built

### The tree

```
More
├── Decades                    ← new, above Server Select
│   ├── 2020s
│   ├── 2010s
│   ⋮
│   └── 1950s
│       ├── ▸ Shuffle the decade
│       └── up to 500 tracks, randomly sampled
└── Server Select
```

Three ids in `ConstantsAA`:

| id | kind | payload |
|---|---|---|
| `DECADES_ID` | static node, registered in `treeNodes` beside `SELECT_LIBRARY_ID` | — |
| `DECADE_ID` | prefix | the decade key, `"1980"` |
| `SHUFFLE_DECADE_ID` | prefix | the decade key |

`SHUFFLE_DECADE_ID` makes a third shuffle prefix. The existing KDoc explains the
choice — "Two prefixes rather than one plus an embedded kind, because the prefix
is what the callback dispatches on" — and the reasoning extends unchanged to
three, but **that comment must be updated with the constant** rather than left
asserting there are two.

Decade rows need no `MediaBrowserTree.getItem` branch, and this is a deliberate
non-change rather than an oversight. That function's KDoc documents a real
hazard: an id absent from `treeNodes` returns null, `onGetItem` errors, and the
default `onSubscribe` drops the subscription. But only `BrowseTreeInvalidator`
depends on a live subscription, and nothing invalidates a decade. Artist and
album rows already live without a branch and navigate correctly; the picker rows
need one precisely because the More tab updates itself underneath the user.

### Plex layer

`LibraryService` gains one endpoint and one parameter, chosen over a separate
`DecadeService`/`DecadeClient` pair because `album.decade` is the same cross-type
filter pattern `artist.id` already uses on `getSectionContent` — an established
precedent rather than a new one, and no third client for
`PlexBrowseRepository.refreshClients` to rebuild on session change.

```kotlin
@GET("library/sections/{sectionId}/decade")
suspend fun getDecades(
    @Path("sectionId") sectionId: String,
    @Query("type") type: Int
): PlexResponse

// getSectionContent, one parameter added:
@Query("album.decade") decade: String?
```

`LibraryClient` wraps both in `plexCall` like every other call, so a decade
browse returns `Either<PlexTransportFailure, PlexResponse>` and the HTTP-versus-
transport distinction holds without new code.

The server's `fastKey` (`/library/sections/4/all?decade=1980&type=9`) is
deliberately not followed verbatim: it encodes `type=9`, and the track listing
needs `type=10` with `album.decade`, so using it would mean string surgery on a
server-supplied URL and would abandon the typed-client boundary.

### Repository

`PlexBrowseRepository` gains three functions, mirroring the playlist trio:

```kotlin
fun getDecades(prefix: String)                    // Directory rows → browsable items
fun getDecadeTracks(decadeKey: String)            // shuffle row + tracks
fun getDecadeTracksForShuffle(decadeKey: String)  // the same tracks, no row
```

The last two share a `decorate` helper the way `playlistTracks` does, for the
reason already recorded there: a queue containing the shuffle row would hold a
playable item with no stream.

Decades arrive as `MediaContainer.Directory`, not `Metadata`, so `itemsOf` and
`tracksOf` do not apply — `LibraryClient.musicSections` is the existing
precedent for reading `Directory`. A decade entry carries only `fastKey`, `key`
and `title`, and **no `type` field**, so the filter is non-blank key and title
rather than a type match.

Tracks are tagged `QUEUE_CACHED_SOURCE`, like album and playlist tracks, so
tapping the seventh row plays 7…500 rather than that track alone.

**`sort=random` here does not contradict `getArtistTracks` fetching unsorted.**
That function's KDoc explains it stays unsorted so that turning car-shuffle off
mid-listen falls back to the artist's real running order rather than one the
function invented. A decade has no real running order to fall back to, and
unsorted would mean permanently sampling the first 500 of 17,649. Random *is*
the honest description of what the server was asked for. Both KDocs should say
so, so the two do not read as an inconsistency.

### Wiring

`isShuffleRow` gains a third clause; `shuffleTracksFor` gains a branch to
`getDecadeTracksForShuffle`. Nothing else changes: `setShuffleForTap`,
`openingPositionIn` and the car-shuffle setting all key off `isShuffleRow`, and
[shuffle follows the tap](2026-08-02-shuffle-follows-the-tap-design.md) holds
unchanged — the decade shuffle row turns shuffle on, a track inside a decade
turns it off.

Error handling is entirely inherited from `fetch`: 401/403 reaches the sign-in
affordance, a transport failure completes the future exceptionally. No new paths.

### Strings and assets

Two strings, and therefore **ten entries** across the five locales
(`values`, `-de`, `-es`, `-fr`, `-it`), plus one drawable:

- `aa_decades` → "Decades"
- `aa_shuffle_decade` → "Shuffle the decade"

`aa_shuffle_decade` is fixed text rather than a format string. "Shuffle the
1980s" would need a `%s` and would raise, in four other languages, the question
of how a decade ordinal agrees with the surrounding sentence. The row already
sits under a screen titled with the decade, so the specificity is not lost.

The decade labels themselves (`1980s`) are server data and are neither
translated nor `translatable="false"` — they are not string resources at all.

`ic_aa_decades` dresses the **Decades row in More**, and only that row. More has
two rows and Server Select already wears `ic_aa_library`; a bare Decades beside
it would put the car's placeholder next to a real glyph in a two-row list.

The eight decade rows underneath carry **no `iconRes`**, and therefore no
`artworkUri`, which hands them to the car's own placeholder — a music note on a
colour picked per row. `LibraryPickerRepository.browsableRow`'s KDoc calls that
colour noisy, and it is right that the colour means nothing; it is accepted here
because the rows are a homogeneous set where a single repeated glyph would carry
no more information than the colour does, and because the whole row is due to be
replaced by a composite under
[#84](https://github.com/codingismy11to7/siskin/issues/84). Note this is the one
place the car draws something we did not choose — it is not the same as drawing
nothing, which the platform does not offer.

## What deliberately does not change

**No per-decade artwork, and therefore no grid.** Plex has no composite for a filter value:
the decade `Directory` entries carry no `thumb`, `composite` or `art`;
`/library/sections/{key}/decade/{decade}/composite` 404s; and
`/library/sections/{key}/composite/1` is a *section-wide* mosaic, identical for
every decade. Synthesizing a 4- or 9-cover tile is real work with real open
questions — where a generated bitmap lives given media3 wants a `Uri`, what a
5-album decade does with a 9-cell grid — so it is [#84](https://github.com/codingismy11to7/siskin/issues/84),
not this. `browsableChildrenAsGrid` flips in one line when that lands.

**No caching layer to stabilise the sample.** Plex offers no random seed, so
stability would have to be an in-memory draw cache with its own eviction policy.
`PlexBrowseRepository` deliberately does not touch Room, and resampling on
re-entry is the behaviour this node wants anyway.

**No paging.** `onGetChildren` already ignores `page`/`pageSize` for every node;
decades introduce no reason to change that, and a random sort makes paging
incoherent in any case — page 2 of a re-randomised query is neither the next 500
nor a stable set.

## Edge cases

- **A decade under the cap** (1950s, 72 tracks) is listed completely and
  `sort=random` merely reorders it.
- **Albums with no year** appear under no decade, and the server offers no
  "Unknown" bucket. This is a silent omission by construction. Not live on the
  reference library, where the decade counts sum exactly to the section total.
- **An empty decade list** — a section whose albums are all undated — renders as
  an empty list, consistent with how an empty Albums tab behaves.

## Testing

Reusing the existing fixtures rather than standing up new setup:

- MockWebServer over the decade request path, asserting the track filter is
  spelled `album.decade`. This is the trap the design is most likely to regress
  on, so it earns a direct assertion rather than coverage by implication.
- `Directory` → browsable items carrying `DECADE_ID`-prefixed ids.
- The shuffle row is first in `getDecadeTracks` and absent from
  `getDecadeTracksForShuffle`.
- `isShuffleRow` recognises `SHUFFLE_DECADE_ID`.
- `lintDebug` reports no new `MissingTranslation`, which is what proves the ten
  string entries actually landed.
