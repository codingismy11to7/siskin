# The streaming cache and precache get Settings rows

**Date:** 2026-10-25
**Status:** Approved
**Issue:** #176

## Context

`docs/decisions/2026-08-29-streaming-cache-sizing-design.md` changed three
defaults and stopped there: `streaming_cache_size` became unset and derived from
the partition, `precache_tracks_count` became 2, and `precache_wifi_only`
became false. Nothing writes any of them, so each default is the effective
value, and a driver whose head unit is mostly offline maps — or whose tether is
slow — has no recourse short of rebuilding.

This design gives two of the three a writer, deletes the third, and shows how
much of the cache is in use. It also establishes the shape for a Settings value
that is chosen rather than toggled, which #174 (the Mix track limit) will reuse.

## What changes

| Key | Row | Values |
|---|---|---|
| `streaming_cache_size` | Cache size | Automatic, a ladder of sizes, Off |
| `precache_tracks_count` | Precache | Off, 1, 2, 3, 5, 10 |
| `precache_wifi_only` | — | deleted, with its reader and `QueuePreloader`'s metered-network check |

## A value row opens a picker screen

Settings has had two kinds of row: a toggle, and a destination. A value is a
third. The row shows its label with the current value on a line beneath it, and
tapping it pushes a picker onto `CarHostActivity`'s back stack — the same way
Customize tabs does. The picker is a heading, an optional explainer, and one
radio row per option in a single scrolling column; choosing one writes the
preference and pops back to Settings, which rebuilds from preferences as it
already does.

One picker fragment serves every value row. What varies — heading, explainer,
options, how each reads and writes — is supplied per row, so #174 is a new
option set rather than a new screen.

The value line beneath the label, rather than at the row's end, is what keeps
long values readable in a 480dp column: "2 tracks · paused while the cache is
off" fits there in English and still has room to wrap in German.

The radio row is the same arm's-length row as `addToggle`'s: the whole row is
the target, the radio button itself is not clickable, and the row is focusable
so a rotary controller stops on it.

## Cache size

The picker lists, in order:

- **Automatic (6.4 GB)** — clears the key. The figure is what the derivation
  gives this partition, so the choice is not made blind.
- **A doubling ladder** — 512 MB, 1, 2, 4, 8, 16, 32, 64 GB — showing only
  entries no larger than **half the partition's total**. Half is generous on
  purpose: the 8 GB ceiling on Automatic is a guess at a good share of a disk
  with unknown other tenants, and a driver with an empty 128 GB head unit should
  be able to overrule it. The 5.8 GB emulator partition stops at 2 GB. A
  partition that cannot be measured shows the ladder up to the 8 GB ceiling,
  for the same reason the derivation falls back to its floor rather than zero.
- **Off (also stops precaching)** — writes `0`. Precaching writes into the same
  cache, so turning one off turns off the other, and the label says so.

Sizes are 1024-based and rounded to one decimal, so the ladder reads as round
numbers. The units are string resources rather than literals — French writes
"Go" and "Mo".

The row's value line reads "Automatic (6.4 GB) · 1.2 GB used", or just "Off".
The used figure comes from `SimpleCache.getCacheSpace()`, read off the main
thread because the first read may construct the cache; it fills into a line
already laid out, so its arrival changes text but not layout. (#259 is the
layout shift this avoids repeating.)

There is no Clear cache button. Choosing Off frees the space, which is the one
clear this design offers and is reached deliberately.

## The cap is live

A changed size applies immediately, with nothing restarted and no "applies next
time" note.

media3's `LeastRecentlyUsedCacheEvictor` holds its cap in a `private final`
field and is itself `final`, and `SimpleCache` fixes its evictor at
construction. So Siskin supplies its own `CacheEvictor`: the same
least-recently-used policy, with a cap that can be changed. `CacheEvictor` is
the extension point media3 provides for exactly this — its documentation
directs custom eviction policies to implement it and call `removeSpan` — so
this is code we own against a public, long-stable interface, not a workaround
of internals.

- **Raising** the cap takes effect on the next write.
- **Lowering** it trims to the new cap immediately, on a background thread,
  oldest first, in batches that release the cache's lock in between — so the
  playing stream's own cache reads and writes get through during a trim of
  thousands of files.
- **Off** trims to zero. Readers already skip the cache when the size is zero,
  since both `DynamicMediaSourceFactory` and `QueuePreloader` read the size on
  every call, and choosing Off cancels a precache already in flight.

**A key being read is never evicted.** The stock evictor gets away with
removing spans under a reader because LRU order almost never reaches the
playing track; a trim to zero always would. For a transcoded track that is a
real failure: the reader hits the hole and resumes with a range request against
a fresh transcode, the mismatch `QueuePreloader` and `StreamingCacheDataSource`
already exist to prevent — and it can land a minute after the car pulls away.
`StreamingCacheDataSource` marks its key open for as long as it is open, the
evictor skips open keys, and the last reader's close re-runs the trim so Off
still ends at zero once the track finishes.

## Precache

Off, 1, **2 (default)**, 3, 5, 10. There is no Automatic: nothing here is
derived, and 2 is marked as the default in the list. Ten is the ceiling because
beyond it the writer competes with the stream actually playing for a whole
album's worth of bandwidth.

`QueuePreloader` reads the count on every `preload()`, so a change applies at
the next transition. Any change also cancels the batch already running, which
was sized by the old count: lowering ten to one should not leave nine tracks
downloading over a tether after the car drives off.

While the cache is Off, the row keeps its stored count and reads "2 tracks ·
paused while the cache is off". Turning the cache back on restores precaching
without a second trip to Settings, and the screen does not change shape under
the driver the way hiding or disabling the row would.

## Wifi-only is deleted

A preference with no writer is the condition #174 and #176 exist to remove, and
this one was never going to get a row: a moving car rarely sees an unmetered
network, so the setting could only mean "almost never precache". The key, its
reader and the metered check in `QueuePreloader` go.

## Translations

Every label, explainer, value format and unit is a string resource in all five
locales. Numbers are formatted for the locale.

## Alternatives considered

**Tap to cycle through values on the row.** No new screen, but overshooting
means going all the way round, and a ladder of eight sizes at arm's length is
too many to cycle.

**A −/+ stepper on the row.** Two small targets on one row, against the
whole-row-is-the-target rule, and awkward on a rotary controller.

**A two-column picker.** Built and tried; it read as an odd grid rather than a
list.

**Sizes as shares of the partition.** Truer to how Automatic works, but nobody
thinks of a music cache in percentages.

**Apply a new size at the next start, with a note.** No new code. Rejected
because the setting should behave the way it looks, and because the case the
issue cares most about — shrinking the cache on a disk that is filling — is the
one where waiting is worst.

**An "Apply now" button that releases and rebuilds the cache.** Neither the
player nor the precacher holds the `SimpleCache` long-term, so this is
possible, but it has to stop playback to be safe and its correctness depends on
every reader having let go of the old instance. About as much code as the
evictor, with sharper edges, to interrupt the music the evictor does not need
to.

**Killing the process to apply.** The car's media app stays bound to the
session that died, and Now Playing goes blank — the same wedge a reinstall
causes.

**A resizable evictor from a library.** None was found. media3 ships only the
fixed LRU evictor and a no-op one; third-party caching libraries are proxy
servers aimed at video, and replacing media3's cache with one would be far more
code owned, not less.

**A Clear cache button.** Not wanted. Off frees the space when that is the goal.

## Testing

The evictor gets unit tests against a real `SimpleCache` in a temporary
directory: eviction order, a raised cap admitting more, a lowered cap trimming
oldest-first to fit, and zero trimming everything.

The cache size ladder's filtering and the value-line formatting are pure
functions of the partition size and the stored values, and are tested as such,
including the half-partition cutoff and the not-yet-measured partition.

Each picker is driven through Robolectric: choosing an option writes the
expected preference — or removes the key, for Automatic — and pops back.

`QueuePreloader`'s existing behaviour loses its wifi branch and nothing else.
