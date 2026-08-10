# Composite artwork for the Decades grid

**Date:** 2026-08-09
**Status:** Approved
**Issue:** [#84](https://github.com/codingismy11to7/siskin/issues/84)

## Context

[Decades in the More tab](2026-08-09-decades-browse-design.md) shipped the node
as an art-less list, and said so: its rows carry no `artworkUri`, so the car
draws its own placeholder — a music note on a colour picked per row, a colour
that means nothing. That document deferred artwork to this one and left
`browsableChildrenAsGrid = false` with a comment naming the condition for
flipping it.

That earlier document is the historical record of the decision it made and is
not edited here. This one supersedes its "no per-decade artwork" section.

## Why we tile it ourselves

Plex has no composite for a filter value. Measured against PMS 1.43.3 on the
reference library, per issue #84:

- `/library/sections/{key}/decade?type=9` returns `Directory` entries carrying
  only `fastKey`, `key` and `title` — no `thumb`, no `composite`, no `art`.
- `/library/sections/{key}/decade/{decade}/composite` and
  `/library/sections/{key}/all/composite?decade=…&type=9` both **404**.
- `/library/sections/{key}/composite/1` does return a JPEG, but it is a
  **section-wide** mosaic — byte-identical for every decade, so it cannot
  distinguish the 1960s from the 2010s.

Collections do carry a `composite`, but that is a Plex-side artifact of a
collection being a real object. A decade is a filter value; there is no object
behind it to have artwork.

## What already exists, and why that decides most of this

`AlbumArtContentProvider` is not a new mechanism to build — it is the mechanism.
It is already exported, already the thing the car opens for every album, artist
and playlist tile, and already does the hard part: `openFile` returns the read
end of a `ParcelFileDescriptor.createPipe()` immediately while a pooled
background thread fetches through Glide and copies bytes down the write end.

So the issue's first open question — "where does a synthesized bitmap live given
media3 hands the car a `Uri`?" — has an answer that predates the question. A
composite is a second path on that authority. `artworkData` on the metadata (a
size-capped byte array in every browse response) and a bare `file://` URI were
both considered and dropped: one inflates every `onGetChildren` payload, the
other gives up the process-boundary control that makes the provider's validation
meaningful.

## The rule

**The tile is a cache with a one-hour life, not a query.**

Plex offers no random seed — `sort=random:12345` is accepted and ignored,
measured on PMS 1.43.3 — so a client-side cache is the only way to pin a draw at
all. That constraint turns out to be the design: one `sort=random` draw per
decade per hour gives an unbiased sample *and* a stable image, which querying
alone cannot.

Two alternatives were rejected:

- **Fresh draw every visit.** The Decades node is re-fetched on every entry to
  the tab (measured in the decades design doc), so this means four cover fetches
  and a decode-and-tile every time, and the tile never becomes the decade's
  identity.
- **A deterministic query with no cache** — stable sort, first four albums.
  Simplest, nothing to invalidate, but the tile would permanently be the
  alphabetically-first albums of that decade. That is the same *consistently*
  incomplete failure the decades doc rejected for track listings.

One hour rather than a day because artwork that never changes is the thing being
fixed. A morning commute and an evening one land in different buckets; two drives
in one day under a 24-hour TTL would not.

## What gets built

### The URI carries a time bucket, and that is load-bearing

```
content://us.codingismy11to7.siskin[.debug].albumart.provider/decadeArt/1980/487234
                                                              └ path ┘ └dec┘ └bucket┘
```

`bucket = nowMs / 3_600_000`.

A URI that named only the decade would be constant forever, and the car's own
image cache could pin the tile for the life of the process — the TTL would exist
in our code and never be observable in the car. Rolling the bucket changes the
URI, which invalidates every cache in the chain at once without any of them
needing to know a TTL exists.

The bucket is computed at browse time and passed into
`PlexMediaMapper.decadeToMediaItem(directory, idPrefix, bucket)` rather than read
from a clock inside it, so the mapper stays a pure function — the same reason
`MediaUrlBuilder` uses `java.net.URLEncoder` over `android.net.Uri`.

### The provider picks the covers, lazily

The alternative was to pick at browse time and encode the four thumb paths into
the URI, leaving the provider dumb. It was rejected for two costs. The Decades
list would go from ~0.14 s to ~0.57 s on *every* entry (per issue #84's
measurements: ~0.06 s per decade after the first, eight decades, one pooled
connection), cached or not, because the repository cannot know a composite
already exists. And a one-hour-stable URI would force the repository to
reproduce the same draw for a whole hour, which means a draw cache *on top of*
the image cache. Two caches for one artifact.

Picking in the provider keeps the browse list at one request and leaves exactly
one cached artifact. Work happens only for a decade the car actually draws.

Prebuilding all eight on a WorkManager schedule was also rejected: it spends
requests on decades nobody browses, and there is no latency problem to solve —
the car renders a placeholder and swaps artwork in asynchronously already.

### Flow

```
PlexBrowseRepository.getDecades(prefix)          one request, unchanged
   └─ PlexMediaMapper.decadeToMediaItem(dir, prefix, bucket)
         artworkUri = content://…/decadeArt/1980/487234

the car opens that URI
   └─ AlbumArtContentProvider.openFile          Java, thin: validate + delegate
         └─ DecadeCompositeArt                  new, Kotlin
              ├─ cacheDir/decade-art/{section}-{decade}-{bucket}.jpg exists?
              │     └─ ParcelFileDescriptor.open(file, MODE_READ_ONLY)
              └─ miss:
                   GET /library/sections/{k}/all?type=9&decade=1980&sort=random
                       X-Plex-Container-Size: 8
                   first 4 entries with a thumb
                   Glide asBitmap, 256×256 each
                   draw into 512×512, compress JPEG, write cache file, stream
```

### `decade=`, not `album.decade=`

`LibraryService.getSectionContent` already has a `decade` parameter, bound to
`@Query("album.decade")`. That spelling is correct for **tracks** and wrong here:
a track's decade belongs to its parent album, an album's decade is its own field.
This needs a **second** query parameter, not a reused one.

The evidence is the server's own `fastKey`, which the decade listing returns
alongside each entry: `/library/sections/4/all?decade=1980&type=9`. Plex is
telling us the spelling. (This is inference from that `fastKey` plus the
type=10 measurements in the decades doc, not a separate probe of
`type=9&album.decade=`.)

Concretely, `getSectionContent` carries both, and the existing one is renamed so
a call site cannot pick the wrong one by reaching for the obvious name:

```kotlin
@Query("album.decade") trackDecade: String?,   // was `decade` — tracks only
@Query("decade")       albumDecade: String?    // new — albums only
```

Paging is unchanged: the over-fetch is `start = 0, size = 8` on the existing
`X-Plex-Container-Start` / `-Size` headers, and `sort` is the existing
`LibraryClient.SORT_RANDOM`.

Getting it wrong is silent in the way the decades doc already documented one
instance of: **HTTP 200 with an empty container**. Here that renders as a decade
whose artwork quietly never appears, which reads as a broken image pipeline
rather than as a malformed query. It earns a direct MockWebServer assertion for
that reason.

The `fastKey` is still not followed verbatim, for the reason the decades doc
gives: it would mean string surgery on a server-supplied URL and would abandon
the typed-client boundary. The typed call reproduces its spelling instead.

### Four covers, in the largest grid that fills completely

- **4 or more albums → 2×2.**
- **1 to 3 albums → the first cover, full-bleed, no grid.**
- **0 albums with a thumb → no artwork**, and the car's placeholder as today.

Four rather than nine, on legibility. A browse-grid tile on the 1024×768
landscape head unit is on the order of 240px — an estimate from the profile's
dimensions, not a measurement — which gives roughly 120px a cover at 2×2 and
roughly 80px at 3×3. At 80px the text on a cover is gone and only dominant
colour survives. Four also matches the shape of Plex's own playlist `composite`,
which this app already renders in the Playlists tab, so the two browse surfaces
agree rather than each having their own idea of what a mosaic looks like.

The sparse rule is one rule rather than a special case. Repeating covers to fill
four cells would claim albums that are not there and reads as a rendering bug;
leaving cells empty looks like artwork that failed to load, which is the one
thing this must never be confused with. A decade with two albums genuinely has
no mosaic, and one cover says that honestly — degrading into exactly what an
album tile already looks like.

**Over-fetch eight, take the first four with a thumb.** Nearly free on the same
request, and it removes the missing-cover case rather than designing a fallback
for it. Issue #84 reports every album on the reference library carries a thumb,
so this is insurance, not an expected path.

### `DecadeCompositeArt` is Kotlin; the provider stays Java

`LibraryClient` exposes suspend functions and `AlbumArtContentProvider` is Java,
which cannot call them — the same wall `PlexScrobbler` exists to cross for
`MediaManager.java`. The bridge is not only a language workaround here: it puts
the whole feature behind one seam that a test can drive without standing up a
`ContentProvider`, and keeps the provider to validation and delegation.

It runs on the provider's existing executor thread, so `runBlocking` around the
`plexCall` is appropriate there. The result is an `Either<PlexTransportFailure,
PlexResponse>` handled like every other call site; no broad catch goes inside an
`either { }` block.

### The tree

One line, as the decades doc promised: `browsableChildrenAsGrid` on the
`DECADES_ID` node flips `false` → `true`.

## The exported boundary

Both path segments are attacker-controlled — any app on the head unit can open
any URI under this authority. The composite path is in one way *safer* than the
existing one and in another way needs a guard the existing one does not.

**Safer:** no caller-supplied path ever reaches `MediaUrlBuilder.artworkUrl` on
this route. The four thumbs come from our own Plex response. The open-proxy
hazard that `isServerRelativePath` exists to prevent — `url=` on Plex's photo
transcoder will fetch an absolute URL on another host, authenticated with the
user's token — cannot arise here. The check still runs on each thumb, as the
defence in depth that function's comment asks for.

**Needs a guard:** two of them.

- **The decade segment must match `\d{4}`.** It becomes part of a cache
  filename, and the two properties that matter are both properties of that
  pattern: **digits only**, so no decoded `/`, no `..` and no separator of any
  kind reaches `DecadeCompositeArt.cacheFile`'s filename interpolation, and
  **fixed length**, so there is no length to play with either. `matches()`
  anchors the whole segment rather than a prefix.

  Narrowing it to `(19|20)\d{2}` was considered and rejected. The smaller
  filename space is not worth anything: nothing is ever cached for a decade
  that yields no albums, so the bogus names are never written, and a caller
  wanting to burn Plex queries can loop the 200 allowed-but-absent values as
  effectively as 10,000. Against that it refuses a genuine pre-1900 decade key
  — an 1890s classical or historical album — which a real library can produce.

  Either way the guard does not by itself absorb a hostile caller, and that
  residual is worth naming rather than overselling: a well-formed but absent
  decade still costs one Plex query per open, because `build()` returns null
  before writing anything and leaves nothing cached to answer the next request.
- **The bucket must be the current one or the one immediately before it.**
  Without this, a caller could walk arbitrary bucket values to force unlimited
  cache misses, and every miss is a Plex request made with the user's token. The
  previous bucket is accepted so the hour boundary is not brittle: a URI minted
  at 10:59:59 and opened at 11:00:01 still draws.

Anything else is refused the way an absent image is — `FileNotFoundException`,
which the car renders as its placeholder.

**Both guards are invoked from `openFile`, beside the album path's own.** That
placement is the point: the two rule sets differ deliberately, and a reader
comparing them should find them adjacent rather than in separate files. The
provider stays Java — a Kotlin rewrite is
[#86](https://github.com/codingismy11to7/siskin/issues/86), deliberately not
folded in here — so each guard delegates to a pure Kotlin helper exactly as the
album path already delegates to `MediaUrlBuilder.isServerRelativePath`. That is
an established shape in this file, not a new one. `DecadeCompositeArt` therefore
receives an already-validated decade and bucket and does no parsing of its own.

One easy thing to miss: **`uriMatcher` is presently dead code.** It is declared
with an `albumArt/*` rule, but `openFile` never consults it — it reads
`getLastPathSegment()` and assumes the album-art shape. A second path is what
makes the matcher load-bearing, and `decadeArt/#/#` needs its own rule. Reading
the decade and bucket off `getLastPathSegment()` would silently pick up only the
bucket.

## The cache

`cacheDir/decade-art/{sectionKey}-{decade}-{bucket}.jpg`.

**A cache hit does no background work at all.** The file check happens before
Glide and before Retrofit, and a hit returns
`ParcelFileDescriptor.open(file, MODE_READ_ONLY)` directly rather than the pipe.
This is worth more than it looks: eight decades scroll into view at once against
an executor sized `max(2, cores / 2)`, and a *build* holds its thread for a
metadata round trip plus four cover fetches while ordinary album art competes for
the same pool. Only the first browse in an hour pays that; every browse after it
is a file handle. The pool is deliberately not resized — that would be tuning
for a cost that lasts one browse per hour.

**Concurrent opens of one tile collapse to one build.** A miss is not idempotent
in cost: until a build renames its file into place, every concurrent open of the
same tile is another cache miss, and so another Plex metadata query plus four
cover transcodes made with the user's token. Builds are therefore serialised on
the `(section, decade, bucket)` triple that names the cache file, with the cache
re-checked after the lock is acquired, so N concurrent opens become one build and
N−1 hits. Per key rather than globally, because the case that matters is eight
distinct tiles missing at once on the first browse of an hour — one lock would
turn that burst into eight sequential round trips. The album artwork path never
needed any of this: Glide's engine already dedups identical in-flight requests
underneath it, and the metadata query is the part Glide knows nothing about.

The lock is a cost fix, not a correctness one. Overlapping builds of one tile
were already safe — each writes a uniquely named partial and renames — and that
stays the backstop for the window where a departing builder and an arriving one
briefly hold different locks for the same key. That window is the price of
removing map entries on the way out, which is what keeps a per-hour key space
from growing for the life of the process.

The section key is in the filename so composites do not survive a library switch
under More → Server Select. Eviction is a sweep on successful build: delete
anything in the directory outside the two live buckets. Steady state is on the
order of sixteen small JPEGs. `cacheDir` is system-evictable, and losing a file
costs one rebuild.

Data-saving mode is honoured exactly as the existing path honours it —
`onlyRetrieveFromCache(true)` on the Glide requests, so a cover that is not
already cached fails the build and yields the placeholder.

## Error handling

Every failure lands on `FileNotFoundException` and therefore on the car's own
placeholder, which is what these rows show on `main` today. **No failure mode
here is worse than not shipping the feature.** That covers: signed out, no music
section chosen, a transport failure on the album request, an HTTP error on it,
and a decade whose albums carry no thumb.

**A 401 on this path deliberately does not raise the sign-in affordance.** A
`ContentProvider` has no route to `MediaLibraryServiceCallback`'s
`PendingIntent`, and needs none: the browse call that produced the list the car
is drawing would have hit the same 401 first and raised it there. Artwork is
downstream of a browse that already succeeded.

## Comments that must change with the code

Both of these currently assert the opposite of what the code will do, and the
project treats a *why* comment as load-bearing:

- `PlexMediaMapper.decadeToMediaItem`'s KDoc, which explains that the row is
  deliberately not built through `browsableItem` because "a decade has neither"
  thumb nor icon, and that the car's placeholder is accepted "because a composite
  is meant to replace it".
- `MediaBrowserTree.kt`'s `browsableChildrenAsGrid = false` comment, which reads
  "Flip to true when composites land."

The reason `decadeToMediaItem` avoids `browsableItem` survives the change, and
should be restated rather than deleted: `browsableItem` falls back to an icon
when there is no thumb, and a decade wants no artwork at all rather than a shared
glyph when its composite cannot be built.

## What deliberately does not change

- **No Room, no prefetch, no WorkManager.** `PlexBrowseRepository` still does not
  touch the database, and the cache is files in `cacheDir`.
- **`AlbumArtContentProvider` stays Java.** Rewriting it in Kotlin is
  [#86](https://github.com/codingismy11to7/siskin/issues/86) and is kept out of
  this branch on purpose: it is ~200 lines of language churn through the one file
  whose diff most needs to be readable, and the part carrying real translation
  risk — `openFile`'s pipe-and-copy body — is the part with no test coverage.
- **The Decades row in More keeps `ic_aa_decades`.** That is the row *for* the
  node, not a decade tile.
- **The decade's own children keep `PLAYABLE_CHILD_STYLE`.** That key describes
  the tracks inside a decade and has nothing to do with how decade tiles render —
  see `BrowseContentStyle`'s KDoc.
- **No new strings**, so the five-locale rule costs nothing here and
  `MissingTranslation` stays at zero.
- **No change to the shuffle row, the browse cache replay, or `isShuffleRow`.**
  Artwork is orthogonal to all of it.

## Testing

Leaning on the seams rather than on drawing, because Robolectric's `Canvas` and
`Bitmap` shadows do not produce real pixels — and with
`unitTests.returnDefaultValues = true`, a test asserting on drawn output would
assert nothing while appearing to pass.

- **The album query is `type=9`, `decade=1980`, `sort=random`**, over
  MockWebServer. The silent-200 trap earns a direct assertion, the same treatment
  the decades doc gave `album.decade`.
- **Hostile segments are refused:** a non-numeric decade, `..`, an empty segment,
  and a two-hour-stale bucket. Mirrors the existing hostile-path test in
  `AlbumArtContentProviderTest`. The segment that actually escapes `cacheDir` is
  `%2f..%2f..%2fevil` — `getPathSegments()` decodes it *after* the `UriMatcher`
  has accepted the segment — so that one carries the test's weight.
- **The decade pattern is pinned in both directions.** Refusals alone would pass
  against a guard narrowed too far, and every other decade test uses `1980`, so
  a typo like `(19)\d{2}` would break the 2000s onward with a green suite.
  Decades outside the 1900s are served positively from cache files the test
  writes, each a different length so `statSize` pins which file was opened.
- **Cover selection:** four or more thumbs yields four; three yields one;
  thumb-less entries are skipped; the request asks for eight.
- **Layout is a pure function** — `cells(count, size)` returning destination
  rectangles — asserted directly: four gives four quadrants, one gives a single
  full-bleed rectangle. It returns a **plain Kotlin data class, not
  `android.graphics.Rect`**, for the same reason `MediaUrlBuilder` uses
  `java.net.URLEncoder` over `android.net.Uri`: `android.jar` is stubbed in unit
  tests, so a `Rect` built there is not reliably the `Rect` it looks like, and a
  test asserting on its fields could pass while measuring nothing. Conversion to
  `Rect` happens at the draw call, which is not unit-tested anyway.
- **Bucket arithmetic** across an hour boundary and within one.
- **Eviction** keeps the two live buckets and deletes older files.
- **`MediaBrowserTreeTest`:** the `DECADES_ID` node reports the grid style.
- **`PlexMediaMapperTest`:** a decade item now carries an `artworkUri`, and it
  round-trips the decade and bucket.

## Verification in the car

The 240px tile figure above is an estimate from the emulator profile's
dimensions, not a measurement, so 2×2 legibility is a claim this design has not
yet proven. Capturing the Decades grid on the landscape AVD, and on the portrait
one, would settle it.

That is a proposal and not a step to take unprompted: each variant is its own
AVD, and switching tears down whatever is running — possibly mid-use or mirrored
over scrcpy. Run it when asked, on whatever AVD is already up.
