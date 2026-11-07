# Cross-fade between tracks

**Date:** 2026-11-07
**Status:** Approved
**Issue:** #291

Builds on the ReplayGain setting
(`docs/decisions/2026-08-09-replay-gain-setting-design.md`), whose audio
processor this has to share the transition with.

## Context

Siskin plays tracks back to back with no overlap. Many listeners want a
cross-fade, and Plexamp has one. #291 asked for it behind a setting, and asked
for a spike first because how to do it with media3 was unknown.

## What the spike found

**Media3 cannot overlap two tracks.** An ExoPlayer decodes one item at a time
into one `AudioSink`; a gapless transition is the next item's samples following
the last one's. The upstream request (androidx/media#2, earlier
google/ExoPlayer#3438) is open with no plan. `CompositionPlayer` can overlap
sequences but is an editing preview, lists audio cross-fade as unsupported, and
does not fit a streamed, session-backed queue.

**A second "tail" player works without disturbing the session.** About five
seconds before the overlap, a bare ExoPlayer opens the outgoing track a little
ahead, muted, and lines its position up with the session's player. At the
overlap it takes over the outgoing track and fades it out while the session's
player seeks to the next item. The session, the car's UI, reporting and
ReplayGain see an ordinary seek to the next item. On the emulator, of ten
attempts not cut short by a skip, nine fired, every swap measured within
25 ms, and none was audible.

**A fixed fade at the end of the file fades silence.** The first prototype
faded the last 3 s; most tracks had finished their own fade-out by then, and by
ear the next track simply started after the last ended.

**Plex knows where each track fades.** `GET /library/metadata/{id}` with
`includeLoudnessRamps=1` adds `startRamp` and `endRamp` to each audio stream:
`"dB seconds;dB seconds;…"`, levels rising as seconds grow, seconds counted from
the start or from the end. They come from the server's loudness analysis, are
undocumented, and are what Plexamp's Sweet Fades use: Plex staff describe its
overlap as starting at a dB threshold, capped at 15 s, with no user-set length.

**Position readings are noisy in a specific way.** In any 250 ms window, one of
the two players' `currentPosition` readings jumps by about 250 ms. A median of
five readings is stable. Adjusting the tail's speed to close a gap re-baselines
its position estimate and makes the readings worse; seeking it does not.

**Seeking a VBR MP3 can misreport position by seconds.** After a far user seek
into one VBR MP3, the session player's position trailed its audio by about 4 s,
so the track ended before the overlap began. The file decodes to its full
length. `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING` on that player did not fix the
user-seek case; the cause was not found.

## Decision

### A setting, on by default

One `addToggle` row, **Cross-fade between tracks**, on by default to match
Plexamp, in the playback group beside ReplayGain, with the four translations.
Turning it off during a fade ends the fade. It has no length option: the ramps
choose each overlap.

### The overlap starts where the track fades

The overlap starts where the outgoing track's `endRamp` first reaches -3 dB,
interpolated between points, clamped to 2–10 s. The outgoing track fades from
full to nothing over the overlap on an equal-power curve, on top of its own
recorded fade. The incoming track rises to full within 0.5 s, so the two are
heard together. -3 dB, the clamp and the 0.5 s are the values heard on the
emulator; they are constants, not settings.

### When there is no fade

The track plays straight through, exactly as with the setting off, when:

- the next track is from the same album, so albums meant to run together stay
  gapless;
- there is no next track, or repeat-one is on;
- the outgoing track has no `endRamp`, as when its library has not been
  analyzed;
- the tail player has not lined up by halfway into the overlap. It is not
  re-armed for that track.

The album test compares `parentRatingKey`s from the same metadata request that
carries the ramps. `PlexMediaMapper`'s `parent_id` extra is the browse parent,
not the album, and the persisted queue drops extras, so neither can answer it.

### The tail player

`CrossfadeController` in `service/` owns the second player and is the only
class that knows it exists. It watches the session's ExoPlayer through a
`Player.Listener` and a 50 ms tick, in three states: idle, armed and fading.

`BaseMediaService` builds the tail on demand:

- the same media source factory, so the streaming cache and
  `ServerAddressResolver`, with MP3 index seeking on, because the tail always
  seeks into the outgoing track. The session player's extractor is unchanged.
- its own `ReplayGainAudioProcessor`, set once to the gain the session's
  processor holds when the fade arms. Its gain never changes, so the gapless
  promotion logic in the shared processor is never involved.
- no audio focus, no wake lock, not attached to the session.

It is created when a fade arms and released when the fade ends or is cut.

### Lining up

Judge the gap by the median of five readings. Inside 25 ms is aligned. Outside
it, seek the tail to the session's position plus an offset that each
measurement corrects, then let it settle 400 ms before measuring again.

### What cuts a fade

A user seek or skip, a queue replacement or removal, repeat-one being turned
on, a player error, the setting being turned off, or the session player losing
audio focus. A cut stops the tail and puts the session player at full volume.
A pause pauses both players. A station top-up appending tracks does not cut
one.

### Ramps are fetched per track

When a track starts, with the setting on, request the current and the next
track's metadata with ramps, unless already cached. The result per track is an
overlap or none, plus its album key. The cache is cleared when the session
changes, so one server's answers are never applied to another's tracks. A
failed request means no fade for that track and is never thrown into playback.

### Plex layer

A `Stream` model with `streamType`, `startRamp` and `endRamp`; `Part.stream`;
`LibraryService.getMetadataWithRamps` and its `LibraryClient` counterpart,
returning `Either` like every other call. `LoudnessRamp` parses a ramp and
finds the threshold crossing, and is plain Kotlin.

## Alternatives considered

**Two players behind a `ForwardingPlayer` facade.** The session would wrap a
facade switching between two full players. Every queue, position and state
call would need translating, and the car's UI would have to survive the switch.
The tail player avoids both.

**Mixing in an `AudioProcessor`.** A processor sees one stream and cannot tell
a seek from a new item; it cannot decode the next track.

**A fixed fade length.** Tried first. Most fades landed in silence.

**A user-chosen length or threshold.** The ramps already give each track its
own overlap, and the tested values sounded right across a long listen.

**A fixed fade when ramps are missing.** That is the version that faded
silence.

**Ramps in every queue-building request.** `includeLoudnessRamps=1` on album
children, playlists, play queues and stations would avoid the extra request but
touches every call site, enlarges every response, and needs the ramps carried
in `MediaItem` extras that the persisted queue drops.

**Ducking the tail ourselves.** ExoPlayer ducks internally and does not report
it. On AAOS the car's audio system may duck per output, which would cover the
tail as well; this is checked on the emulator during implementation. Cutting
the fade on focus loss is the floor either way.

## Consequences

- Each track costs one extra metadata request while the setting is on, and
  none while it is off.
- During an overlap the outgoing track is streamed a second time from the
  streaming cache, or from the server if the cache is off. Exact seeking reads
  an MP3 from its start, so with the cache off an MP3 fade downloads most of
  the track again within the lead time; on a slow link it does not line up and
  that track plays without a fade.
- Reporting sees a seek to the next item. `PlaybackReporter` reports the
  outgoing track stopped up to ten seconds before its end. Plex still records
  it as played: on the emulator, every track that faded out appeared in
  Tautulli's history.
- The VBR MP3 seek error is unexplained. If it reaches the tail, the symptom
  is a jump in the outgoing track at the swap. The tail's exact seeking is
  meant to prevent it; a VBR transition is part of the by-ear check.
- A library without loudness analysis gets no fades with the setting on.
- Being on by default, it reaches existing installs with the update that
  ships it; the changelog and release note say so and where to turn it off.
