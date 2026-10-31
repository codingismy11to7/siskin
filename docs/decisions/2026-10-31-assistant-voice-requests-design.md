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

The emulator image does carry the car Assistant, `com.google.android.carassistant`,
which works signed out once the emulator has a host microphone. Testing against it
found a second fault, described under "Assistant has to be visible to Siskin".

## Decision

### A separate resolver, ahead of the id lookup

An item with an empty `mediaId` and a search query, even an empty one, is a voice
request. `onSetMediaItems` and `onAddMediaItems` send it to `VoiceRequestResolver`, which returns a queue with
its opening position or a typed failure. Everything else takes the existing path
unchanged, except that an item with neither an id nor a query, such as a request by
URI, is refused: it has no stream, and reading it as "play music" would replace the
saved queue.

A branch inside `resolveQueueForItem` would not fit: it returns a bare list, and
voice needs an opening index, a start position and failures. Routing through
`onSearch` would not either, because that path returns rows for a person to
choose from.

A failed request writes nothing and leaves the player alone. Voice is the only
known source of a row with no stream, so this is not a general guard.

### Choosing a match

A focus extra selects the tier, and under a track or album focus a row credited to
the artist hint wins before the title is compared, so "Hurt" by Johnny Cash does not
open another artist's. Without a focus, a name equal to the query wins, ignoring
case, trying artists, then albums, then tracks. Otherwise the first result wins, in
the same order. First-result-only would let a partial artist match beat an exact
track title, and preferring tracks would make "play Black Sabbath" one song, because
eponymous tracks are common.

No exact match still plays something, because Google's guidance is to prefer
something similar over an error.

Assistant leaves the app name in the query but not in its focus hints: "the world
is a beautiful place on siskin" arrived that way. A trailing "on Siskin" or "Siskin"
is stripped before searching, and a query of nothing else counts as empty. A name
that really ends in "Siskin" loses that word too, which is rare enough to accept.

### Building the queue

Artist and album rows are browsable only, so a match has to be expanded:

- **Artist:** `getArtistTracks`, shuffled as a Mix is.
- **Album:** `getAlbumTracks`, in album order.
- **Track:** its album, opened at that track, or the track alone if the album
  fetch fails. Google suggests filling the queue with related content, and all
  search hits would make an unrelated queue from a common word.

### An empty query resumes, then shuffles

"Play music on Siskin" arrives with no query for a platform caller. The car Assistant
sends it as "on siskin" with an artist focus and an artist hint of "Siskin", so the
trim above is what makes it a resume. `BaseMediaService` has usually
restored the saved queue into the player by then, so the player's own state is
the queue to resume. It is told to play and nothing else: handing its own items
back would make media3 replace the queue, which re-buffers the track and reports it
to Plex as stopped, and rewriting the saved row resets its position. A queue that has played to its end is not
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
| `Unreachable` | a search tier failed and none found anything |

`search` folds each tier's failure into an empty list, so a dead server reads as
no results. In a car a dropped signal is routine, and telling that driver the
artist is not in their library would be wrong, so a voice-only variant reports
a failure when a tier failed and none found anything. It also stops at the first
unreachable tier, since each can cost a 20-second connect timeout and Assistant waits
10 seconds. A late result can still overwrite something the driver tapped meanwhile;
guarding against that would touch the ordinary tap path, and it was left out. The
car's browse search is unchanged.

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

### Assistant has to be visible to Siskin

Assistant finds media apps by their browser service, then sends `playFromSearch`
to the active session. media3 builds a controller's rights from
`ControllerInfo.isTrusted()`, and `BaseSessionCallback` uses the
`AcceptedResultBuilder(session, controller)` default, which gives an untrusted
controller read access only. The request then fails the `COMMAND_SET_MEDIA_ITEM`
check and is dropped with no log, and Assistant says "there was a problem" after
waiting 10 seconds for the playback state to change.

Trust needs the platform to look the caller up, and package visibility hid
Assistant from Siskin: logcat shows `AppsFilter ... BLOCKED`, then `Package
com.google.android.carassistant doesn't exist`, then `trusted=false`. A `<queries>`
entry for that package gives `trusted=true`, and the same request plays. It keeps
the decision with the platform, where widening `onConnect` for a named package
would put a trust rule in Siskin.

One request did get through before this fix. The likely reason is a visibility
grant Android makes after an interaction between two apps, which a reinstall
clears, but that was not confirmed.

Google's `MEDIA_PLAY_FROM_SEARCH` filter on an activity was tried and left out. It
is documented for phone apps, the car pages do not mention it, the car Assistant
never launched it, and requests failed the same way with it installed.

## Testing

Unit tests cover the resolver's choices and failures, the callback's guarantee
that a failed request never touches `QueueRepository`, and the search variant
against MockWebServer. On the emulator, the probe repeats a query, an empty query
and a nonsense query, plus `prepareFromSearch` then `play`. That probe is not
Assistant, so track requests were also made by voice against the emulator's car
Assistant, which is a 2025 build. Its logs show `PREPARE_MEDIA` sends Siskin nothing:
the request arrives as `playFromSearch` from `PLAY_MEDIA`. Gemini is a different
matter: in a car that ships with it, it asks for an app such as Spotify and never reaches
Siskin, and Google documents no route for that.
