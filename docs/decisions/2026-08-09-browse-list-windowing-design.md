# Windowed browse for lists too long to send

**Date:** 2026-08-09
**Status:** Approved

## Context

[#83](https://github.com/codingismy11to7/siskin/issues/83) reported that browse
lists stop at 500 items and say nothing, and proposed threading media3's
`page`/`pageSize` through to `X-Plex-Container-Start`/`-Size` — the parameters
`MediaLibraryServiceCallback.onGetChildren` receives and discards.

Both halves of that turned out to be wrong, measured on the AAOS API 33 emulator
against a live PMS 1.43.3 library of 1204 artists, 2777 albums and 40,565
tracks. The issue's own "Unverified" section asked for exactly this measurement
first, and it changed the design rather than confirming it.

## What is actually true

### The car never pages

media3 1.9.2's `MediaLibraryServiceLegacyStub.onLoadChildren` reads
`android.media.browse.extra.PAGE` and `PAGE_SIZE` out of the subscribe options
bundle. It forwards them only when `page >= 0 && pageSize > 0`; otherwise it
calls `onGetChildren(parentId, 0, Integer.MAX_VALUE, params)`.

`com.android.car.media` never sets them. Every node arrives as:

```
onGetChildren parentId = [rootID]     page=0 pageSize=2147483647
onGetChildren parentId = [playlistID] page=0 pageSize=2147483647
onGetChildren parentId = [artistsID]  page=0 pageSize=2147483647
```

Nor does it page lazily on reaching the end. With `MAX_ITEMS` temporarily set to
100, the Artists list was scrolled to its true end — `car_ui_scrollbar_page_down`
disabled, last item "Ballyhoo!" at index 99, the 100th — and no further
`onGetChildren` arrived, for that parent or any other.

So `page`/`pageSize` are not a paging facility we are failing to use. Forwarding
them would mean requesting the entire library on every tab open: 60.4 MB and
11.9 s for the track listing alone.

### The wall is at ~227 KB, not at 500 items

Raising the cap does nothing, which is directly observable: with `MAX_ITEMS` at
5000 the car still ended the Artists list at "Max Graham", index 292.

Two surfaces truncate at different item counts, which rules out an item cap:

| Tab | Last index rendered | Items kept | Bytes/item | Implied budget |
|---|---|---|---|---|
| Artists | 292 | 293 | 774 | 226,782 B |
| Albums | 281 | 282 | 806 | 227,292 B |

Agreement within 0.2 % across two independently measured surfaces. The car keeps
roughly the first **227 KB** of a browse list and silently discards the rest —
no exception, nothing in logcat, no indication to the user.

Per-item sizes are the real thing that crosses the Binder, not an estimate:
`LegacyConversions.convertToBrowserItem` is the exact conversion the legacy stub
applies, and its output was parcelled directly. A 500-item Artists node measures
387,124 B, so 500 items was never the number actually reaching the car — ~293
was.

This also disposes of shrinking the payload as a fix. Per item, roughly 300–350 B
is strings (artwork URI ~120 chars, title ~12–19, mediaId ~15) against ~450–500 B
of fixed `MediaDescriptionCompat` and extras overhead. Deleting the artwork URI
outright — losing all artwork — would take 774 B to ~530 B and raise capacity
from 293 to ~428. Against 1204 artists that is still a wall. Slimming moves the
cliff; it does not remove it.

### Plex pages correctly

`X-Plex-Container-Start`/`-Size` work on `library/sections/{key}/all`, which
nothing exercised before because every call site passed `0`. Verified: distinct
disjoint pages, `offset` echoed back, order stable under an explicit `sort`.

```
start=0     size=3   offset=0     The Hilliard Ensemble / ∆AIMON / $uicideboy$
start=500   size=3   offset=500   Have a Nice Life / The Haxan Cloak / Headboard
start=1200  size=3   offset=1200  ZOX / ZSK / Zulu Winter
```

## Design

The car asks for every node in full, so paging cannot come from the car. It
comes from the tree: **a list too long to browse becomes a list of ranges, each
of which is a node the car can ask for in full.**

Tapping Artists yields window rows labelled by the titles they span; tapping one
fetches exactly that slice with `start` and `size`. A library that fits under
`WINDOW_SIZE` keeps today's flat list and pays nothing — the same request that
returns the first window returns `totalSize`, so the shape is decided without a
second round trip.

### Offsets, not first-character buckets

Plex offers `/library/sections/{key}/firstCharacter?type=N`, which returns 27
`Directory` buckets with counts in one 1.3 KB response — the same shape the
Decades feature already consumes, and appealing because cumulative-summing the
counts gives every letter's offset for free.

It was rejected because it does not solve the problem on its own. Artists bucket
harmlessly (largest letter 95), but albums do not: **B = 350 and S = 315**, both
over the ~281-item ceiling, so those letters still truncate silently. Letters
would need a second, different splitting mechanism bolted underneath them for
exactly the cases that matter. Fixed-size windows over offsets are one mechanism
that is correct everywhere, and give evenly sized nodes instead of buckets
ranging from 5 to 350.

### Ordering by displayed name

Both windowed tabs pass `sort=title` rather than the server default or
`titleSort`.

`titleSort` is not merely "The Beatles" filed under B. 521 of the 1204 artists
carry an explicit one and some are arbitrary: "The Hilliard Ensemble" sorts as
`[anonymous]` and therefore comes first in the entire library, and "Max Graham"
sorts as `Deep Funk Project` and lands at index 292 among the Ds. Under
`sort=title` that artist is at 645, under M.

This matters more once windows exist, because a window is named after the item
at its edge. Ordering by a field the labels disagree with produces ranges that
read as broken alphabetising — and worse, labelling cannot repair it, since the
artist is filed under D whatever the label says. Only the sort can. Ordering by
the displayed name is what keeps a label and its contents the same thing.

The cost is that this changes the existing order of both tabs, which is a
user-visible change beyond what #83 asked for.

### Labels

`"$uicideboy$  -  Anders Osborne"`, each side cut to 16 characters with an
ellipsis.

Truncating each end deliberately, rather than letting the car truncate the label
as a whole: the car cuts from the right, which costs the *second* title
entirely — the one saying where the window stops. Measured on the 1024×768 head
unit, a row holds roughly 34 characters, and album titles routinely exceed that
alone ("A State of Trance Classics, Vol. 2"), so for albums this is the common
case rather than an edge case.

Known limitation: 16 characters is not always enough to distinguish adjacent
windows. A run of "A State of Trance Classics, Vol. N" produces two consecutive
rows both reading "A State of Tran…". Widening the cut trades directly against
the second title fitting.

### Window rows carry a flat icon

Each window row sets an `artworkUri` pointing at the tab's own drawable. An
absent `artworkUri` makes the car draw a music note on a per-row colour, which
`decadeToMediaItem` already accepts for eight rows; at 25–56 rows per tab it
becomes a column of unrelated colours competing for a driver's attention. One
repeated glyph says just as little and says it quietly.

Relatedly, the Artists and Albums tabs change from grid to list, so labels get
full width. This contradicts the note in `MediaBrowserTree.buildTree` about
grid/list styling being frozen at what a default install showed, which needs
updating with it.

## Cost

| | Before | After |
|---|---|---|
| Artists node, parcelled | 500 items, 387,124 B | 25 rows, 11,456 B |
| Window contents, parcelled | — | 50 items, 38,712 B |

In-app, the window list completed 351 ms after `onGetChildren` — count, first
window and 25 boundary titles. The comparable "before" figure was not captured
in-app; server-side, `curl` fetched 500 artists in 0.44 s and all 1204 in 0.71 s
from the same network, so the window list is not paying a round-trip penalty for
its extra requests.

The window list costs one request for the count and first window, plus one
one-item request per boundary for its label. Those are issued together rather
than in series — they are independent, and on a car's connection round trips
dominate. Index 0 needs no request, because the response that carried the count
already holds the first window.

`titleAt` returns null rather than raising, because those calls run inside
`async` and this codebase forbids a `raise` crossing a coroutine-builder
boundary. A window whose label could not be fetched falls back to its position,
which is worth more than failing the tab.

## Bounds and what is not covered

A window list is itself a browse node under the same 227 KB budget. At ~458 B
per window row that is ~495 rows, so at `WINDOW_SIZE = 50` this design covers
libraries up to roughly **24,750 artists or albums**. Beyond that the window list
truncates and windows need to nest. That is not built; it is recorded so the
next person meets a documented bound rather than the same silent wall.

**A library small enough never to window still pays for windowing, in styling.**
A tab's browsable content style is fixed in `MediaBrowserTree.buildTree`, which
runs on `onGetLibraryRoot` — before any library has been queried — so it cannot
depend on the item count. It had to become "list" so a window row's range label
gets the full width of the row. A library of `WINDOW_SIZE` items or fewer
returns its artists or albums flat, and those now render as list rows with a
thumbnail rather than as a grid of tiles. Accepted rather than fixed: the
alternative is learning the count on first browse and re-rendering the root
through `BrowseTreeInvalidator`, which is real machinery and a visible re-render
for a case that only libraries under fifty items ever reach.

Untouched, still capped by `MAX_ITEMS` and still able to truncate silently:

- playlist tracks, artist tracks, album tracks
- decade tracks — a deliberate random sample, not a truncation; see
  [the decades design](2026-08-09-decades-browse-design.md)
- search results, which receive `page`/`pageSize` from
  `onGetSearchResult` and discard them for the same reason

Also unresolved: `onGetItem` answers `found=false` for a window id, since no node
in `treeNodes` matches. The browse header still renders the range correctly, so
this is recorded rather than fixed.

## Alternatives rejected

**Raise `MAX_ITEMS`.** Measured as a no-op: at 5000 the car still stopped at 293
artists.

**Thread `page`/`pageSize` through.** The car always sends
`Integer.MAX_VALUE`, so this is "fetch the whole library", not paging.

**Shrink the per-item payload.** Buys ~293 → ~428 items at the cost of all
artwork, and still walls at 1204.

**First-character buckets.** Leaves albums B and S over the ceiling, so it needs
a second splitting mechanism underneath for the cases that matter.

**Make the truncation honest** — a trailing "showing first N of M" row. Makes the
wall visible without making the tail reachable; worth having only if windowing
is rejected.

## A note on generality

Every number here comes from AAOS API 33, the only API level for which nixpkgs
carries an `android-automotive` system image, and from one PMS 1.43.3 library. A
different head unit may well page. Nothing in this design prevents that: the
media3 plumbing forwards a real `page`/`pageSize` if a client ever sends one.
The design only declines to *depend* on a facility that this car does not
provide.
