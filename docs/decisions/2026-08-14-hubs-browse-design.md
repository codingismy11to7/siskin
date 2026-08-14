# Discover: Plex hubs in the More tab

**Date:** 2026-08-14
**Status:** Approved

## Context

Adds a browse surface that no other node in the fork has: one the *server*
composes. Playlists, Artists, Albums and Decades all answer a question the
driver already knows how to ask. Plex's hubs propose the question.

Root is capped at four tabs and the fourth is More, so this nests there — the
same constraint [the decades design](2026-08-09-decades-browse-design.md) worked
under. It becomes More's first row, above Decades, with Server Select staying
last where it reads as settings.

Composite artwork for the hub rows is deliberately out of scope and gets its own
issue, as Decades' did in
[#84](https://github.com/codingismy11to7/siskin/issues/84). The parallel stops
at the artwork: Decades became a grid when its composites landed, and Discover
stays a list whatever happens to its rows. See *What deliberately does not
change*.

## Why

`LibraryService.getSectionHubs` has existed since the Plex layer was written,
with a client, a model and tests, and **no production caller**. The endpoint was
mapped and then left alone. What follows is what it actually returns, measured
rather than assumed, and it turns out to be worth wiring up.

The draw is that hubs are computed from listening history the app cannot see.
"Haven't played in 5 months" is a genuinely good prompt for a drive and there is
no way for Siskin to compute it — the app has no local play history, and the
data lives on the server.

## What the server already knows

Measured against PMS 1.43.3 on a live music section — 508 artists, 1,322 albums,
12,596 tracks — with real play history behind it. Every figure below comes from
that one server unless it says otherwise.

```
GET /hubs/sections/{key}
```

returns ten hubs in ~0.04 s on a LAN, 125 KB with the default six items each:

| hub | title | size | type |
|---|---|---|---|
| `music.recent.played` | Recently Played Music | 6 | artist |
| `music.recent.added` | Recently Added in Music | 6 | album |
| `music.recent.artist` | More by Just Surrender | 3 | album |
| `music.recent.genre` | More in Pop/Rock | 6 | album |
| `music.popular` | Most Played in April | 6 | album |
| `music.vault` | Haven't played in 5 months | 6 | artist |
| `music.recent.label` | More from Epitaph | 6 | album |
| `music.top.period` | Top Albums from 1993 | 0 | album |
| `music.touring` | Artists on Tour | 0 | artist |
| `music.videos.new` | Recently Added Music Videos | 0 | clip |

### The hub set is itself derived from history

The same request against a server whose play history is untouched returns **six**
hubs, five of them empty — `recent.artist`, `recent.genre`, `popular` and
`recent.label` are absent entirely. The server emits only hubs it can populate.

This matters more than it looks: it means **the feature cannot be designed
against a fixed list of hub identifiers.** Whatever ships must render whatever
arrives, including a set it has never seen.

### Every hub carries a replayable query

Each hub carries `key`, and it is not a preview link — it is the full query
behind the row:

```
music.vault        /library/sections/7/all?type=8&viewCount>=50
                     &lastViewedAt!=-1&lastViewedAt<=-5mon&sort=random
music.recent.genre /library/sections/7/all?type=9&genre=138884
music.popular      /hubs/sections/7/popular?monthsAgo=4
```

Three things follow. The filter grammar is richer than anything the fork
currently sends — comparison operators and relative dates (`-5mon`, `-2w`).
The parameters are **server-rolled**, so a typed client cannot reconstruct them:
nothing in the app knows that this vault means five months rather than nine.
And `music.popular` is a *different endpoint*, so "follow the key" is the only
uniform way to open a hub.

### Stable per request signature, re-rolled by any change to it

Five consecutive identical requests returned byte-identical titles and items.
Change anything about the request and every random choice is drawn again:

| request | popular | vault | recent.label |
|---|---|---|---|
| default | Most Played in April | 5 months, 6 items | Epitaph, 6 items |
| `?count=3` | Most Played in July | 9 months, 3 items | Red Bull Records |
| `?count=30` | 30 items | **0 items** | 2 items |
| `?count=500` | 51 items | 13 items | 32 items |

So `count=` is not a page size, it is a different lottery ticket. This rules out
the design where the listing is fetched at a large `count` and the preview items
*are* the content: at `count=30` the vault came back empty on a server that has
one.

It also means **an empty hub is an ordinary outcome, not an error.** Three
separate causes were observed, all answering 200:

- `music.top.period` picked the year 1993, and the library has nothing rated
  from 1993. The account rates tracks — 122 of them, all at `userRating=10`,
  across 111 albums running 2000–2016 and peaking at 2006 — so the hub is a
  near miss rather than an unsupported feature. The server picks a period
  without checking it is non-empty.
- `music.touring` needs tour tags. `type=8&tagType=306` returns 0 **with the
  recency clause removed**, so no artist carries the tag at all. Not a licensing
  gate: the account is Plex Pass, lifetime, active.
- `music.videos.new` wants music videos, of which there are none — and which
  Siskin could not play anyway.

### Titles are localised by the server, and so is the content

Siskin sends no language header today, so hub titles arrive in English whatever
the car's locale. Sending one fixes that, for all five of Siskin's locales, with
correct grammar and plurals:

| locale | `music.vault` |
|---|---|
| en | Haven't played in 2 years |
| de | Keine Wiedergabe seit 4 Monaten |
| es | No se ha reproducido en 7 meses |
| fr | Pas joué depuis 10 mois |
| it | Non sono stati riprodotti da 3 anni |

Read the numbers, not just the words: **each locale is its own roll.** The same
hub spans four months to three years depending on the language asked for. A
locale change does not translate the row, it replaces it. That is accepted —
a driver sees one locale — and it is recorded because it will look like a bug
to whoever finds it next.

### A hub can be turned into tracks in one request

Hub items are containers, so "shuffle this row" needs a track list. It costs one
request, using the cross-type filter the fork already sends as `artist.id`:

```
/library/sections/{key}/all?type=10&album.id=<ids>&sort=random
```

Both `album.id` and `artist.id` accept comma-separated lists with OR semantics:

| input | tracks |
|---|---|
| 6 albums | 42 |
| 6 artists | 154 |
| 500 albums (3,499-character URL) | 4,801 |

500 ids answered 200, so no cap on the id list is needed beyond the
`MAX_ITEMS` cap already applied to the track container.

## The rules

**Siskin mirrors, it does not curate.** Every non-empty hub is shown, in server
order, whatever it happens to be. The app holds no list of blessed hub
identifiers, so a hub Plex adds later appears with no code change. The two
exclusions are structural rather than editorial: `size=0`, because a row that
opens onto nothing is worse than no row, and `type=clip`, because Siskin has no
video playback and those rows could never play.

**A hub's children are its real list, not its preview.** The six items that
arrive with the listing decide only whether the row exists. Tapping follows the
hub's own `key`.

**We follow keys, we do not author them.** No rewriting a hub's query into
another type, no reconstructing its filters. The server rolled them; only it
knows what they were.

## What gets built

### The tree

```
More
├── Discover                        ← new, above Decades
│   ├── Recently Played Music       ← one row per surviving hub, server order
│   ├── Recently Added in Music
│   ⋮
│   └── Haven't played in 5 months
│       ├── ▸ Mix
│       └── the hub's artists or albums
├── Decades
└── Server Select
```

Three ids in `Constants`:

| id | kind | payload |
|---|---|---|
| `DISCOVER_ID` | static node, registered in `treeNodes` beside `DECADES_ID` | — |
| `HUB_ID` | prefix | `<scope>\|<key>` |
| `MIX_HUB_ID` | prefix | the same payload |

`MIX_HUB_ID` makes a fourth mix prefix. `Constants`' existing KDoc explains the
choice — the prefix is what the callback dispatches on — and extends unchanged,
but **it must be updated with the constant** rather than left saying "Three
prefixes", which is what it says today.

Its value is `"[shuffleHubID]"`, not `"[mixHubID]"`. The same KDoc records why
the existing values read "shuffle" while the rows are called Mix — an id is a
wire format, not a label — and a new prefix spelled the other way would make
that paragraph describe three of its four constants.

The payload mirrors `DecadeKey`: scoped by machine identifier so a stale id
reached through the car's back stack cannot query a library that has since been
switched away from.

Hub *items* need no new ids. They are artists and albums, so they become the
same `ARTIST_ID` and `ALBUM_ID` rows the Artists and Albums tabs produce, and
everything downstream — drilling, queueing, artwork, shuffle-follows-the-tap —
works with no new code. Rows are mapped by each item's **own** `type` rather
than the hub's declared one, because that is what the response actually carries.

### Plex layer

`Hub` gains `key`. It carries `hubIdentifier`, `title`, `type`, `size` and
`more` today; `key` is what a tap follows.

`LibraryService` gains a by-path call, because a hub's parameters cannot be
rebuilt from typed arguments:

```kotlin
@GET
suspend fun getByPath(@Url path: String): PlexResponse
```

**This is the one new risk in the design and it needs a guard, not a comment.**
`PlexRetrofitFactory`'s interceptor attaches `X-Plex-Token` to every request. A
`@Url` may be absolute, so a response body carrying
`key: "https://elsewhere.example/x"` would send the account token to that host.
`LibraryClient` rejects any key that is not a relative path beginning with `/`,
and a hub whose key fails that check is dropped at listing time — a row that
cannot be opened should not be drawn.

**A leading slash is not the whole rule, and finding out why is the reason this
guard has its own tests.** OkHttp normalises a backslash to a slash, following
the WHATWG URL Standard, so `/\evil.example/x` passes a slash-only check and
resolves to `https://evil.example/x` — a different host, with the account token
attached. This was measured against the pinned OkHttp and Retrofit, not
reasoned about, and again independently in review; 23 further candidates
(`%5C`, `%2F`, tabs and newlines, `..` traversal, `@`, embedded `#`,
leading-space-then-slashes) were swept and none escaped. So the guard also
rejects any key containing a backslash. That over-reaches slightly — a
mid-string backslash cannot escape the host — and the cost is one dropped row
for a key Plex would percent-encode anyway.

A test pins OkHttp's resolution behaviour itself, separately from the guard.
Without it, a dependency bump that changed which characters take the authority
branch would reopen the hole with the suite fully green.

A second hazard on the same call, which measurement partly retires: keys contain
`>`, `<`, `!` and `=` (`viewCount>=50`, `lastViewedAt<=-5mon`), and OkHttp
canonicalises `>` and `<` in a query to `%3E` and `%3C` rather than passing them
through. That turns out to be safe — against PMS 1.43.3, the raw form, the fully
percent-encoded form and OkHttp's actual mixed form all returned the same 128
artists, so the server decodes them.

What is *not* safe is encoding an already-encoded key a second time, which is
exactly what `getFirstCharacterContent` documents: `%23` became `%2523` and
answered 200 with an empty list. So the key is handed over whole rather than
decomposed into `@Query` parameters, and a round-trip test guards the boundary —
not because single encoding breaks it, but because nothing else would notice if
double encoding crept in.

`getSectionContent` gains `album.id` beside the `artist.id` it already has.

`PlexIdentity.headers()` gains `X-Plex-Language` from the device locale. It
applies to every request rather than only to hubs, which is correct — it is a
statement about the client, not about one call.

### Repository

`PlexBrowseRepository` gains three functions, mirroring the decade trio:

```kotlin
fun getHubs(prefix: String)                  // Discover's rows; drops size=0 and type=clip
fun getHubContent(hubKey: String)            // mix row + the hub's containers
fun getHubTracksForShuffle(hubKey: String)   // the tracks, no mix row
fun getHubTracksForIds(ids: List<String>)    // container ids -> tracks, one request
```

Naming follows `getDecadeTracksForShuffle` and `getPlaylistTracksForShuffle`:
the rows are called Mix and the functions still say Shuffle, the same split
`Constants` already documents for the id values.

### Wiring

`isMixRow` gains a fourth clause and `mixTracksFor` a `MIX_HUB_ID` branch.

**A hub mix always costs at least one request, unlike a decade mix, and the
difference is worth being exact about.** A decade's browse list *is* tracks, so
a cache hit replays it with `drop(1)` and issues nothing. A hub's browse list is
albums or artists, which are browsable and have no stream — replaying it would
hand media3 a queue of things it cannot play. So the cached list is used for the
container **ids**, which are then expanded to tracks by one
`album.id`/`artist.id` request. The kind is read off the row's own prefix,
`ALBUM_ID` or `ARTIST_ID`, so the list says which filter it needs.

The cache still earns its place: it saves *following the key a second time*, so
a hit costs one request and a miss two. And for a hub whose key carries
`sort=random` — the vault does — following it again would return a different set
of containers, so the cache is what makes the mix play what the driver is
actually looking at. That is the decade design's argument, surviving intact for
a different reason than latency alone.

The guard is unchanged in shape: `getHubContent` always writes the hub's own mix
row at index 0, so the cached list identifies itself, and a miss — cold cache or
another node's list — falls back to `getHubTracksForShuffle`.

### Strings and assets

Four strings, and therefore **twenty entries** across the five locales, plus one
drawable:

| key | English |
|---|---|
| `browse_discover` | Discover |
| `browse_mix_hub` | Mix |
| `browse_discover_empty` | Nothing to suggest yet |
| `browse_discover_empty_hint` | Play some music and check back |

`browse_mix_hub` is **"Mix"**, not "Discover Mix", breaking the `Artist Mix` /
`Playlist Mix` / `Decade Mix` pattern on purpose. Those name the thing being
mixed, which the driver can see. A hub's name is server-supplied and already the
screen's heading, so a qualifier would name the *feature* rather than the
content and tell the driver nothing.

`ic_browse_discover` dresses the Discover row in More, and only that row. The
hub rows underneath carry no `iconRes`, and therefore no `artworkUri`, handing
them to the car's own placeholder — accepted here for the reason the decade rows
accepted it, as a homogeneous set where one repeated glyph would carry no more
information than the placeholder's colour does.

The hub titles themselves are server data: neither translated nor
`translatable="false"`, because they are not string resources at all.

## What deliberately does not change

**Discover is a list, permanently — not a list until artwork lands.** This is
the one place the design departs from the decade precedent rather than following
it. `browsableChildrenAsGrid` is `false` on the Discover node and stays `false`,
and a future artwork change must not flip it.

A decade row *can* be recognised from a tile: "the eighties" is a look, and four
covers from it carry real information. A hub row cannot. "Haven't played in 5
months", "Most Played in April" and "More from Epitaph" are propositions, and
nothing about four album covers distinguishes the first from the second — the
covers would be the *answer* to the row, not a picture of it. The information is
the sentence, so the layout has to be the one that shows sentences: a list gives
the full title plus a second line, where a grid gives a caption that truncates.

Localisation sharpens it. The titles are server-supplied and vary in length by
locale — "Haven't played in 5 months" against "Keine Wiedergabe seit 4 Monaten"
— so a grid's caption width would be a constraint the app does not control and
cannot test in four of its five languages.

This is the same judgement `buildTree`'s KDoc already records for Artists and
Albums, which became lists when they started serving group rows, and for the
same stated reason: a row whose meaning is textual is worse in a grid.

**Composite artwork is still a legitimate follow-up**, as its own issue — a hub
row could wear a tile the way a list row wears a thumbnail. `DecadeCompositeArt`
is keyed on a decade string and fetches its own covers, so it would need
generalising to an arbitrary scope and id, though the listing already hands over
six items with real thumbs. What that issue may not do is change the layout.

**No paging.** `onGetChildren` ignores `page`/`pageSize` for every node and hubs
introduce no reason to change that.

**No "load more" for `more=true`.** Following the key already returns up to
`MAX_ITEMS`; the flag tells the app nothing it can act on beyond that.

**No caching of the hub listing.** The existing `queueSourceCache` covers the
mix row's needs, and a re-fetch returns the same roll for the same request.

**No shuffle row on Discover itself.** Its children are hubs, not containers,
and "shuffle everything the server suggested" is not a request anyone makes.

## Edge cases

- **Every hub filtered out** — the case on a server with no play history —
  renders the message row, not a blank screen and not an error. This follows the
  rule the signed-out row, the picker's message row and the confirmation row all
  already follow.
- **A key returning nothing on tap**, reachable if the server re-rolls between
  listing and tap, renders the message row for the same reason.
- **A hub of one item** (`More by Just Surrender`, 3 albums, `more=false`) is a
  complete hub, not a truncated one, and needs no special handling.
- **A library switch** leaves the Discover node stale exactly as it leaves the
  three music tabs stale; the scope in each id is what stops a stale row
  querying the wrong library.

## Testing

Reusing the existing MockWebServer and Robolectric fixtures:

- **The relative-path guard**: an absolute-URL key is rejected and its row
  dropped; a relative one is accepted. This is the test protecting the account
  token and is the most important one here.
- **Key round-trip**: a key containing `>`, `!` and `<` reaches the wire
  unchanged.
- The listing drops `size=0` and `type=clip`, and surviving rows carry
  `HUB_ID`-prefixed ids.
- Items map by their own `type`, not the hub's declared one.
- The mix row is first in `getHubContent` and absent from
  `getHubTracksForShuffle`; an album hub mixes on `album.id`, an artist hub on
  `artist.id`.
- The cache guard, in `MediaLibrarySessionCallbackShuffleTest`: a mix tap right
  after browsing expands the *cached* ids without re-following the key; the
  resulting queue holds tracks and excludes the mix row; and a cache seeded by
  browsing a different hub falls back to `getHubTracksForShuffle`.
- A hub mix never queues a browsable item — the regression that would follow
  from replaying the cached list the way the decade branch does.
- `X-Plex-Language` is present on requests.
- An empty listing renders the message row.
- `lintDebug` reports no new `MissingTranslation`, which is what proves the
  twenty string entries landed.
