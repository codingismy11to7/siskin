# ReplayGain becomes a setting

**Date:** 2026-08-09
**Status:** Approved

## Context

Adds the second row to the Settings screen that
[sign-in behind the car's settings gear](2026-08-01-car-sign-in-entry-point-design.md)
built and [continuous play](2026-08-02-continuous-play-setting-design.md) first
put a control on — that design named ReplayGain as one of the rows the `addToggle`
shape was built for, so this is the row following the pattern rather than
inventing one.

It also reaches into the prefetch path that
[the Plex browse and playback slice](2026-07-28-plex-browse-playback-design.md)
left as ReplayGain's only source of gain data, and depends on the resolver that
[the server address book](2026-08-08-server-address-book-design.md) put in front
of every stream.

## Why

**The machinery is complete, wired, and unreachable.**
`ReplayGainAudioProcessor` is installed in the audio sink unconditionally
(`BaseMediaService.kt:488`) and `ReplayGainUtil` is called from five places —
item transition (`:155`), timeline change (`:167`), tracks change (`:182`), seek
(`:260`), teardown (`:355`). None of it does anything.
`Preferences.getReplayGainMode()` (`Preferences.kt:92`) defaults to `"disabled"`,
`prefetchQueueGains` returns at `ReplayGainUtil.java:69` on that string, and
`resolveGain` and `resolvePeak` return `0f` for it.

Nothing in `app/src/main` writes the key. This is the same shape #72 described
for continuous play, inverted: there the absent writer made `true` the permanent
value, here it makes `"disabled"` one.

**The prefetch has two defects that only matter once it runs.** Both come from
one line — `submitPrefetch` builds `new MetadataRetriever.Builder(App.getContext(),
item).build()` at `ReplayGainUtil.java:87`, and the two-argument builder
constructs its own `MediaSource.Factory` internally:

1. **It bypasses the streaming cache.** `DynamicMediaSourceFactory` is what
   every played stream is built on, and the retriever is not using it, so the
   prefetch opens a second connection to a URL playback is about to open
   anyway, reads the header, and throws the bytes away.
2. **It bypasses `ServerAddressResolver`.** Playback survives a server address
   that changed after the queue was built; the prefetch does not. It fails, the
   `catch (Throwable)` at `:149` swallows it, one debug line is logged, and
   gains silently never arrive.

Neither is observable today because the feature is off. Turning it on is what
makes them real, which is why they belong in this change and not a later one.

**Nothing pre-caches, so there is no cheaper source for the tags.**
`QueuePreloader` is the read-ahead path and it is off the same way this is:
`getPrecacheTracksCount()` (`Preferences.kt:130`) defaults to `"0"` with no
writer, so `preload` returns immediately. The streaming cache
(`getStreamingCacheSize()`, `Preferences.kt:82`, defaulting to 256 MB) is
read-*through* — bytes land in it as playback consumes them, never before.

## The rule

**Reading a track's tags is part of playing it, so it goes through playback's
plumbing.**

Every property the stream path has — the cache, the cache keys, the address
resolver, the streaming-cache-size preference — exists because playback needs
it. A second reader of the same bytes that reconstructs none of that is not a
lighter-weight path; it is the same path with the fixes missing.

## Decision: the mode stays a four-way string, and only the UI is boolean

`ReplayGainUtil` keeps comparing against `"disabled"` / `"track"` / `"album"` /
`"auto"` exactly as it does now. That file is dense with invariants about gain
spikes, poisoned caches and post-seek restores, several of them documented at
length; a change that only needs to make the feature reachable should not open
it.

`Preferences` gains an adapter pair beside the existing reader:

- `isReplayGainEnabled(): Boolean` — `getReplayGainMode() != "disabled"`
- `setReplayGainEnabled(enabled: Boolean)` — writes `"auto"` or `"disabled"`

`getReplayGainMode()` is untouched and keeps its `"disabled"` default, which is
now a default rather than the value. `"auto"` is what "on" means: album gain
when the adjacent track shares an album title, track gain otherwise, so it is a
superset of the other two — album behaviour inside an album, track behaviour
across a shuffle.

Adding explicit `track` and `album` choices later is then a UI change against an
unchanged key, with no migration.

## Decision: off by default

A library whose files carry no ReplayGain tags gets the whole cost and none of
the benefit, and a car's connection is the worst place to discover that. The
switch is flipped once by someone who knows their own library.

This follows continuous play's reasoning rather than contradicting it: the
question a default answers is "what should happen to someone who never opens
Settings", and the honest answer for a feature that depends on how the user's
files were tagged is "nothing".

## Decision: the retriever is handed the app's `DataSource.Factory`

Two ways to give `MetadataRetriever` the right plumbing, and the difference is
not obvious without reading media3's bytecode.

`MetadataRetriever.Builder.build()` constructs a factory **only when one was not
set**, and the one it constructs is
`DefaultMediaSourceFactory(context, DefaultExtractorsFactory().setMp4ExtractorFlags(260))`.
Verified against `media3-exoplayer-1.9.2`: 260 is
`FLAG_OMIT_TRACK_SAMPLE_TABLE | FLAG_READ_SEF_DATA`. Omitting the sample table
is the metadata-only optimisation — without it, reading tags off an M4A parses
the full sample table for a track that is not going to be played.

**Rejected: passing `DynamicMediaSourceFactory` directly.** It is one line and it
is safe — `MetadataRetriever` never calls `setDrmSessionManagerProvider` or
`setLoadErrorHandlingPolicy`, both of which are `TODO()` in that class and would
throw — but it silently gives up those flags.

**Chosen: reuse only the `DataSource.Factory`.**
`DynamicMediaSourceFactory.buildDataSourceFactory()` (`:66`) is already public,
already `@VisibleForTesting`, and its KDoc already describes itself as the
factory every stream is built on. The prefetch wraps it in its own
`DefaultMediaSourceFactory` carrying media3's metadata flags, and gets the
cache, the address resolver, the streaming-cache-size preference *and* the
optimisation.

The factory is built per prefetch rather than cached, because
`buildDataSourceFactory` reads `getStreamingCacheSize()` on each call and that
is what keeps the preference live.

## Decision: the window is three, and it slides

`prefetchQueueGains` currently loops `0 until player.getMediaItemCount()`
(`ReplayGainUtil.java:73`), so queueing a 60-track playlist opens 60 retrievers
through a two-thread pool (`:46`) with a 20-second timeout each. Through the
cache those reads stop being waste, but the connection count is still wrong.

It takes the next three items instead, **not including the current one**. Three
rather than one: one is the strict minimum for the pending-gain handoff at a
gapless boundary, and leaves a skip landing on a track whose gain is not known
yet. The current track is excluded because it needs no retriever — `setReplayGain`
already reads its gain out of the metadata the player itself parsed, on
`onTracksChanged`, at no network cost. That is also what
`collectUpcomingStreamUris` does, which starts from `getNextWindowIndex`.

The "late prefetch for current track" branch in `submitPrefetch` survives this:
it fires when an item that *was* in the window becomes current before its
retriever finishes, which a fast skip still produces.

**This needs a second call site.** A track ending does not change the timeline,
so today's `onTimelineChanged`-only trigger would never move the window forward
— capping without also calling `prefetchQueueGains` from `onMediaItemTransition`
would prefetch the first three tracks of a queue and nothing else, which is
worse than not capping at all.

Selection extracts to a pure function over `Player`, mirroring
`QueuePreloader.collectUpcomingStreamUris`, so the window is testable without a
retriever. `prefetchedIds` stays as the dedupe.

## Decision: a flip takes effect at the next track

Nothing notifies the service. `render()` writes the preference, the service
reads it the next time it resolves a gain, and that is the whole mechanism —
the same absence of plumbing continuous play relies on, and here it is also the
correct behaviour rather than merely the cheap one.

Every level change `ReplayGainUtil` makes happens at a track boundary, and the
comments throughout it exist because changes anywhere else are audible as a
jump. Applying a flip mid-track would deliberately introduce the defect the file
is built to avoid: turning the switch off snaps a track playing at −8 dB back to
unity, in a stationary car, in response to a settings tap.

The cost is that the switch appears to do nothing until the next track. That is
accepted.

## Decision: one string

`car_settings_replay_gain` = **"Volume leveling"**, plus its four translations in
`values-{de,es,fr,it}`.

Not "ReplayGain", which is a tag format's name and would survive translation
only by not being translated.

The neighbouring row is an imperative phrase — "Keep the music playing" — and
this one is a noun phrase deliberately. "Even out the volume" promises an effect
the feature does not always deliver: it normalises to a reference level using
tags the file already carries, and for a file without them it does nothing at
all. A label naming the feature can be false about nothing.

## Not in scope

- **Preamp and prevent-clipping.** They keep their defaults (0 dB, on) and stay
  writer-less. Their "no writer" status is now the exception on this screen
  rather than the rule, which is worth a comment where
  `isFallbackToRandomTracksEnabled` already carries one, not a control.
- **Explicit `track` / `album` choices.** `auto` covers both; a chooser row is a
  shape this screen does not have yet, for a distinction most drivers have no
  opinion about.
- **Live apply**, per the decision above.
- **`ReplayGainUtil`'s gain arithmetic**, cache policy, and every guard in it.
  Untouched.
- **Turning on `QueuePreloader`.** A separate preference with a separate cost,
  and this change makes the prefetch cheap enough that it is not a prerequisite.

## Tests

- `Preferences`: the mode still defaults to `"disabled"`; `setReplayGainEnabled`
  round-trips through `isReplayGainEnabled` in both directions; enabling writes
  the literal `"auto"`, which is the coupling to `ReplayGainUtil`'s `switch` and
  the thing a future refactor would break silently. Robolectric caches
  `SharedPreferences` statically across test methods, so `@Before` resets the key
  rather than assuming its absence.
- `PlexSignInSettingsTest`: the `Connected` state renders the new row, the switch
  reflects the preference, tapping the row writes it, and the switch is
  `isClickable == false` — the assertion #79 added specifically so a row copying
  its shape cannot desync the switch from the preference on a real tap.
- The extracted window function: against a fake `Player`, that it returns three
  items, stops at the end of a queue shorter than three, and follows repeat and
  shuffle order the way `collectUpcomingStreamUris` does.
- Lint: `MissingTranslation` stays at zero. One new string means four new
  translations in the same change.
- On the emulator: gear → Settings → toggle, then play an album whose files carry
  ReplayGain tags and confirm `ReplayGainUtil`'s debug lines show a non-zero
  `totalGain`. With the switch off, confirm no prefetch runs at all.

## Risks

- **Concurrent access to the same cache key.** The player and the prefetch can
  open the same resource at once. `DownloadUtil` sets no `CacheDataSource`
  flags, so `FLAG_BLOCK_ON_CACHE` is unset and a locked key falls through to
  upstream instead of blocking. Worst case is a duplicate read of the overlap,
  which is what happens on every track today. There is a second edge past
  that one: `StreamingCacheDataSource.close()` calls
  `cache.removeResource(cacheKey)` whenever the key's `ContentMetadata` has no
  content length, and `CacheDataSource` only writes content length when it
  wins the hole span and is the one writing to the cache. A prefetch that
  loses that race to playback, or hits an upstream response with no
  `Content-Length`, closes with the length unset and removes the cached spans
  for that key -- including bytes playback itself wrote. Checked
  `SimpleCache.removeResource`: it is synchronised and only unlinks files, and
  an in-flight reader keeps its file descriptor, so what this costs is cache
  churn -- a span refetched later -- not corruption, a crash, or wrong audio.
- **`prefetchedIds` only clears on `release()`**, so it grows with the number of
  distinct tracks played in a session. Bounded by library size and left alone.
- **An untagged library** resolves every gain to zero and plays at preamp-only,
  which is unity by default. The feature does nothing, correctly, but it does it
  after paying for the metadata reads — which is the argument for the default
  above, not a defect.
