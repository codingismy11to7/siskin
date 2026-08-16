# Composite artwork for the Discover rows

**Date:** 2026-08-16
**Status:** Approved
**Issue:** [#114](https://github.com/codingismy11to7/siskin/issues/114)

## Context

[Discover](2026-08-14-hubs-browse-design.md) shipped as a list whose rows carry
no `artworkUri` at all, so the car draws its own placeholder — a music note on a
colour it picks per row. Ten of those in a column carry less information than
the covers behind them would, and that is the whole reason this is worth doing.

That document deferred artwork to this one, exactly as the decades design
deferred [#84](https://github.com/codingismy11to7/siskin/issues/84). Neither
earlier document is edited here; both are the historical record of what they
decided.

**The parallel with #84 stops at the tile.** Decades became a grid when their
composites landed. Discover does not, and this design does not get to revisit
that — see *What deliberately does not change*.

## Why we tile it ourselves

Plex has no composite for a hub, the same way it has none for a filter value.
Measured against PMS 1.43.3, a `Hub` carries exactly

    Metadata, context, hubIdentifier, hubKey, key, more, size, style, title, type

— no `thumb`, no `art`, no `composite`. There is no object behind a hub to have
artwork; it is a proposition the server composed.

## What is cheaper here than it was for #84, and what that buys

**The source material arrives free.** A decade row needed one extra request per
decade to pick its covers — #84 measured ~0.57 s for eight decades, paid on
every entry to the node. A hub listing already returns six items per hub *with
their thumbs*, because that is what the row's existence is decided from, so a
Discover listing has everything ten composites need inside the one ~125 KB
request it already makes.

That saving is not the interesting part. **The interesting part is that six
candidates can ride the URI, and four covers cannot fail the tile.**

The decade path picks its four covers, fetches them, and fails the whole tile if
any one of the four fails to load:

```kotlin
if (covers.size != cells.size) return null
```

Its `OVER_FETCH = 8` does not help, because the spares are discarded *before* the
fetch — the over-fetch absorbs a thumb-less album, not a failed load. Carrying
the whole candidate pool instead of the chosen four means two loads can fail and
the row still wears a full 2×2. This design takes that improvement into the
shared core, so the decade path gains it too; see *The refactor*.

`?count=` is **not** a way to ask for a bigger pool. Each distinct `count` value
is an independently re-rolled hub set — the hubs design measured a vault hub
returning six items at the default and **zero** at `count=30` — so raising it to
fill a nine-cell tile would change which hubs appear and what is in them. Six is
the pool ceiling, which is another reason the grid stays 2×2.

## What gets built

### The URI carries the covers, the library and the hour

```
content://us.codingismy11to7.siskin[.debug].albumart.provider/hubArt/abc123-4/487234/<pool>
                                                              └path┘ └scope─┘ └bucket┘ └pool┘
```

`pool` is up to `POOL_MAX` — six, the listing's own item count — server-relative
Plex thumb paths in **one** path segment, comma-joined.
`scope = CompositeArt.scopeOf(session)` and `bucket = nowMs / 3_600_000`, both
the same values and the same definitions the `decadeArt` path uses.

A comma because a Plex thumb path does not contain one: they are
`/library/metadata/{ratingKey}/thumb/{timestamp}`, digits and slashes. The
delimiter is not load-bearing for safety in either direction, which is the
property to keep. A caller who sends commas gets a split into components that
fail `isServerRelativePath` and a refused URI; a genuine thumb that somehow
carried one would corrupt its own pool, be refused the same way, and cost that
row its tile and nothing else.

One segment rather than one segment per thumb, because the pool is variable
length and `UriMatcher` has no repeating wildcard. The alternatives were six
near-identical `addURI` rules — which works, but makes the cap on how many
covers a caller may demand emergent from the rule set rather than stated in
code — and query parameters, which read best of the three and were rejected for
being unproven: nothing here shows query parameters survive the media3 → car
image-loader round trip, and a wrong guess there is artwork that silently never
appears. A percent-encoded multi-segment path is proven, because `openAlbumArt`
has shipped on exactly that mechanism — `appendPath` encodes the separators and
`getPathSegments()` decodes them, so a Plex path arrives whole rather than
truncated to its last component.

Minting and splitting the pool live in one pure helper, so the delimiter
convention has a single home and a unit test rather than two call sites that
have to agree.

### The bucket stays, and its job here is not the job it does for decades

A `decadeArt` URI names a random draw, so without a bucket it would be constant
forever and the car's own image cache could pin a tile for the life of the
process. A `hubArt` URI names its own covers: it is content-addressed, and it
already changes exactly when the server's picks change. On that argument alone
the bucket is redundant.

It is kept for a different reason. **A degraded tile has to be able to heal.**
If a cover fails to load transiently, the tile drawn is a smaller one — four
candidates becoming one full-bleed cover — and the car then caches *that* under
a URI nothing invalidates. With the bucket, the next hour mints a fresh URI and
the row redraws. The cost is an hourly rebuild of a byte-identical image: four
Glide loads served from Glide's own disk cache and one JPEG encode.

Two consequences of keeping it are worth stating rather than discovering.
`CompositeArtBucket`, `evictStale` and the `isLive` guard all carry over
unchanged. And the hourly churn hands the car N change events across an
unchanged-size list, which is the shape `DecadeKey`'s KDoc already establishes as
safe — the shape that crashes `BrowseAdapter.onBindViewHolder` is a change
*alongside* a removal.

### Flow

```
PlexBrowseRepository.getHubs(prefix)              one request, unchanged
   └─ PlexMediaMapper.hubToMediaItem(hub, prefix, scope, bucket)
         pool = hub.metadata.mapNotNull(::artworkThumb).distinct().take(POOL_MAX)
         artworkUri = AlbumArtContentProvider.hubContentUri(scope, bucket, pool)
                      an empty pool mints no artworkUri at all

the car opens that URI
   └─ AlbumArtContentProvider.openHubArt         Java, thin: validate + delegate
         ├─ hit:  ParcelFileDescriptor.open(cached, MODE_READ_ONLY)
         └─ miss: HubCompositeArt.build(context, pool, bucket)
                    load the pool in order until four covers land
                    draw into 512×512, compress JPEG, write, rename, stream
```

`.distinct()` is not defensive, it is load-bearing: `artworkThumb`'s
parent-thumb fallback means every item in a hub can resolve to the *same*
cover whenever its items share a parent -- several albums by one artist,
"More by Just Surrender" measured at three. Without it the tile draws that one
cover four times, which reads as a rendering bug rather than a mosaic. Deduping
first means such a hub mints a pool of one, and the greedy pick already knows
what to do with that: one candidate, one full-bleed cell, the honest picture of
what the hub actually has to show.

Scope and bucket are computed once per browse and passed into the mapper rather
than read from a clock or a session inside it, for the reason the decade path
does the same: the mapper stays a pure function, and every row in one listing
agrees about both.

**No Plex request appears anywhere on this path.** The decade path's
`getSectionContent` has no counterpart here.

### The cache

```
cacheDir/composite-art/{machineIdentifier}-{sectionKey}-{hash}-{bucket}.jpg
```

`hash` is the first 16 hex characters of a SHA-1 digest over the joined pool.
A digest rather than an identifier because the pool is variable length and
cannot be a filename, and rather than `hashCode()` because this provider is
exported and a 32-bit collision is craftable — the impact would only be one
wrong tile, but a digest costs nothing to prefer. It is computed from the
*already-validated* pool, which gives this path a property the decade path has
to buy with a `\d{4}` rule: **nothing caller-shaped reaches a filename at all**,
so there is no charset guard to get right. `MessageDigest` is plain JVM, so the
naming is testable without Robolectric.

The directory is renamed `decade-art` → `composite-art` so one `evictStale`
sweep covers both kinds. That orphans whatever sits in `decade-art` on an
already-installed device: at most ~16 small JPEGs, in a system-evictable
`cacheDir`, never swept again. Deleting the old directory once was considered
and judged not worth three lines of code that would run on every successful
build forever.

Two hubs whose pools happen to be identical share one file, which is correct —
the same six covers make the same image.

## The refactor

`DecadeCompositeArt` is keyed on a decade and fetches its own covers. The split
is by *where the covers come from*, and nothing else:

```
CompositeArt            scopeOf, currentScope, cacheIdentifier
                        cacheDir, cacheFile, evictStale
                        loadCover, the locked build body
  ├─ DecadeCompositeArt fetches its covers   (LibraryClient, coverThumbs)
  └─ HubCompositeArt    receives its covers  (from the URI)
```

Everything with a hard-won comment moves into the core intact: the partial-file
rename, the `compress` return check that a full disk reports by returning false
rather than throwing, the orphan cleanup on a failed rename, and the recycle on
every exit.

### The cover source is a lambda, and that is load-bearing

```kotlin
fun build(context, session, id, bucket, covers: () -> List<String>): File?
```

If `DecadeCompositeArt` fetched its thumbs and handed the list to a shared core,
`CompositeBuildLocks` would no longer sit around the metadata query — and
collapsing N concurrent opens of one missing tile into one Plex request is the
entire reason that lock exists. Evaluated inside the lock, the decade path keeps
exactly the behaviour it has today. Hubs pass `{ pool }`; the lambda costs them
nothing and reads the same.

The re-check after acquiring the lock, against the same session snapshot the
lock key was built from, is unchanged for both.

### The greedy pick

Cells are chosen from how many covers *landed*, not from how many thumbs exist:

```
load the pool in order, stopping at four covers
cells(covers.size, SIZE)     4 → 2×2,  1–3 → one full-bleed,  0 → no tile
```

This changes a shipped path, and the change is owned rather than incidental: a
decade whose fourth cover fails now draws a full-bleed tile where it previously
drew nothing. One wrinkle falls out of it — covers are requested at the cell edge
implied by the *candidate* count, so a pool that degrades to full-bleed
re-requests its survivor at the full edge. That is one extra Glide call, from its
own disk cache, on a path that only runs after a load has already failed. A pool
too small to fill the grid in the first place never enters that trade: below
four candidates the layout is a single full-bleed cell no matter what lands, so
the pick asks for one cover at the full edge rather than four, and the re-request
above only arises where a full grid was actually attainable to begin with.

The pick itself is a pure generic function over a loader, so the interesting
behaviour is unit-testable without Glide.

### `scopeOf` stops living on a decade class

`HubKey`, `PlexBrowseRepository` and `AlbumArtContentProvider` all reach for
`DecadeCompositeArt.scopeOf` today for the definition of *which library*, which
is a decade-shaped home for a library-shaped fact. It moves to `CompositeArt`
with the rest of the shared core. The churn is mechanical and touches
`AlbumArtContentProvider`, `PlexBrowseRepository`, `DecadeKey` and `HubKey`'s
KDoc, `CompositeBuildLocks`' KDoc, and five test classes.

There is still exactly one definition of the string, which is the property that
matters: the id a row is minted with, the URI it wears and the guard the
provider applies cannot drift apart.

## The exported boundary

`openHubArt` reads scope, bucket and pool by index, so the matcher rule's arity
is the first guard, as `openDecadeArt`'s is: `hubArt/*/#/*`. Then, in order:

- **The bucket** — `CompositeArtBucket.isLive`, unchanged, including the
  `Long.parseLong` in a try despite `#` already restricting the segment to
  digits, because `#` matches a digit run long enough to overflow a long.

  **Its rationale is narrower here than on the decade path.** There it bounds an
  otherwise unbounded space of Plex requests, since every miss is a query made
  with the user's token. Here there are no Plex requests to bound; it bounds
  cache filenames and gives the tile its hour.
- **The scope** — equality against `CompositeArt.currentScope()`, unchanged,
  carrying the same microsecond-wide window between this read and the cache's own
  that `openDecadeArt` documents and declines to close until the provider becomes
  Kotlin in [#86](https://github.com/codingismy11to7/siskin/issues/86).
- **The pool** — split, then two checks. The count must be 1..`POOL_MAX`, which
  bounds one open to six cover fetches. Then `MediaUrlBuilder.isServerRelativePath`
  on **every** element, where a single failure refuses the whole URI rather than
  filtering the bad entries out. Filtering would let a caller pair one real thumb
  with five probes and still be handed a tile; refusing whole costs real traffic
  nothing, because a pool this app minted is valid in all six positions by
  construction.

### The open-proxy property does not survive, and the comment claiming it must change

`openDecadeArt`'s KDoc records, correctly, that no caller-supplied path reaches
`MediaUrlBuilder.artworkUrl` on that route — the four thumbs come from our own
Plex response, so the hazard `isServerRelativePath` exists to prevent cannot
arise there at all.

**That is not true of `hubArt`**, where the pool is caller-supplied by
construction, and the comment must say so rather than be inherited by a reader
comparing the two.

It is not a new capability. `openAlbumArt` has always let any app on the head
unit fetch any server-relative Plex path with the user's token, behind this same
guard; `hubArt` grants nothing `albumArt` did not. What is new is amplification —
one open triggering six fetches instead of one — which the count cap exists to
bound. `artworkUrl` re-validates each path independently, so every thumb is
checked twice.

The residual runs the *opposite* way from the decade path's. A well-formed but
absent decade costs one Plex query per open forever, because the build returns
null before writing anything and leaves nothing cached to answer the next
request. A hostile `hubArt` open costs Glide fetches and no Plex metadata request
at all.

## Error handling

Every failure lands on `FileNotFoundException` and therefore on the car's own
placeholder, which is what hub rows draw on `main` today. **No failure mode here
is worse than not having shipped the feature.** That covers no session, a foreign
scope, a stale bucket, a malformed pool, every cover failing to load, `compress`
returning false on a full disk, and a failed rename.

One case from the decade path disappears rather than being handled: there are no
Plex API calls on this route, only Glide fetches of transcode URLs, so the
question of whether a 401 should raise the sign-in affordance does not arise.

Data-saving mode is honoured through the shared `loadCover`, and remains
unreachable while the preference is frozen at false.

## Comments that must change with the code

The project treats a *why* comment as load-bearing, and several of these
currently assert the opposite of what will ship:

- **`PlexMediaMapper.hubToMediaItem`'s KDoc** — "No artwork of its own: a hub row
  is a proposition". Its no-artwork reasoning goes; its **no-grid** reasoning is
  the issue's central constraint and must be restated rather than deleted.
- **`AlbumArtContentProvider.openDecadeArt`'s** open-proxy paragraph, per above.
- **`CompositeGrid`** ("in a decade composite"), **`CompositeArtBucket`** ("the
  hour window a decade composite belongs to") and **`CompositeBuildLocks`**
  (which names `DecadeCompositeArt.build`), all of which now serve two callers.
- The `scopeOf` and `isSafeCacheIdentifier` references in **`DecadeKey`** and
  **`HubKey`**.

## What deliberately does not change

**`browsableChildrenAsGrid` stays `false` on the Discover node, permanently.**
This design does not get to revisit it, and `MediaBrowserTreeTest` pins it. A
decade row is recognisable from four covers — "the eighties" is a look — while a
hub row is a sentence: nothing about four covers distinguishes "Haven't played in
5 months" from "Most Played in April", and the covers would be the *answer* to
the row rather than a picture of it. Localisation sharpens it, since the titles
are server-supplied and vary in length by locale, so a grid's caption width would
be a constraint the app neither controls nor can test in four of its five
languages. The tile decorates a list row; it does not promote the list to a grid.

That rule governs Discover's own rows and nothing below them. A hub's contents
are albums and artists, which grid — that was misread once and fixed in
[#113](https://github.com/codingismy11to7/siskin/issues/113).

**Hub row ids are untouched.** They stay `HUB_ID + HubKey.of(scope, key)`, with
the bucket deliberately *not* in the id: putting it there would churn every row
hourly and stale every persisted id. This is the id/URI split `DecadeKey`'s KDoc
documents a crash over, arrived at from the other direction.

**The grid stays 2×2**, and `CompositeGrid` carries over with no change to
`cells`. Four covers from a pool of six; a nine-cell tile is not coverable and
`?count=` cannot be used to make it so.

**`CompositeGrid.SIZE` stays 512, and the screen does not get a vote.** The
composite is generated at 512×512 — 256 px a cover cell — to match
`AlbumArtContentProvider.DEFAULT_ARTWORK_SIZE`, so a composite costs the car no
more than an album cover does. That is the parity worth keeping, and it is the
reason to leave the number alone rather than tune it per surface.

**What a given head unit happens to draw it at is not a design input**, and
saying so is the point of this paragraph, because getting it backwards is easy.
Measured on the landscape AVD, a Discover row's thumbnail is 112 px square,
against the ~260 px #84 measured for a *grid* tile — so a cover cell lands at
56 px there and cover text is gone, which a capture at 4× confirms: only very
large type survives. That number describes a 1024×768 mdpi profile, not this
feature. The app already generates roughly 4.5× what that screen asks for, a
denser head unit draws the same file larger, and shrinking the output to suit
the smallest surface would be the actual mistake.

#84's 87 px figure is not a threshold this has to clear either. It was the
argument for choosing 2×2 over 3×3 *at one output size* — nine cells of one
512 px composite are illegible where four are not — and it says nothing about
how small a screen may render the result.

So the bar on this row is what it always was: **the placeholder, not cover
legibility.** Ten identical music-note glyphs distinguish nothing. Four covers
from the hub's own contents distinguish one row from the next at 56 px, and read
as a collection rather than as a single album, which is information a lone cover
would not carry.

**Nothing else moves.** No new Plex requests, no new endpoint, no `?count=`. No
new strings and no new drawables, so the five-locale rule costs nothing and
`MissingTranslation` stays at zero. No paging and no caching of the hub listing.
`AlbumArtContentProvider` stays Java — #86 is untouched. No Room, no prefetch, no
WorkManager.

## Testing

Leaning on the seams rather than on drawing, because Robolectric's `Canvas` and
`Bitmap` shadows produce no pixels and, under `returnDefaultValues`, an assertion
on drawn output would assert nothing while appearing to pass.

- **The pool round trip**, plain JUnit: six thumbs, one thumb, a thumb containing
  the delimiter, and more than `POOL_MAX`.
- **The greedy pick**, as a pure function over a stub loader — the new behaviour,
  and the one most worth pinning. Six candidates with two failures still yield
  four covers and a 2×2; four failures degrade to one full-bleed; every failure
  yields no tile.
- **`hubToMediaItem` mints a URI** carrying scope, bucket and pool, asserted in
  `PlexMediaMapperAssemblyTest` — the Robolectric suite — because `Uri.Builder`
  returns null under `returnDefaultValues` and the plain suite would be comparing
  null to null. With it, the #84 regression shape: two scopes, two buckets or two
  pools must not mint one URI. And an empty pool mints no `artworkUri` at all.
- **The provider guards, in both directions.** Refused: a stale bucket, a foreign
  scope, an absolute URL in the pool, a backslash in the pool, and a pool of
  seven. Served: the current scope against a cache file the test wrote, asserted
  on `statSize`, because a guard that refused everything would pass every refusal
  case while blanking every tile in the car. Served *also*, and this one is the
  interesting case: `%2f..%2f..%2fevil`, which `getPathSegments()` decodes to
  `/../../evil` **after** the matcher has accepted it. `isServerRelativePath` does
  not reject `..` and never has — `openAlbumArt` accepts the same shape today, and
  the hubs design measured `..` traversal among 23 candidates that do not escape
  to another host, so refusing it here would buy nothing a caller cannot get by
  naming the target path directly. The guarantee this route actually makes is the
  stronger one, and the test asserts that instead: a hostile pool resolves to a
  hex-named file inside the cache directory or to nothing, because the cache id is
  a digest computed locally rather than a segment copied out of the URI.
- **Cache naming and eviction:** one file per (pool, scope, bucket), a different
  pool giving a different file, the two live buckets surviving a sweep, older
  ones not, and files this app did not name left alone.
- **`MediaBrowserTreeTest`:** the Discover node still reports a list. That
  assertion is this issue's central constraint and outlives it.
- `lintDebug` stays clean, with no new `MissingTranslation` because there are no
  new strings.

## Verification in the car

Done on the landscape AVD — API 33, `automotive_1024p_landscape`, 1024×768 mdpi
— by browsing More → Discover:

| | measured |
|---|---|
| Discover row (`item_container`) | 800 × 116 px |
| row thumbnail (`thumbnail`) | 112 × 112 px |
| cover cell at 2×2, as drawn | 56 px |
| cached composite | 76 KB |

The row draws a 2×2 of four distinct covers rather than the music-note
placeholder, the layout is a list, and the cache file lands under the name this
document specifies — `{machineIdentifier}-{sectionKey}-{hash}-{bucket}.jpg`,
with a 16-character hex hash. Nothing warned in logcat, so no cover fell back.

**Only one row appeared, and that is the documented ordinary case rather than a
defect.** The hubs design measured that a server whose play history is untouched
returns six hubs with five of them empty; "Recently Added in Music" is the one
that needs no history to fill. Seeing the ten-row case needs a server with real
listening behind it.

The portrait variant is still unverified. Each variant is its own AVD and
switching tears down whatever is running, so it is not a step to take
unprompted. Portrait is 800×1280 at ldpi, a different pitch entirely, and by the
argument above that changes what is drawn rather than what is generated.
