# Artists by initial, as a setting

**Date:** 2026-08-10
**Status:** Approved

## Context

[Windowed browse for lists too long to send](2026-08-09-browse-list-windowing-design.md)
— shipped as [#87](https://github.com/codingismy11to7/siskin/pull/87) — considered
first-character buckets and rejected them, in a section titled "Offsets, not
first-character buckets". The reason was measured and remains correct: album
buckets **B = 350** and **S = 315** are both over the car's ~281-item ceiling, so
letters would need a second splitting mechanism bolted underneath them for
exactly the cases that matter.

That is an *album* fact. Artists bucket harmlessly, and the two tabs do not have
to answer the same way. This document adds letter buckets as a setting on the
**Artists tab only**, defaulting on, and leaves the Albums tab windowed
unconditionally.

The earlier document is the historical record of the decision it made and is not
edited here. This one supersedes its first-character rejection **for artists**;
for albums that rejection still stands, on the same numbers, re-measured below.

## What is actually true

Everything here was measured on 2026-08-10 against the same live PMS 1.43.3
library #87 used — 1204 artists, 2777 albums — reached over `plex.direct` from a
development machine rather than from the head unit.

### The index is one small request

`GET /library/sections/{key}/firstCharacter?type=8` answers **1303 bytes in
0.085–0.116 s** with one `Directory` per bucket, each carrying exactly three
fields:

```json
{"size": 12, "key": "%23", "title": "#"}
{"size": 79, "key": "A",   "title": "A"}
```

No `thumb`, no `composite`, and — unlike the decade index — **no `fastKey`**. The
`MediaContainer` also sets `title2: "By First Letter"`.

| # | A | B | C | D | E | F | G | H | I | J | K | L | M |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 12 | 79 | 95 | 80 | 76 | 30 | 53 | 63 | 47 | 26 | 29 | 14 | 64 | 98 |

| N | O | P | Q | R | S | T | U | V | W | Y | Z | ∆ |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 28 | 30 | 64 | 4 | 50 | **133** | 63 | 8 | 12 | 28 | 10 | 7 | 1 |

The counts sum to exactly 1204, the `totalSize` the section listing reports, so
the index is a complete partition and nothing is missing from it.

Two things about that bucket set are worth stating because they are easy to
assume wrong:

- **Empty letters are omitted.** There is no `X` bucket in this library. "27" is
  this library's number, not a constant, and no code may index by letter or
  assume a fixed count.
- **The set is not "`#` plus the alphabet".** `∆` is its own bucket, holding one
  artist — ∆AIMON, which carries no `titleSort` at all. Meanwhile `#` holds the
  digits and other symbols: twelve artists, among them `$uicideboy$`, `3epkano`,
  `187 Lockdown`, `808 State` and *The Hilliard Ensemble* (`titleSort:
  [anonymous]`). Whatever rule Plex applies, a bucket key is an opaque
  server-supplied string and is treated as one.

### A bucket is a path segment, not a query

`GET /library/sections/{key}/firstCharacter/D` returns `totalSize = 76`, matching
bucket D's count exactly.

The obvious spelling is wrong and **wrong silently**, which is the trap this
codebase has hit twice before (`album.decade`, and `sectionID` on playlists):

| request | result |
|---|---|
| `/library/sections/4/firstCharacter/D` | 200, `totalSize=76` — the bucket |
| `/library/sections/4/all?type=8&firstCharacter=D` | 200, `totalSize=1204` — **the whole library**, 5.19 MB |

A caller who reached for the query form would see a full, correctly-formed
artist list under a heading that says "D" and no error anywhere. It earns a
direct MockWebServer assertion for that reason.

Three further properties, each measured:

- **The `key` field is the segment, not `title`.** They differ for the symbol
  bucket: `key` is `"%23"` where `title` is `"#"`. A `#` interpolated into a URL
  is a fragment delimiter and would silently request the whole section.
- **`key` arrives already percent-encoded**, so it must be spliced in with
  `@Path(encoded = true)`. Letting Retrofit encode it again produces `%2523`.
  `∆` arrives raw and OkHttp encodes it on the way out.
- **`type` is honoured on the bucket path and changes the answer.** On key `Q`,
  `type=9` returns 3 albums where no `type` returns 4 artists. Omitting it works
  today only because a music section defaults to artists — a coincidence of the
  section's type, not a guarantee — so `type=8` is passed explicitly, the same
  rule `getDecades` follows in always passing `ALBUM`.

`X-Plex-Container-Start`/`-Size` work on a bucket too: `start=74` on bucket D
echoes `offset=74` and returns the last two entries. Nothing in this design
needs that, but it is what would make windowing *inside* an oversized bucket
possible later, and is recorded so a future reader does not have to re-measure
it.

### The sort inside a bucket must be the server default

This is the opposite call from #87, and the reason is measured. Bucket D, first
four entries:

| sort | opens with |
|---|---|
| `sort=title` | Arne Domnérus, Bob Dylan, Brigitte DeMeyer, Carl Craig |
| server default | Daft Punk, May Erlewine, Lucrecia Dalt, DāM-FunK |

A bucket labelled **D** whose list runs A, B, B, C reads as broken. Membership is
already decided by `titleSort`, so ordering the contents by anything else
scrambles them.

#87 reached the opposite conclusion for windows and both conclusions are right,
because the two shapes fail differently. A window is *named after the item at its
edge*, so an ordering its labels disagree with produces ranges that read as
broken alphabetising — only the sort can fix that, which is why windows force
`sort=title`. A letter bucket's label is a letter and its membership is the
server's; there is no edge item to disagree with, and forcing `sort=title` there
buys nothing while costing the coherence above. The tab therefore orders itself
differently under the two settings, on purpose.

### Filing is by `titleSort`, and mostly that is a feature

**426 of 1204 artists — 35% — file under a first character other than their
displayed one.** The bulk of that is ordinary library filing:

| displayed | filed under | `titleSort` |
|---|---|---|
| The Ahmad Jamal Trio | A | Ahmad Jamal Trio, The |
| Rashied Ali | A | Ali, Rashied & Lowe, Frank |
| Oren Ambarchi | A | Ambarchi, Oren |
| Bob Dylan | D | Dylan, Bob |

Surname-first, leading "The" dropped — what a record shop's divider cards do, and
what Plex's own web UI and iTunes both do. A minority is arbitrary, being alias
or collaboration data rather than filing:

| displayed | filed under | `titleSort` |
|---|---|---|
| DJ Zinc | A | Aquasky |
| Sandra Collins | A | Astral Projection |
| The Hilliard Ensemble | # | [anonymous] |

Those artists are effectively unreachable by initial. This was put to the user
explicitly and accepted: the buckets are Plex's, on Plex's field. Turning the
setting off restores a shape where every artist is reachable by scanning ranges
ordered by displayed name, which is what makes accepting this cheap.

### Artists fit; albums still do not

#87 measured the car keeping roughly the first 227 KB of a browse list — about
**293 artists** at 774 B each — and silently discarding the rest.

| | largest bucket | over the ceiling? |
|---|---|---|
| Artists | S = 133 | no, with room to spare |
| Albums | B = 350, S = 315 | yes, twice |

Album buckets are re-measured here and unchanged from #87's figures, which is why
the Albums tab is not offered this setting.

### It is cheaper than windows, not just different

| | requests | bytes | time |
|---|---|---|---|
| Letter list | 1 | 1.3 KB | 0.085 s |
| Window list (#87) | 1 + ~25 boundary titles | 11.5 KB | 351 ms in-app |
| Bucketing displayed initials ourselves | 1 | 5.19 MB | 0.64 s |

The letter list also cannot produce a degraded label. #87's window list falls back
to `"1 - 50"` when a boundary title times out at 3 s; a letter's label arrives in
the same response as its count, so `titleAt`, its timeout and its positional
fallback are simply not on this path.

The third row is the alternative that would have given exact displayed-initial
filing: fetch every artist and bucket them in-app. It is rejected on that 5.19 MB
— per tab entry, on a car's connection — and on introducing an in-memory artist
cache to make a letter's contents reachable afterwards.

## Design

### The setting

A boolean `artists_by_initial`, defaulting **true**, read through
`Preferences.isArtistsByInitialEnabled()` and written by
`setArtistsByInitialEnabled()` — the same shape as the continuous-play and
car-shuffle pairs beside it.

The row reads **"Show artists by initial"**, sentence case like its neighbours,
added with `addToggle` after Volume leveling and before Sign out; a destructive
terminal action stays last. The internal name follows the label, per the
precedent car-shuffle set.

Writing the preference also calls
`BrowseTreeInvalidator.invalidateNode(ARTISTS_ID, 0)`. That is precisely the case
that function's KDoc was written for: the car caches a browse list and does not
re-fetch it when the user navigates back into it, so without this the tab keeps
whichever shape was current when it was first loaded — and the toggle would read
as doing nothing until the next cold start.

### Why the default is on

#87 is **unreleased** — `v0.99.2` predates it — so no install has ever seen a
window row. The argument that held car-shuffle's default at the shipped behaviour
("an existing install keeps behaving as it did") has nothing to preserve here.
Letters ship as the Artists experience and windows become the opt-out, which is
also the cheaper request, the better label and the shape a driver can scan.

### The endpoint

Two additions to `LibraryService`, alongside `getDecades`:

```kotlin
@GET("library/sections/{sectionId}/firstCharacter")
suspend fun getFirstCharacters(
    @Path("sectionId") sectionId: String,
    @Query("type") type: Int
): PlexResponse

@GET("library/sections/{sectionId}/firstCharacter/{key}")
suspend fun getFirstCharacterContent(
    @Path("sectionId") sectionId: String,
    @Path("key", encoded = true) key: String,
    @Query("type") type: Int,
    @Header("X-Plex-Container-Start") start: Int,
    @Header("X-Plex-Container-Size") size: Int
): PlexResponse
```

The index takes no paging, for the reason `getDecades` takes none: the response is
bounded by the number of distinct initials in a library. `LibraryClient` wraps
both in `plexCall` and always passes `PlexItemType.ARTIST`.

`Directory` gains a nullable `size` for the bucket count. `directoriesOf` already
narrows a container to its `Directory` entries with no `type` filter — written
that way for decades, which carry no `type` either — and is reused unchanged.

### The tree

`ConstantsAA.ARTIST_LETTER_ID = "[artistLetterID]"`, the remainder being the
bucket `key` verbatim, encoding and all.

`MediaBrowserTree.getChildren(ARTISTS_ID)` branches on the preference between
`getArtistLetters` and today's `getArtistWindows`. The letter prefix is tested
**before** `ARTIST_ID`, for the reason the window prefixes already are: no group
id is a prefix of an item id, and keeping the narrower match first means that
stays true by construction rather than by coincidence of spelling.

The Artists node stays `browsableChildrenAsGrid = false` under **both** settings.
That is a deliberate simplification rather than an oversight: `buildTree` runs on
`onGetLibraryRoot`, before any library has been queried, so a style that depended
on the setting would need the root invalidated and visibly re-rendered every time
the row was toggled. A one-character label needs no width, so a list costs it
nothing.

`onGetItem` answers `found=false` for a letter id, exactly as it already does for
a window id — the browse header still renders correctly, so this is the same
recorded limitation rather than a new one.

### The repository

```
getArtistLetters(letterPrefix, artistPrefix)   the Artists tab, setting on
getArtistLetter(bucketKey, artistPrefix)       one bucket's artists
```

A bucket's contents are fetched at `start = 0, size = MAX_ITEMS` with **no
`sort`** — the measured reason above, restated in the KDoc, because the adjacent
window functions pass `SORT_DISPLAY_TITLE` and the disagreement will otherwise
read as an oversight.

Both functions are section-scoped and open with the same `sectionKey ?:
errorFuture()` guard every other browse node uses, so a request arriving with no
library chosen is the existing `ERROR_PERMISSION_DENIED` rather than a new
failure mode.

Rows are built by the existing window-row mapper: it already sets
`browsableChildStyle(true)`, so a bucket's artists render as a grid of real
artwork, which is what they should be. Each row carries the tab's own
`ic_aa_artists` drawable for the reason #87 gives — an absent `artworkUri` makes
the car draw a music note on a per-row colour, and 27 unrelated colours compete
for a driver's attention where one repeated glyph says the same thing quietly.

**The mapper is renamed** `windowRowToMediaItem` → `groupRowToMediaItem`. It now
serves two kinds of row and its name should not claim one of them.

### `fetch` grows a suspend map, and a documented hazard goes away

#87's `windowed()` hand-copies `resultFor`'s HTTP/Unreachable routing, and both
carry a KDoc warning that the two deciders must be kept in step. It had to: it
needs the head *response* for `totalSize`, not merely its `Left`/`Right`.

Making `fetch`'s map lambda `suspend` gives it exactly that, so `windowed()`
collapses into `fetch()` and the duplicated routing — plus the warning about
keeping it in step — is deleted rather than copied a third time for letters.
This is the reason the letter path introduces no error handling of its own.

### Small libraries stay flat

`windowed()` returns artists flat when the total is ≤ `WINDOW_SIZE`. Letters do
the same: the index's counts sum to the library total, so the shape is decided
from data already in hand, and below the threshold one further request returns
the flat list. Five letter rows for twenty artists is a worse tab than a list of
twenty, and this keeps a small library behaving identically under both settings.

The cost is one extra round trip, on small libraries only — the case where the
second request is smallest. `getArtistLetter` is not involved, so nothing else
pays for it.

**If that second request fails, the tab falls back to the letter rows** rather
than to an error or an empty list. They are already in hand from the index and
cost nothing to build, and they are a working tab: every artist is still two taps
away. This is `titleAt`'s reasoning — a degraded label is worth more than a
failed tab — applied one level up, and it is what keeps the letter path free of
error routing of its own. The alternative, raising the failure, would have to
choose between reporting a 401 as "unreachable" and losing the sign-in
affordance, or dropping the whole tab over a request the index had just proven
the server was answering.

### Rows carry their count on the second line

A letter row's title is the bucket's `title` verbatim — "A", "#", "∆". These are
server-supplied and already display-ready, so nothing here is localised, cut with
`shortened()`, or derived.

The second line — the same line an album uses for its artist, and the one the
signed-out row already uses for its hint — carries the bucket's count, "79
artists". This costs a `plurals` resource rather than a plain string, in all five
locales, so the string budget for this feature is **one string plus one plurals
resource, times five**. `lintDebug` must be checked for new `MissingQuantity` and
`ImpliedQuantity` errors against the documented nine-error baseline.

The count is also the only honesty available about the truncation accepted below:
a bucket reading "400 artists" over a list that stops near 293 is at least
diagnosable, where a bare "S" is not.

## Bounds and what is not covered

**A bucket over roughly 293 artists truncates silently, and this is accepted
rather than fixed.** The counts needed to detect it are in hand — the index
carries every one of them before a row is drawn — so a guard that fell back to
windows for the tab would have cost one comparison. It was put to the user with
that trade-off stated and declined in favour of the smaller diff. The setting is
the escape hatch, and it is worth being plain that this reintroduces, for a
sufficiently lopsided library, the same class of silent wall
[#83](https://github.com/codingismy11to7/siskin/issues/83) was filed about. The
largest bucket in the reference library is 133 against 1204 artists, so reaching
it takes a library concentrated far more tightly than this one, not merely a
larger one.

Two mitigations already in the design reduce what that costs: the count on each
row makes the shortfall visible to anyone who looks, and turning the setting off
reaches every artist. Windowing *inside* an oversized bucket is the fix if it
ever bites — bucket paging is measured working above, and the id would grow to
carry `(key, start)`.

Also not covered:

- **Albums.** `getAlbumWindows` and `getAlbumWindow` are untouched and window
  unconditionally. B = 350 and S = 315 are over the ceiling, and offering the
  setting there would ship a shape that silently drops the tail of the two
  largest letters in an ordinary library rather than a lopsided one.
- **Composite artwork for a letter.** #90 left behind machinery general enough to
  reuse — `CompositeGrid`, `CompositeArtBucket`, `CompositeBuildLocks` — and a
  grid of tiled covers would turn 27 rows into 9. It is deliberately a separate
  issue: it needs its own provider path, and the bucket keys `%23` and `∆` need a
  filename guard that the decade path's `\d{4}` rule does not provide. Flipping
  the tab to a grid would also reintroduce the preference-dependent styling this
  design avoids.
- **Everything #87 left capped.** Playlist, artist, album and decade track
  listings, and search, are all still bounded by `MAX_ITEMS` and can still
  truncate silently.

## Alternatives rejected

**Letters for both tabs.** Album buckets B = 350 and S = 315 are over the
ceiling, re-measured on this branch. This is #87's original objection and it
survives; only its scope narrows.

**Bucket displayed initials in-app.** Gives exact displayed-initial filing and
sidesteps every `titleSort` surprise. Costs 5.19 MB and 0.64 s per tab entry, and
needs an in-memory artist list to serve a letter's contents afterwards.

**Cumulative offsets from the bucket counts**, into a `sort=title` listing, as the
earlier document floated. It cannot work: the counts partition by `titleSort` and
the listing would be ordered by `title`, so every offset but the first would point
into the wrong place. The bucket path removes the arithmetic entirely.

**`sort=title` inside a bucket.** Measured to open bucket D on A, B, B, C.

**A guard falling back to windows on an oversized bucket.** Declined by the user;
see the bound above.

## Comments that stop being true

Per this repository's convention these document real hazards and are corrected
rather than deleted:

- **`PlexBrowseRepository`'s "windowed browse" section comment** — "a list too
  long to browse becomes a list of ranges". It becomes ranges *or* buckets, and
  the sentence about paging never coming from the car is unaffected.
- **`resultFor` and `windowed`'s paired "second decider / keep the two in step"
  warnings** — deleted with the duplication they describe, once `fetch` takes a
  suspend map.
- **`MediaBrowserTree.buildTree`'s grid-versus-list comment** — "Artists and
  Albums became lists when they started serving window rows" needs the letter
  case, and needs to say that the Artists node is list under both settings on
  purpose.
- **`LibraryClient.SORT_DISPLAY_TITLE`'s KDoc** — its argument is about windows
  being named after edge items and stays true of windows. It should say it
  describes one of two shapes, so the letter path's bare default sort does not
  read as a call site that forgot.
- **`LibraryService`'s header KDoc on paging** — "These headers exist for the
  windowed browse tree instead" gains the bucket listing, and should note that
  the index itself takes no paging.

## Testing

Reusing `PlexBrowseTestServer`, extracted for #87's suite.

- **`PreferencesArtistsByInitialTest`** — the key defaults to true and
  round-trips.
- **The bucket path is `/firstCharacter/%23`, not `%2523`.** This is the one
  defect here that would ship silently and look like an empty symbol bucket, so
  it carries the suite's weight. `∆` gets the same treatment, asserted through the
  recorded request rather than through a mapper.
- **The query form is never sent.** No request on this path may carry
  `?firstCharacter=`, because the server answers it 200 with the whole library.
- **No `sort` on a bucket request**, and `type=8` on both calls — the latter
  asserted positively, since omitting it happens to work on a music section and
  would pass any test that only checked the response.
- **`Start`/`Size` are present** on the bucket request and absent from the index.
- **Rows** carry the bucket title verbatim, the id prefix plus the raw key, the
  artists icon, and the count on the second line — including the singular form,
  which a bucket of one (`∆`) makes a real case rather than a hypothetical.
- **A small library returns artists flat**, and the same library returns letter
  rows once it crosses `WINDOW_SIZE`.
- **`MediaBrowserTreeTest`** — `ARTISTS_ID` routes to letters with the preference
  on and to windows with it off; a letter id routes to the bucket; the letter
  prefix matches before `ARTIST_ID`; and the node reports **list** style under
  both settings, which is what would catch someone later making it conditional.
- **`PlexSignInSettingsTest`** — the row is present with its label, its switch is
  `isClickable == false`, and tapping the row writes the preference.
- **#87's window tests must pass unchanged.** They are the regression suite for
  the opt-out path, and `fetch` growing a suspend map is meant to be invisible to
  them.

Every test touching the new key resets it in `@Before`: Robolectric caches
`SharedPreferences` statically across test methods and across classes in a JVM
fork, so a test assuming absence otherwise reads whatever ran before it.

## Verification in the car

Not yet done, and the design is not finished until it is:

- The letter list renders as 27 list rows with the artists icon, and the second
  line shows the count. The second line is the part most worth checking — it is
  taken on the signed-out row's precedent, which is a *different* node.
- Tapping a letter opens a grid of that letter's artists.
- `#` and `∆` both open correctly, which is the percent-encoding path end to end.
- Toggling the setting while sitting on the Artists tab redraws it, which is what
  `invalidateNode` is there for and the one part of this that depends on the car's
  subscription behaviour rather than on our code.

Landscape only, on whatever AVD is already running. Each variant is its own AVD
and switching tears down whatever is on it, so portrait is left for when it is
asked for.
