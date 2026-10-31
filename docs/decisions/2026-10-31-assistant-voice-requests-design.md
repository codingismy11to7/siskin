# Playing what Google Assistant asks for

**Date:** 2026-10-31
**Status:** Approved

## Context

A driver reported that asking Assistant to play something on Siskin does
nothing, while another media app on the same Volvo EX30 handles it (#289).

A probe against an API 33 AAOS emulator called `playFromSearch` through a
platform `MediaController`, the interface Assistant uses:

- The session advertises `PLAY_FROM_SEARCH` and `PREPARE_FROM_SEARCH`.
- The call arrives at `onSetMediaItems` as one item with an empty `mediaId`,
  for a named query and an empty one alike.
- `resolveQueueForItem` finds nothing and hands back that item, which has no
  stream. ExoPlayer throws a `NullPointerException` from
  `DynamicMediaSourceFactory`, Guava logs it, and the caller sees no change and
  `error=null`.
- `queue.insertAll(items, true, 0)` has already run by then. It clears the table
  and drops the row for its blank id, so a voice request erases the saved queue.

The probe could not reach Assistant itself: the emulator has no Google account.
Whether Assistant routes to Siskin at all is therefore unproven. Google's example
puts the `MEDIA_PLAY_FROM_SEARCH` filter on an activity and says "some systems"
need it, without naming them. Siskin declares it on `MediaService`, and no
activity declares it.

## Decision

### A separate resolver, ahead of the id lookup

An item with an empty `mediaId` is a voice request. `onSetMediaItems` and
`onAddMediaItems` send it to `VoiceRequestResolver`, which returns a queue with
its opening position or a typed failure. Everything else takes the existing path
unchanged.

A branch inside `resolveQueueForItem` would not fit: it returns a bare list, and
voice needs an opening index, a start position and failures. Routing through
`onSearch` would not either, because that path returns rows for a person to
choose from.

A failed request writes nothing and leaves the player alone. Voice is the only
known source of a row with no stream, so this is not a general guard.

### Choosing a match

A focus extra selects the tier. Without one, a name equal to the query wins,
ignoring case, trying tracks, then albums, then artists. Otherwise the first
result wins, in search's order of artists, albums, tracks. First-result-only would
let a partial artist match beat an exact track title, and preferring tracks
would let "play Tool" start a track with that word in its title.

No exact match still plays something, because Google's guidance is to prefer
something similar over an error.

### Building the queue

Artist and album rows are browsable only, so a match has to be expanded:

- **Artist:** `getArtistTracks`, shuffled as a Mix is.
- **Album:** `getAlbumTracks`, in album order.
- **Track:** its album, opened at that track, or the track alone if the album
  fetch fails. Google suggests filling the queue with related content, and all
  search hits would make an unrelated queue from a common word.

### An empty query resumes, then shuffles

"Play music on Siskin" arrives with no query. `BaseMediaService` has usually
restored the saved queue into the player by then, so the player's own state is
the queue to resume, read before anything is written, because rewriting the
queue resets its saved position. A queue that has played to its end is not
resumed, because seeking to its end would play nothing, so it counts as nothing
to resume.

With nothing to resume, it plays a shuffle of the whole library through a named
wrapper over the unfiltered hub query. `PlexMixRepository.randomTracks` was the
alternative, but it cannot tell a failure from an empty library.

### Failures reach Assistant

| Failure | Meaning |
| --- | --- |
| `SignIn` | not signed in, or the server rejected the token |
| `NotFound` | the search worked and nothing matched |
| `Unreachable` | every search tier failed |

`search` folds each tier's failure into an empty list, so a dead server reads as
no results. In a car a dropped signal is routine, and telling that driver the
artist is not in their library would be wrong, so a voice-only variant reports
when every tier failed. The car's browse search is unchanged.

The callback returns a failed future and calls `MediaSession.sendError`, which
is non-fatal: whatever was playing keeps playing. `setPlaybackException` would put
the session into an error state over a misheard song.

`sendError` reaches the platform session only briefly: media3 pushes a
`PlaybackState` carrying the error and replaces it with the normal state in the
same call, so a read a moment later shows `error=null`. Assistant hears the error
only if it is listening for state changes, and that is unproven. It is kept for
this build because a car test answers that directly, and the Voice tab records the
failure either way. If Assistant stays silent, the sticky option needs clearing
logic and a check that the media notification controller is connected, since that
is the only route `setPlaybackException` has to the platform session.

Each failure has a message translated into the four locales. Dutch, which the
reporting car appears to use, falls back to English.

### A Voice tab on the debug screen

`VoiceRequestLog`, modelled on `FailedTrackLog`, keeps the last 20 requests with
their time, query, focus and outcome, and the debug screen gets a Voice tab.
Logcat is out of reach in a car, and this is what separates "Assistant never
reached Siskin" from "it reached Siskin and failed". It logs on arrival, so a
request that fails is still recorded. Failures was the alternative home, but a
successful request is not a failure.

### One variable per build

This ships to `automotive:internal` as a patch release without the activity
filter. An empty Voice tab after an Assistant request means routing is the
remaining fault, and the next patch adds the filter. Shipping both at once would
not show which one mattered.

## Testing

Unit tests cover the resolver's choices and failures, the callback's guarantee
that a failed request never touches `QueueRepository`, and the search variant
against MockWebServer. On the emulator, the probe repeats a query, an empty query
and a nonsense query, plus `prepareFromSearch` then `play`, Assistant's real
sequence. None of that is Assistant, so routing is proven only in a car.
