# A Mix is a sample, not a prefix

**Date:** 2026-08-28
**Status:** Approved
**Follows:** `2026-08-13-mix-rows-design.md`

## Context

Tapping **Mix** on a 12,586-track playlist plays A-artists and only A-artists.

The rows have been wrong since they shipped, and wrong in the way that takes
longest to notice: a Mix of a large list plays a *shuffle of its first 500
tracks*, which sounds exactly like a working shuffle until enough of it has gone
by. Two of the four rows are affected, and the fault is not the cap — it is that
a prefix is being presented as a sample.

`PlexBrowseRepository.getPlaylistTracksForShuffle` fetches
`playlists/{id}/items` with `X-Plex-Container-Start: 0`,
`X-Plex-Container-Size: 500` and **no sort**, and
`MediaLibrarySessionCallback.resolveQueueForItem` shuffles what comes back. So
the shuffle is real and the sample it draws from is the head of the list.
`getArtistTracks` has the same shape. Measured on the live library that produced
this report — artists at playlist index 0-5 are `4 Non Blondes` and
`A Wish for Marilynne` ×5, and at index 480-485 they are `Anberlin` ×6.

Decade Mix and Hub Mix already send `sort=random`, so they are honest samples
that happen to be capped at 500. Only their number changes here.

This is #88 for the surfaces that feed playback. The browse lists that issue
also covers are deliberately left alone; see "What this does not fix".

## What was measured

Against PMS **1.43.3.10896**, the same version the neighbouring decision docs
cite, on a music section of 12,596 tracks.

| | Question | Answer |
|---|---|---|
| 1 | Is there a container-size ceiling? | **No.** `X-Plex-Container-Size: 13000` answered `size=12596` — the whole playlist in one response |
| 2 | Does `playlists/{id}/items` report `totalSize`? | Yes, beside `size` |
| 3 | Does `sort=random` work there? | **No.** The same items in the same order as the unsorted control, across repeated requests |
| 4 | Does `sort=random` work on `library/sections/{id}/all`? | Yes — independent draws differ |
| 5 | Can a section query be filtered by playlist membership? | **No.** `playlistID`, `playlist` and `inPlaylist` are all accepted and all ignored, answering the unfiltered 12,596 |
| 6 | Do smart playlists expose a re-issuable query? | **Yes** — `content` decodes to a section query, and swapping its sort for `random` returns independent draws of that playlist's membership |
| 7 | What does a response cost? | ~1.63 KB/track: 500 ≈ 833 KB, 2,500 ≈ 4.0 MB, 12,596 ≈ 20.5 MB |
| 8 | Can the count be learned without paying for 7? | **Yes.** `X-Plex-Container-Size: 0` answers `totalSize` in **225 bytes**; for a playlist, `GET playlists/{id}` answers `leafCount`, `smart` **and** `content` together in **561 bytes** |

**Finding 1 is the one that shaped this design.** An earlier draft had a
concurrent pager fetching five 500-item pages at staggered
`X-Plex-Container-Start` offsets. None of that is needed: the server returns
whatever is asked for. The design below issues at most two requests and never
runs one coroutine builder, which is also why the `raise`-across-`async` hazard
that pager would have carried does not appear anywhere in it.

**Finding 7 is why a cap survives at all.** Nothing stops the app asking for
12,596 tracks; 20.5 MB of JSON parsed on a head unit is the reason not to.
`excludeElements` and `excludeFields` were tried and bring it only to 15.8 MB,
so trimming the payload does not rescue the unbounded case.

## The decision

**A Mix is up to N tracks drawn without bias from the whole of the thing that
was tapped.** One rule, four rows, no exceptions:

- `totalSize ≤ N` — the Mix is *all* of it, in natural order, shuffled locally
  exactly as today.
- `totalSize > N` — the Mix is **N drawn at random**, and the draw is the
  server's wherever the server can do it.

The same N bounds all four rows. Playlist and Artist change kind; Decade and Hub
change only their number.

N is a preference rather than a constant because the right value depends on the
head unit and on the connection, and neither is knowable here. On the library
measured above, only All Music exceeds the default — every other playlist plays
in full.

## The request shape

**A cheap probe, then exactly one fetch.**

1. **Probe** — cheap, and shaped by what is being mixed:
   - *Section-backed rows* use the same section query with
     `X-Plex-Container-Size: 0`, which answers `totalSize` in ~225-471 bytes.
   - *Playlists* use `GET playlists/{id}`, 561 bytes, which answers
     `leafCount`, `smart` and `content` in one request. A container probe would
     answer the count and `smart` but **not** `content`, so it would cost a
     second request in exactly the case that needs one fewer.
2. **One fetch**, shaped by that answer:

| Case | Fetch |
|---|---|
| `≤ N` | size N, natural order |
| `> N`, section-backed — Artist, Decade, Hub | size N, `sort=random` |
| `> N`, smart playlist | size N, `sort=random` on the decoded `content` query |
| `> N`, manual playlist | size `totalSize`, sampled locally |

**Decade and Hub skip the probe entirely.** Both branches of the rule are
`sort=random` for them, and a random sort of a list shorter than N returns all
of it — so the probe could not change the request, and asking anyway would be a
round trip that buys nothing. Only Playlist and Artist probe: Playlist because
it must learn `smart` and `content` as well as the count, and Artist because its
`≤ N` branch is deliberately *unsorted* and so differs from its `> N` one.

The probe is what makes the `> N` cases cost one fetch instead of two. Asking
for N first and re-asking when the answer turns out to be short would mean
throwing away up to 4 MB; a few hundred bytes is cheaper than being wrong.

`≤ N` keeping natural order is load-bearing for artists specifically.
`getArtistTracks`'s KDoc argues that an artist has a real running order to fall
back on when the car's shuffle toggle goes off mid-listen, and that reasoning
survives here intact: under the threshold the fetch is unsorted, so the fallback
order is still the artist's own rather than one this app invented.

## Where N lives

A preference, `mix_track_limit`, defaulting to **2500** — about 4 MB and half a
second on a LAN, roughly 170 hours of music.

**Nothing writes it yet.** The Settings screen behind the car's gear offers no
row for it, so the default is the effective value, and its KDoc says so. That is
the existing shape of `isReplayGainPreventClipping` and
`isFallbackToRandomTracksEnabled`, both of which are un-surfaced sub-options of
a toggle that *is* on that screen. `mix_track_limit` differs in having no
visible parent at all, which makes it closer to `LOUDNESS_PREAMP`. A follow-up
issue covers the row.

Stored as a string and parsed, matching `PRECACHE_TRACKS_COUNT` and the other
numeric preferences on that screen, so a future `ListPreference` needs no
migration.

**N is not `Constants.MAX_ITEMS`, and does not replace it.** `MAX_ITEMS` stays
500 and goes on bounding the browse nodes, which this design leaves alone; N
bounds what reaches the *player*. They are separate because the two have
different ceilings — a browse list is bounded by what the car will render in one
binder transaction, and a queue is not bounded by that at all, because media3
sends a `Timeline` through `BundleListRetriever`, an `IBinder` the receiving
process pulls in chunks. Collapsing them into one number would re-impose the
browse ceiling on playback, which is the whole of what this design removes.

## Smart playlists, and the hazard their query carries

A smart playlist carries a `content` field holding the library query that
defines it. Re-issuing that query with `sort=random` produces a uniform sample
of exactly the playlist's membership — verified against a 120-track playlist,
which answered with two independent draws over the same 121-item container.

That value is server-supplied and **doubly encoded**. The measured example:

    library://x/directory/%2Flibrary%2Fsections%2F7%2Fall%3Ftype%3D10%26userRating%253E%253E%3D4%26...

One decode yields `/library/sections/7/all?type=10&userRating%3E%3E=4&...`,
where `%3E%3E` is the `>>` comparison operator and **must stay encoded**.

This is the same hazard `LibraryService.getByPath` and
`getFirstCharacterContent` already document, and it is handled the same way:
decode exactly once, hand the result over whole through `@Url` rather than
decomposing it into `@Query` parameters that Retrofit would re-encode.
`getFirstCharacterContent`'s KDoc records what re-encoding costs — `%23` became
`%2523` and addressed a bucket that does not exist, answered `200` with an empty
list and no error anywhere.

**And it must be guarded before it is followed.** A `content` value is a string
out of a response body, so `LibraryClient.isSafeHubKey`'s rule applies to it
unchanged: the token rides on every request this client makes, and an absolute
or protocol-relative URL would hand a full account credential to whatever host
it named. A `content` that does not decode to a relative path is refused, and
the row falls back to the manual-playlist path rather than being followed.

## Manual playlists over N

A manual playlist has no `content` to re-issue, and finding 5 rules out asking
the server to filter a section by playlist membership. It is fetched in full and
sampled locally.

This is the one unbounded path in the design, and it is chosen knowing that: it
is the only option with no bias at all, and a manual playlist over 2,500 tracks
means someone added 2,500 tracks by hand. The alternatives were a contiguous
random window — cheap, but a *stretch* rather than a sample, which is the shape
of the bug being fixed — and several scattered windows, which reintroduces the
concurrency that finding 1 removed. Neither buys enough for a case that may
never occur.

## The tap

Tapping a track rather than the Mix row queues the cached browse list, which is
itself truncated, so playback stops a couple of hundred tracks in with nothing
saying why. The tap now fetches the node in full, up to N, and opens at the
tapped track.

The tapped track is always inside the first N, because the browse list it was
tapped in showed at most the ~285 items #87 measured — and #88 expects fewer
still for tracks, which carry a stream URI and a `requestMetadata` bundle an
artist row does not. So this fetch is the natural-order one and needs no
sampling.

**What has to change to allow it.** `cachedTracks` tags every track with the
*constant* `Constants.QUEUE_CACHED_SOURCE`, and `queueSourceCache` is a single
slot under that same key, so a tapped track knows it came from "the last browse"
and not which node that was. `onGetChildren` already receives `parentId`;
recording it beside the cached list is enough for `resolveQueueForItem` to
re-fetch. No change to item extras, no Room schema change, and the index-0 guard
in `cachedDecadeTracks` and `cachedHubTracks` is untouched.

## What this does not fix

**Browse lists still truncate silently.** A long track list is still fetched at
one size and still loses whatever exceeds the car's ~227 KB browse ceiling, with
nothing on screen saying so. That is deliberate: a list long enough to hit it is
not usable in a car, and the reachability problem it caused is what the Mix rows
and the tap above now solve. #88 is narrowed to the display, not closed.

**Search is untouched.** #89's 50-per-type cap is a different shape — results
are ranked by relevance, so "fetch it all" is not meaningful and the windowing
idiom does not transfer.

**Album tracks and the playlists listing keep their caps.** Neither realistically
approaches 500.

## Errors

Probe and fetch are sequential, both inside the existing `either { }` in
`fetch`/`cachedTracks`, and neither introduces a coroutine builder — so Arrow's
`raise` short-circuit crosses nothing it must not cross, and the rule against a
broad catch inside `either { }` is not newly stressed.

A failing probe or fetch is an ordinary `PlexTransportFailure`, which already
carries the host that failed. No new error type: nothing here fails in a way
`CreatePinError`'s precedent would justify modelling separately.

The count is read from the probe that is about to be acted on, and the probes
disagree with each other. The **playlists listing** reports `leafCount` 12,586
for the list that `GET playlists/{id}` and the items container both report as
12,596. The two probes named above agree with the fetch that follows them; the
listing does not, and is not consulted for this. A ten-item disagreement cannot
matter at a threshold of 2,500, but reading the count from a third endpoint
would be a bug waiting for a list that sits on the line.

## Testing

MockWebServer, matching the existing suites. Probe-then-fetch is two requests, so
tests assert `X-Plex-Container-Size: 0` on the first and the size and sort on the
second.

- `≤ N` — one probe, one unsorted fetch, every track returned, order preserved.
- `> N` section-backed — the second request carries `sort=random` and size N.
- `> N` smart playlist — the decoded `content` is requested verbatim, with
  `%3E%3E` intact, and a `content` naming another host is refused.
- `> N` manual playlist — full fetch, N sampled, sample drawn from the whole.
- Probe fails, and fetch fails, each surfacing as `PlexTransportFailure`.
- The tap resolves the node it was tapped in rather than the last one browsed.

The `content` decode and its host guard are pure functions and test without
Robolectric. The preference read needs it, and must reset `mix_track_limit` in
`@Before` — Robolectric caches `SharedPreferences` statically across methods, so
a test that assumes absence passes or fails on what ran before it.

## Documents affected

- `CHANGELOG.md` — a bullet at the top of `[Unreleased]`, since this changes
  what the car does.
- #88 — narrowed to the browse lists.
- A new issue for the `mix_track_limit` settings row.

## Alternatives considered

**Always sample, never exhaustive.** One `sort=random` fetch for every Mix,
whatever the size. Simplest possible change, and it gives up the property that a
playlist under the threshold plays through without repeating — which is most
playlists, including every one on the library measured here except All Music.

**Always exhaustive.** Correct at any size and unbounded in cost: 20.5 MB for
the list that prompted this, with no ceiling on what a larger library would ask
of a head unit.

**Sample immediately, extend while playing.** Start on a small draw and append
in the background as the queue drains. The best listening behaviour of the four,
and the only one needing new state to track what has already been drawn. Finding
1 removed most of its advantage: a correctly-shaped fetch of 2,500 already
returns in half a second, so there is little wait left to hide.

**A concurrent pager over `X-Plex-Container-Start`.** Designed in full before
finding 1 was measured, and deleted by it. Worth recording because the header
pair reads like an invitation to page, and the endpoint does honour arbitrary
offsets — it is simply that nothing here needs them.
