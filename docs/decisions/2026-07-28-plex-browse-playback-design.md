# Plex browse and playback

**Date:** 2026-07-28
**Status:** Approved

## Context

The Plex API client layer landed in PR #7, the browse tree was cut to three tabs
in PR #11, and sign-in moved to the Plex PIN flow in PR #13. What remains is the
half the sign-in spec deferred: browse, mapping and playback still ask Subsonic
for everything, so the app currently signs in to Plex and then browses nothing.

This spec closes that gap and deletes `subsonic/`.

### Why this is one slice

The sign-in spec argued browse and playback are not separable, and reading the
code confirms it. `AutomotiveRepository` hands results to `MappingUtil`, which
builds both the browse item *and* the stream URI *and* the extras bundle that
`MediaManager`, `ReplayGainUtil` and `SessionMediaItem` all read. There is no
seam between "listing a track" and "playing a track" — they are the same
function call.

Twelve files outside `subsonic/` still import it, and they form one connected
graph around a single pivot type:

```
AutomotiveRepository ──┐
SongRepository ────────┼─▶ Child ─▶ MappingUtil ─▶ MediaItem(+extras bundle)
QueueRepository ───────┤            │
MediaManager ──────────┘            ├─▶ SessionMediaItem (Room)
                                    ├─▶ Queue (Room, extends Child)
BaseSessionCallback ──▶ star/unstar └─▶ ReplayGainUtil
```

Removing `Child` breaks every one of those at once. That is the argument for a
single slice rather than a staged conversion: any intermediate state requires an
adapter shaped like the type being deleted.

## Decision: delete the pivot type

`Child` does triple duty today — wire DTO, domain model, and Room base class
(`Queue extends Child`). The conversion does not replace it. Plex `Metadata`
maps straight to `MediaItem`, and the Room entities are built from `MediaItem`:

```
Plex Metadata ──▶ MediaItem ──┬─▶ player
                              ├─▶ Queue (Room)
                              └─▶ SessionMediaItem (Room)
```

A neutral `Track` domain type was considered and rejected. It would exist only
to be converted: nothing reads a domain object, because **the extras bundle is
already this codebase's domain model.** Every downstream consumer —
`ReplayGainUtil`, `MediaManager`, `BaseSessionCallback`, `BaseMediaService` —
reads the bundle, never `Child`.

Using Plex `Metadata` itself as the currency was also rejected: it would leak
wire naming (`ratingKey`, `grandparentTitle`) into persistence and playback for
no benefit, since the mapping happens exactly once either way.

### What that decision is worth

The bundle carries roughly 30 keys. Outside the mappers themselves, the keys
live code actually reads are:

| Key | Read by |
|---|---|
| `id` | `MediaManager` (scrobble) |
| `artistId` | `MediaManager` (continuous play) |
| `type` | `BaseMediaService`, three sites |
| `uri` | `BaseSessionCallback` |
| `parent_id` | `MediaLibraryServiceCallback` |

Everything else — `suffix`, `transcodedSuffix`, `bitDepth`, `samplingRate`,
`path`, `isVideo`, `bookmarkPosition`, `originalWidth`/`Height`,
`averageRating`, `playCount`, `userRating`, `size`, `contentType`, `discNumber`,
`created`, `starred` — is written, persisted to Room, and read back only by a
mapper that writes it somewhere else. It is a 30-column round trip in service of
five values.

## Consequent decisions

### `Chronology` is deleted, not converted

`ChronologyDao` has exactly one method, `insert`. Nothing queries the table
anywhere in the app. It fed the phone UI's play-history screen, which the
three-tab sweep removed; what survived was a table that grows forever and is
never read.

`Chronology` (63 lines), `ChronologyRepository` (30), `ChronologyDao` (12) and
the two `MediaManager.saveChronology` call sites in `BaseMediaService` all go.

This is not scope creep: `Chronology`'s constructor copies all 30 bundle keys,
so it is one of the largest consumers of the type being deleted. Converting it
would mean porting a 30-field copy into a table with no readers.

### The ReplayGain bundle path goes; the feature stays

Plex exposes no ReplayGain field, so the bundle fast-path has no producer after
this change. `ReplayGainUtil` already carries a `MetadataRetriever` fallback
that reads gain from the file's own ID3/Vorbis tags, and that path becomes the
only one.

Deleted: `ReplayGainBundleUtil` (60 lines), the `rg_*` bundle keys, the
`@Embedded` `replayGain` column on `SessionMediaItem`, and the fast-path branch
in `ReplayGainUtil.applyGain`. `ReplayGainUtil` and
`ReplayGainAudioProcessor` otherwise survive untouched.

The cost is a `MetadataRetriever` round-trip per track that the Subsonic
`replayGain` field used to save. Keeping the machinery dormant against a backend
that cannot populate it is the alternative, and it is worse: it is Subsonic-shaped
code with no producer, which is exactly what this whole sweep exists to remove.

### `ClientCertManager` goes

The sign-in spec named this "a surviving oddity" and deferred it to this slice.
Its consumers are `App.java:36` and `subsonic/RetrofitClient.kt:102`.
`PlexRetrofitFactory` does not use it, and Plex has no client-certificate story
in this design. Once `subsonic/` is gone, `App` is configuring a static SSL
factory that nothing reads, so both the call and the class go.

### Credential rejection is classified inline

`SystemRepository` documents its own death: *"Dies with the rest of this class
when the browse tree moves to Plex, where a rejection is simply HTTP 401."*

`MediaLibraryServiceCallback.classifyFailure` currently reacts to a failed browse
by issuing a **second** request — a Subsonic `ping` — to ask whether the server
was refusing the credentials or merely unreachable. Against Plex the failed call
already carries its own answer: 401 is a rejection, a transport failure is not.
Classification moves inline and the extra round-trip disappears.

`SystemRepository`, `CredentialStateCallback` and `SystemRepositoryTest` go with
it. This is the one place where the conversion removes a network call rather than
relocating one.

### Transcoding parameters are dropped

`MusicUtil.getStreamUri` appends `maxBitRate` and `format` unless
`isServerPrioritized()`, and `updateStreamUri` rewrites them on already-built
URIs. All three read preferences frozen at their defaults since the three-tab
spec removed the settings screen.

Streams direct-play the part via `MediaUrlBuilder.streamUrl`, which already
exists and already works. Porting the bitrate machinery onto Plex's universal
transcoder would reimplement something no user can currently reach; it returns
when there is a settings surface to drive it.

## API-layer additions

Three gaps, all small. This amends the API-layer spec by extension rather than
correction — nothing there is wrong, it simply stopped short of browse.

| Addition | Endpoint | Needed by |
|---|---|---|
| `sort` query on `getSectionContent` | existing | "View by albums" tab; continuous play's random tier |
| Similar tracks | `library/metadata/{id}/nearest` | Continuous play's first tier |
| Rate | `GET /:/rate?key=&identifier=&rating=` | The heart command |

`sort` is one optional `@Query` serving two callers that arrived independently —
the artist-sorted album list and `sort=random` — which is the reason it is a
parameter rather than two endpoints.

`similar` goes in `LibraryService`, consistent with the other
`library/metadata` paths. `rate` goes beside `reportProgress` in `SearchService`,
since both are `/:/` server actions that Plex serves over GET despite being
writes.

**Recorded rather than fixed:** `SearchService` thereby becomes "search,
playlists, timeline and rate", which its own KDoc no longer describes. Splitting
it is a rename with no functional content, and this slice is large enough. A
later change that touches it should take the opportunity.

## The mapper

`plex/PlexMediaMapper.kt` replaces `MappingUtil`, with three functions:

- `trackToMediaItem(Metadata, parentId)` — playable
- `albumToMediaItem(Metadata, idPrefix)` — browsable
- `artistToMediaItem(Metadata, idPrefix)` — browsable

The bundle it writes carries `id`, `artistId` (from `grandparentRatingKey`),
`type`, `uri`, `parent_id`, `partKey` and `thumb`.

### `partKey` rather than a stream URL

Plex stream URLs carry `X-Plex-Token`. Baking one into a `MediaItem` and
persisting it — which is what today's code does with the Subsonic URI — means a
restored queue holds a URL that breaks whenever the token rotates.

Storing `partKey` (from `Metadata.media[0].part[0].key`) and rebuilding through
`MediaUrlBuilder.streamUrl` at restore time fixes that class of bug instead of
porting it. It is also why `MusicUtil.updateStreamUri`, whose job was patching
already-built URIs, has no successor.

## Room

`Queue` stops extending `Child` and becomes a standalone entity. Both it and
`SessionMediaItem` shrink to the fields that rebuild a `MediaItem`: ratingKey,
title, parentTitle, grandparentTitle, thumb, duration, index, year,
parentRatingKey, grandparentRatingKey, partKey, plus the ordering columns
(`trackOrder`, `lastPlay`, `playingChanged`) and `SessionMediaItem`'s
`timestamp`.

Version 23 → 24: drop `chronology`, recreate `queue` and `session_media_item`.
Destructive, following the precedent already set twice — no install holds data
worth preserving, and a queue of Subsonic ids is meaningless against Plex.

## Artwork

Only one thing changes: `CustomGlideRequest.createUrl`, which builds a Subsonic
`getCoverArt` URL, is replaced by `MediaUrlBuilder.artworkUrl`, which already
exists and already takes width and height.

**A correction, recorded because the wrong version of this section was approved
first.** An earlier draft claimed the id round trip through
`AlbumArtContentProvider` was broken: `contentUri` appends the artwork id as one
path segment and `openFile` reads it back with `getLastPathSegment()`, and Plex
thumbs are multi-segment paths (`/library/metadata/12345/thumb/1699…`), so the
id looked like it would truncate to `1699…` and every cover would 404 silently.

It does not. `Uri.Builder.appendPath()` percent-encodes the separators and
`getLastPathSegment()` decodes them back, so the path survives whole — including
after the URI is serialised and reparsed, which is what actually happens when it
travels through `MediaMetadata` to the car and back. Probed under Robolectric:

```
built uri       = content://…/albumArt/%2Flibrary%2Fmetadata%2F12345%2Fthumb%2F1699999999
lastPathSegment = /library/metadata/12345/thumb/1699999999
round trips     = true
```

The error came from reasoning about `getLastPathSegment()` on a raw parsed
string — where it genuinely does truncate — without checking that the matching
`appendPath` encodes. The two halves are a pair. No encoding helper is needed,
and the task that would have added one is dropped.

This is retained rather than deleted because the reasoning was plausible enough
to survive review, and a future reader looking at that round trip deserves the
answer rather than a second run at the same wrong conclusion.

## Browse tree mapping

`MediaBrowserTree` itself does not change — the three fixed tabs and the id
prefixes stay. Only what `AutomotiveRepository` does with each node changes:

| Node | Plex call |
|---|---|
| Playlists | `SearchClient.getPlaylists()` |
| Playlist → tracks | `getPlaylistItems(id, start, size)` |
| Artists | `getSectionContent(sectionKey, ARTIST, …)` |
| Artist → albums | `getChildren(ratingKey, …)` |
| Albums | `getSectionContent(sectionKey, ALBUM, …)` |
| Album → tracks | `getChildren(ratingKey, …)` |
| View by albums | `getSectionContent(sectionKey, ALBUM, sort=…)` |
| Search | three calls (ARTIST, ALBUM, TRACK), merged |

Search takes three requests because Plex rejects a multi-type search with HTTP
400 — verified in the API-layer spec against PMS 1.43.3, and the reason
`PlexItemType`'s KDoc calls the merge "the browse layer's decision to make."
This is that decision: issue all three, merge artists then albums then tracks,
preserving the ordering `AutomotiveRepository.search` uses today.

The "view by albums" shortcut currently rides Subsonic's `alphabeticalByArtist`
sort combined with a hack that **swaps the name and artist fields** on every
album so the artist renders as the title. Plex sorts server-side, so both the
swap and the hack go.

`artist.titleSort` is the right key, confirmed against a live server: it orders
albums by artist, where plain `titleSort` orders by album title. Unlike the
other two values this spec deferred to live checking, this one was guessed
correctly.

The label swap is not reproduced. Both album lists now render identically and
differ only in sort order, so "view by albums" shows album titles ordered by
artist rather than artist names on the title line. That is a deliberate,
user-visible change from the inherited behaviour.

`AutomotiveRepository` today is 594 lines, most of it six near-identical
Retrofit `enqueue` + `SettableFuture` blocks. The rewrite collapses that
boilerplate into one shared helper; the per-node logic is a few lines each.

## Continuous play

Ported in full, keeping both tiers: Plex's similar-tracks endpoint first, random
tracks from the music section as fallback.

The similar tier depends on sonic analysis. Where that is absent the endpoint
returns nothing and the random tier takes over — the same shape as the Subsonic
behaviour, where `getSimilarSongs2` returning empty fell through to
`getRandomSample`. The existing `dedupAgainstQueue` filter is unchanged.

### The endpoint, corrected against a live server

An earlier draft of this spec named `library/metadata/{id}/similar`. **That path
does not exist** — PMS answers it with HTTP 404, not an empty result, so the
"returns nothing and falls through" premise was never reachable through it.

The correct path is `library/metadata/{id}/nearest`, and its measured behaviour
is what the design assumed:

| Library | `/nearest` |
|---|---|
| sonically analyzed | 200 with real matches |
| not analyzed | 200 with an empty list |

Whether a library has been analyzed is worth recording because it is not
discoverable where one would look for it: the `/library/sections` payload
carries no analysis field at all, so reading its absence as "not analyzed" is a
mistake. The signal lives on the *track* — an analyzed library's tracks carry a
`musicAnalysisVersion` field, and an unanalyzed library's do not.

### The random tier's gate

`isFallbackToRandomTracksEnabled` defaults to `false`, and the three-tab spec
removed the settings screen that could turn it on. Left alone, continuous play
would therefore do nothing at all on an unanalyzed library — the tier that
exists precisely for that case is switched off with no way to switch it on.

The default flips to `true`; the preference itself stays, so a later settings
surface has something to bind to. This is the same shape of problem as the
dropped playlist shuffle below: preferences outliving the UI that set them.

## Rating

The heart survives as a heart, and this is a deliberate choice against the grain
of Plex's own UI.

Plex rates 0–10 (rendered as five stars, half-star granularity) where Subsonic
starred binary. media3 offers `StarRating(maxStars, rating)` and
`CommandButton.ICON_STAR_FILLED`, so a star was available. But **what the car
renders is a command button, not a rating widget** — media3 has no successor to
`MediaSessionCompat.setRatingType(RATING_5_STARS)`, and
`androidx.media3.session.MediaConstants` carries no rating constant at all. A
full five-star picker *is* possible in AAOS — unlike projected Android Auto, an
AAOS app runs natively and can show its own screens — but only as an activity,
and it would inherit the constraint `CarSignInActivity` already carries: that
screen deliberately omits `distractionOptimized`, so it opens only when parked.
Five small tap targets is not a driving input.

Given a binary toggle either way, the heart icon is kept because **Plex itself
collects highly-rated tracks into a heart-named playlist**, so the car's heart
matches what the user sees everywhere else in Plex.

Tapping sets `userRating=10`; untapping clears it. `userRating=10` and "five
stars" are the same value — the heart and the stars are one field.

The clear value was deferred to live verification, the way the sign-in spec
deferred `strong=true` — and like that one, the guess was wrong. Measured
against PMS:

| Request | Resulting `userRating` |
|---|---|
| `rating=10` | `10.0` |
| `rating=0` | **`0.0`** — a zero-star rating, not an absent one |
| `rating=-1` | absent (null) |

So untapping the heart sends `rating=-1`. Sending `0` would have looked correct
from inside the app — `isHearted` reads `>= 10.0`, so `0.0` renders as
unhearted — while leaving every un-hearted track carrying an explicit zero-star
rating in Plex, visible in every other client and impossible to tell from a
deliberate one-star-down verdict. This is exactly the class of bug that only a
live check catches: the app's own behaviour is correct either way.

Structurally this is a small change: the heart is already a custom command
button. `BaseSessionCallback` handles the tap and calls `onSetRating` itself;
`HeartRating` only tracks state and picks the on/off icon. Only the network call
underneath it changes.

## Execution

Four commits. Three are trivially reviewable; the third is not, and that is
accepted rather than worked around.

**1 — API-layer additions.** `sort`, `similar`, `rate`, with tests. Additive,
no consumers yet, green.

**2 — `PlexMediaMapper` and its tests.** Additive, not yet wired, green.

**3 — The cutover.** `AutomotiveRepository`, the Room entities and DAOs,
`MediaLibraryServiceCallback`, `MediaManager`, `BaseSessionCallback`,
`AlbumArtContentProvider`, `BaseMediaService`. Deleting `Child` breaks all of
them simultaneously, so they move together.

**4 — Deletion.** `subsonic/`, `Child`, `MappingUtil`, the `Chronology` stack,
`SongRepository`, `SystemRepository`, `ClientCertManager`,
`CustomGlideRequest`, `ReplayGainBundleUtil`, `MusicUtil`'s stream and cover
half, and the Subsonic keys in `Preferences`. Pure compiler-driven removal.

### Why commit 3 is not split further

Splitting it means keeping `Child` alive across a boundary, which means an
adapter shaped like the type being deleted — the throwaway scaffolding the
sign-in spec rejected for the same reason. The diff is bounded to roughly ten
files in a 10,000-line tree, and commits 1, 2 and 4 carry the parts that
benefit from being read in isolation.

## Verification

`./gradlew assembleDebug` and `./gradlew test` after every commit.

The nine existing tests under `plex/` stay green untouched. **A red Plex test
means the mapper or the API additions broke the layer underneath**, which is the
sharpest signal available that this change overreached — the same tripwire the
previous two specs relied on.

Of the four tests outside `plex/`, exactly one goes: `SystemRepositoryTest`,
with its subject, taking the seven credential-rejection assertions and the
`isAuthFailure` ones it inherited from `CredentialGateTest` during the sign-in
change.

The other three survive untouched, which is worth stating because two of them
look like they should not:

- `MediaBrowserTreeTest` — the tree's three-child root is unchanged here; only
  what each node fetches changes.
- `BaseSessionCallbackTest` — covers listener registration and custom-layout
  mechanics, not rating, so swapping the network call under the heart does not
  reach it.
- `CredentialGateTest` — already rewritten against the Plex predicate.

### Robolectric is adopted, reversing the previous two specs

Both earlier specs specified "all pure — no Robolectric," and for them that was
right: PIN state machines and token routing are genuinely framework-free.

This slice is not. Its central deliverable, `PlexMediaMapper`, produces a
`MediaItem` carrying an extras bundle, and `app/build.gradle` sets
`unitTests.returnDefaultValues = true`. Under that setting the stub
`android.jar` does not throw — it returns the zero value for the return type, so
`Uri.parse()` yields null and a `Bundle` silently discards everything written to
it. A test that maps a `Metadata` and asserts on the resulting bundle therefore
reads back null for every key whether the mapper is correct or broken. Testing
"everything except the object being built" is not a testing strategy.

Verified before adopting: Robolectric 4.16 runs against `compileSdk = 36` with
no `@Config(sdk = …)` override, in about 13 seconds including compilation, and
`Uri`, `Bundle` and `MediaItem` all behave for real.

The cost is two lines in `app/build.gradle`: the
`org.robolectric:robolectric:4.16` test dependency, and
`unitTests.includeAndroidResources = true` beside the existing
`returnDefaultValues`. It is a `testImplementation`, so nothing reaches the APK.

Adoption is scoped, not wholesale. `returnDefaultValues` stays, and only classes
annotated `@RunWith(RobolectricTestRunner::class)` get shadows, so every
existing test runs exactly as before. Logic that is already pure — the response
narrowing, the part-key and thumb selection, the search merge — keeps plain
JUnit tests, which are faster and state their intent more clearly. Robolectric
is for the assembly step alone.

**The evidence for this was the artwork section above.** The pure-function
discipline was about to ship an encoding helper against a bug that does not
exist, and no amount of it would have caught that, because the question was
precisely what the framework does. One Robolectric probe answered it.

### New tests

Plain JUnit, no framework:

- **Part key, thumb fallback, search merge** (`PlexMediaMapperTest`) — the
  branching logic, including a part with no key beside a usable one, and the
  album-then-artist thumb fallback.
- **Response narrowing** (`PlexBrowseRepositoryTest`) — an absent `Metadata`
  list is an empty tab, not an error. The Subsonic original got this wrong and
  put "Something went wrong" on the first tab for anyone with no playlists.

Robolectric:

- **Mapper assembly** — a mapped track carries the bundle keys live code reads
  (`id`, `artistId`, `type`, `uri`, `parent_id`, `partKey`, `thumb`), and its
  media id, title, artist and artwork URI are what the browse tree will show.
- **Stream URL rebuild** — a persisted entity whose token has changed produces a
  current URL, not the stored one. This is the bug `partKey` exists to prevent,
  and it is only observable once `Uri` and `Bundle` are real.
- **Room entity round trip** — `MediaItem` → `Queue`/`SessionMediaItem` →
  `MediaItem` preserves every field the player and the car read.

On the emulator: browse all three tabs, drill Artists → albums → tracks, play a
track, run a search, toggle the heart and confirm it survives a queue reload,
and confirm artwork renders in the browse list and on the now-playing screen.

Artwork keeps its emulator check even though the URI round trip is sound: a unit
test can prove the URL is well-formed, never that the server returns an image
for it.

## Scale

`app/src/main/java` is 10,010 lines today, of which `subsonic/` is 1,424 across
44 files. Roughly 2,500 lines out against 800 in, landing near 7,500 — down from
49,080 before the three-tab sweep.

The number that matters is different: after this, **nothing in the app is
Subsonic-shaped.** The conversion is over, and what remains is a Plex client
with an inherited package name.

## Dropped, deliberately

### Playlist shuffle

`AutomotiveRepository.getPlaylistSongs` read
`Preferences.isAndroidAutoShufflePlaylistsEnabled()` and, when set, shuffled the
playlist and truncated it to `MAX_SHUFFLE_ITEMS` (100) instead of taking the
first `MAX_ITEMS` (500) in order. `PlexBrowseRepository` does not reproduce it,
and both the preference and the constant are deleted.

Shuffling belongs to the player, not to the browse tree — the session already
carries a shuffle command — and truncating to 100 to make a shuffle feel random
is a workaround for not having one. The preference also defaulted to `false`
with no settings screen to change it, so the behaviour was already unreachable;
this records the removal rather than leaving it to be discovered as a
regression later.

### Preferences that outlived their UI

The three-tab spec removed the settings screen but kept the preferences its
survivors read, on the reasoning that they would freeze at their defaults. Two
of those defaults turned out to switch a feature *off* with no way to switch it
back on — the random-tracks fallback above, and playlist shuffle here.

The defaults that gate a car-reachable behaviour are flipped to the value that
makes the feature work; the preferences stay, so a later settings surface has
something to bind to. Auditing the rest of the frozen preference surface is not
in scope here, but it is now a known category of latent dead switch rather than
an assumption that they are all harmless.

## Not in scope

Renaming the `com.cappielloantonio.tempo` package. Offline playback as a
car-native feature. Any settings surface, including sign-out and transcoding
preferences. A five-star rating screen. Splitting `SearchService`. Restoring
playlist shuffle. A full audit of the frozen preference surface.
