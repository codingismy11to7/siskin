# The streaming cache sizes itself, and precaching turns on

**Date:** 2026-08-29
**Status:** Approved

## Context

Siskin has cached streamed audio since the fork — inherited from tempo, never
removed, and never noticed. `DownloadUtil` builds a media3 `SimpleCache` under
`getExternalFilesDirs(null)[0]/streaming_cache` with an LRU evictor, and
`DynamicMediaSourceFactory` routes every stream through it whenever the size is
above zero. `StreamingCacheKeyFactory` already keys entries on
`machineIdentifier + partKey` so that neither a rotated token nor a re-probed
address orphans them, which is the work that makes the cache survive long enough
to be worth sizing.

What it lacked was a size worth having. `streaming_cache_size` defaulted to
`"256"` megabytes and nothing wrote it, so 256 MB was the value rather than a
default. On a library of any fidelity that is a few dozen tracks, and a driver
who plays the same playlist daily was evicting most of it between trips.

`QueuePreloader` — a `CacheWriter` that fills the next queue tracks into the
same cache, wired into transitions, timeline changes and network changes — was
in the same position, one step worse: `precache_tracks_count` defaulted to `"0"`
and `precache_wifi_only` to `true`. Complete, tested by nothing, and dead.

This design changes three defaults. It does not build the settings surface that
should govern them; that is #176.

## What changes

| Key | Was | Is |
|---|---|---|
| `streaming_cache_size` | `"256"` MB | unset, and derived from the partition |
| `precache_tracks_count` | `"0"` | `"2"` |
| `precache_wifi_only` | `true` | `false` |

`StreamingCacheSize.forDirectory` takes **a tenth of the partition's total
bytes**, clamped to `[1 GB, 8 GB]`, and then clamped again to a quarter of the
partition. `DownloadUtil.getStreamingCacheSizeMegabytes` prefers an explicit
preference over it when one exists.

Roughly: a 32 GB partition yields 3.2 GB, a 64 GB one 6.4 GB, and anything from
80 GB up gets the 8 GB ceiling. The 5.8 GB partition on the AAOS emulator image
yields the 1 GB floor.

## Total, not free — this is the whole design

**A cache sized as a share of *free* space ratchets itself to nothing.** The
bytes it already holds are not free. So each process start measures a smaller
figure than the last, computes a smaller cap from it, and the evictor trims the
cache to fit — which frees less than it consumed, and the next start reads
smaller still. A head unit restarts the media service constantly; the cache
would be gone within days, and the failure would present as "caching randomly
stopped working" with nothing in the log to say why.

Total bytes cannot feed back, because nothing the cache does changes it. That is
the entire reason the policy is expressed against a number that looks less
relevant than free space.

The honest version of a free-space policy is to budget against *free plus what
the cache already holds*, which is stable for the same reason. It was rejected
for cost, not correctness: `SimpleCache.getCacheSpace()` is an instance method
and the cap is needed to construct the instance, so the size would have to come
from walking the cache directory before construction — thousands of span files
on a full 8 GB cache, on the startup path, to refine a number that a fixed share
of total already approximates well.

## The floor is a floor, not an override

`FLOOR_MEGABYTES` is 1 GB, which is a fifth of the emulator's 5.8 GB partition
and would be a half of a 2 GB one. Taking half a small device for cached music
is worse than caching less, so the quarter-of-total clamp is applied *after* the
floor and can override it downward. It is the only path that returns less than
the floor, and `givesUpTheFloorOnAPartitionTooSmallToAffordIt` is the test that
pins it.

## An unmeasurable partition resolves to the floor, not to zero

Zero is not a neutral value here: it is how the preference spells "cache
nothing", and `DynamicMediaSourceFactory` and `QueuePreloader` both branch on
it. So a `statfs` that throws, or a path with no existing ancestor, must not
return `0` — that would convert a measurement failure into a silent, total loss
of caching, which is a worse outcome than the 256 MB this replaces.

Relatedly, `SimpleCache` creates its content directory lazily, so on a fresh
install the first measurement is of a path that does not exist yet, and `StatFs`
throws on those. `totalMegabytes` walks up to the nearest existing ancestor —
the same partition either way. Without the walk, every fresh install would take
the floor, which is exactly the population the derivation exists to serve.

## Why precaching turns on, and on any network

Two tracks ahead covers a skip and the track after it. It is deliberately short:
the writer shares bandwidth with the stream actually playing, and racing further
ahead trades an audible stall now for a hypothetical one later.

`precache_wifi_only` becomes `false` because Siskin runs in a moving car. The
upstream default assumed a phone that spends its evenings on wifi and can wait;
a car on a road never sees an unmetered network, so the flag as shipped did not
mean "prefer wifi", it meant "never precache". Spending cellular data is a real
decision and it belongs to the driver, which is what makes #176 the right home
for it — but the current behaviour is not a conservative default, it is a
feature that cannot run.

## What this deliberately is not

**It is not Downloads.** Tempo's download stack — the tracker, the service, the
Room entities, the browse destination — was deleted in the fork and none of it
comes back. The distinction that matters is pinning: a download is a promise
that a track stays, and it brings an eviction UI, a storage-full story, a
sync-on-a-schedule story, and a "why is this greyed out" story with it. This
cache promises nothing and needs none of that. It gets faster the more you use
it, and the LRU evictor is the entire policy.

**It is not the OS's cache.** The bytes live under `getExternalFilesDirs`, not
`getExternalCacheDir`, so `StorageManager.getCacheQuotaBytes` does not govern
them and the system will never reclaim them under pressure. That is the correct
side of the trade for this app — an OS free to delete the playlist you just
cached defeats the point — and it is what makes the sizing discipline ours to
get right rather than the platform's.

## Testing

`StreamingCacheSizeTest` covers the policy as a pure function of total
megabytes — the tenth-share, both clamps, the sub-floor small-partition case,
and the zero/negative fallback — and covers `forDirectory` through Robolectric's
`ShadowStatFs`, including the not-yet-created-directory walk.

`DynamicMediaSourceFactoryTest` and `ReplayGainPrefetchSourceTest` continue to
set `streaming_cache_size` explicitly through `ResolvedStreamFixture`, which is
now an override rather than a redundant restatement of the default; both still
pass unchanged.

## Alternatives considered

**A bigger constant.** Simplest possible change, and wrong for the reason the
old one was: it cannot know whether it landed on a 16 GB head unit or a 128 GB
one, and the number that suits one wastes or overruns the other.

**Budget from free space plus the cache's own size.** Correct, and rejected on
cost — see above. Worth revisiting if a startup-time directory walk ever turns
out to be cheap, or if media3 exposes the cached size without an instance.

**Move the cache under `getExternalCacheDir` and take `getCacheQuotaBytes`.**
Lets the platform answer the sizing question and reclaim under pressure. Also
lets it delete the cache at any moment, which is the one thing this feature
exists to prevent.
