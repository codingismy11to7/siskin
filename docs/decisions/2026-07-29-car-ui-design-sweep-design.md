# Car UI design sweep

**Date:** 2026-07-29
**Status:** Approved

## Context

Written after the fact. Unlike the specs before it, this change had no design up
front: it was an interactive pass over the car's browse and playback surfaces,
driven one screen at a time against a live AAOS emulator and a real Plex account,
with each change built and reinstalled to be looked at before the next was
started.

That working style is worth naming because it decided the content. Three of the
findings below could not have been reached by reading code — the artist-albums
defect lives on the *server*, and the transport-row and progress-bar behaviour
live inside `com.android.car.media`. Equally, a sweep with no spec produces no
record of *why* by default, which is what this document exists to supply.

Six commits, from `74d2fac1` to `1396c60d`.

## Decision: playable children are always a list

`EXTRAS_KEY_CONTENT_STYLE_BROWSABLE` and `EXTRAS_KEY_CONTENT_STYLE_PLAYABLE`
describe an item's **children**, not the item itself. Both call sites — the fixed
tree in `MediaBrowserTree` and the mapper's `browsableItem` — wrote a single
value to both keys. An album therefore could not ask for the browsable grid that
makes the Albums tab look right without simultaneously asking for its *tracks* as
tiles.

The result fitted one row of tracks per screen where a list fits five, and every
tile carried the same cover, because a track's artwork is inherited from its
album. The single visual that could distinguish the rows was identical in all of
them, which is precisely the case a grid is worst at.

`BrowseContentStyle` now holds the rule: playable children are a list
unconditionally, browsable children follow the node's preference. Playable is
unconditional rather than per-node because the only playable items in this app
are tracks, and no track list benefits from tiles.

The `gridView` parameter became `browsableChildrenAsGrid`. The rename is part of
the fix: the old name described the item rather than its children, which is the
mistake the code made.

## Decision: "view by albums" is deleted

It ran the identical query to the Albums tab — same endpoint, same mapper, same
grid — with `sort=artist.titleSort` in place of `sort=titleSort`. Same albums,
same tiles, differing only in row order.

And the order was illegible. The tile's large text is the album title while the
sort key is the artist, so the grid read as unsorted; a user cannot see the field
it is ordered by. The inherited Subsonic implementation swapped name and artist
so the artist rendered as the title, which is what made the ordering visible; the
Plex rewrite dropped that swap deliberately (see the browse/playback spec) and
with it the only clue.

It also occupied the first slot of the artist list, and was capped at
`MAX_ITEMS`, so it was a truncated flat grid either way.

Alternatives considered and rejected:

- **Rename it** to "Albums by artist". Honest about the destination, leaves the
  illegible ordering and the stolen slot.
- **Restore legibility** by rendering the artist on the title line in that view
  only. Would have worked, but it re-adds a second album list whose entire
  purpose is a sort order.
- **Move it under More**, freeing the artist slot without fixing the ordering.

Deleted instead because Artists → artist → albums already does the job in two
taps, legibly. `SORT_ARTIST` goes with it. `SORT_TITLE` stays and keeps a comment
recording that the server's *default* order is the one just removed — without the
explicit sort the Albums tab would silently become the deleted view.

## Decision: an artist's albums come from the section listing

`library/metadata/{artist}/children` silently omits albums. Measured against a
live PMS 1.43.3 library:

| artist | `?type=9&artist.id=` | `/children` |
|---|---|---|
| 311 | **17** | 14 |
| ∆AIMON | **1** | 0 |
| 8-Bit Operators | **1** | 0 |
| 10,000 Maniacs | **1** | 0 |
| 20syl | **1** | 0 |
| 808 State | **1** | 0 |
| The Hilliard Ensemble | 1 | 1 |

Five of the first twelve artists reported zero children while owning one album
each. 311 returned 14 of its 17; the three omitted were a compilation, a
greatest-hits and a live album whose own metadata is **field-for-field identical
in shape** to the ones that appeared, so nothing in the payload distinguishes
them. Adding `?type=9` to `/children` changed nothing.

∆AIMON is the clearest case: `/children` answers `size: 0` — with a 1607-byte
body that is entirely the artist's biography, which is how a zero-item response
came to look substantial — while `allLeaves` on the same artist returns 16
tracks and the album's own `parentRatingKey` points back at it. The relationship
exists; that endpoint cannot see it.

The section listing filtered by `artist.id` returned the correct count for every
artist sampled. It is also what the Albums tab already reads, which is how an
album could be visible there and missing from its own artist: **the two screens
were querying different indexes.**

Scoped to the artist direction only. Album → tracks is sound on the same server —
four albums checked, every child count matching its own `leafCount` — so that
call still uses `getChildren`.

The same `artist.id` filter feeds the artist track list, rather than
`allLeaves`. Both returned identical counts (297, 16 and 14 for the three artists
sampled), so this picks the query already proven against the artist relation
instead of introducing a second one.

## Decision: shuffle rows, with the player doing the shuffling

A "Shuffle this artist" row heads each artist's album list and a "Shuffle this
playlist" row each playlist. They exist because shuffle mode alone does not do
what it appears to: it orders what comes *after* the current item, so starting a
playlist and then reaching for shuffle still plays track one and needs a `next`
as well.

The track list stays in library order and the player shuffles, which follows the
rule the browse/playback spec set when it removed inherited playlist shuffle —
shuffling belongs to the player, and the session already carries the command. The
consequence is deliberate: turning the car's shuffle toggle off mid-listen
reveals the artist's or playlist's real running order rather than one this code
invented.

The opening track is chosen in `onSetMediaItems`, because shuffle mode would
otherwise always open on track one.

Two id prefixes, `SHUFFLE_ARTIST_ID` and `SHUFFLE_PLAYLIST_ID`, rather than one
prefix plus an embedded kind. The prefix **is** the mechanism: the car rebuilds a
tapped item from its media id alone, so the extras the row was built with are
gone by the time it arrives, and the prefix is all the callback has to dispatch
on.

Two consequent details, both places where the obvious shape is wrong:

- `getPlaylistTracksForShuffle` is a separate entry point from
  `getPlaylistTracks` rather than a filter applied afterwards, because a queue
  containing the row would hold a playable item with no stream.
- `isShuffleRow` is a pure prefix test and deliberately does *not* delegate to
  `shuffleTracksFor`. That issues a network request, and `rememberTracks` runs
  the predicate against every row of every browse list.

The row is playable but never calls `setUri`, for the reason `browsableItem`
already documents: a non-null `localConfiguration` makes `resolveQueueForItem`
treat the row as already resolved and "play" a track that has no stream.

### The cost: the car's shuffle toggle is global, sticky and buried

Deferring to the player buys consistency — one shuffle concept, and the car's
toggle tells the truth about what is happening. What it costs is that tapping a
shuffle row reaches out and flips a **global, persistent** setting, and the
control it flips is a bad one.

Three separate problems, all downstream of that:

- **It persists.** `BaseMediaService` writes shuffle mode to `Preferences` and
  restores it when the player is built, so a shuffle row tapped once leaves
  shuffle on across track changes, queue changes and process restarts.
- **It leaks into unrelated listening.** Later, deliberately playing the first
  track of an album: that track plays, and then the *next* one is shuffled,
  because the toggle is still on from something else entirely. The user asked for
  an album and got a shuffle of it. This is the concrete complaint that prompted
  revisiting the decision.
- **It is hard to reach.** Turning it back off means expanding Now Playing,
  opening the overflow, and finding the shuffle button — several interactions
  deep, in a car, to undo a side effect of one tap elsewhere.

The alternative is to shuffle in the app: hand the player an already-shuffled
list and never touch its toggle. That fixes all three — no global state is
mutated, and an album tapped later plays in album order. Its own costs are real
though. The car's toggle would then read "off" while shuffled audio plays, which
is a lie of exactly the kind this decision was trying to avoid; turning it *on*
would shuffle an already-shuffled list; turning it *off* would not restore the
real running order, because the order the player holds is one this code invented;
and re-shuffling would mean leaving and re-entering the row.

So both options are wrong in different places, and the honest summary is that
this was decided on one axis — consistency with the browse/playback spec's
"shuffling belongs to the player" — before the usability cost of the specific
toggle being deferred to was understood. Neither option dominates, which is the
argument for making it a choice rather than a decision. Tracked in [#31]; the
likely shape is a preference, once there is a settings surface to hold one.

## Decision: icons are vendored Material Symbols

Five rows and tabs were borrowing icons that meant something else — More and
Select Library wore the Playlists glyph, and every library-picker row fell back
to the car's own placeholder, a music note on a colour picked per row that
carries no meaning.

The Material icon set is **not distributed as an Android drawable dependency**,
which is worth recording because it looks like it should be. Of the 282 drawables
in the built APK, 143 are material-components internals (chip checkmarks, dialog
scaffolding — that library ships *widgets*, and only the icons its widgets draw),
75 are media3-session's player glyphs, and none of the general set is present:
`library_music`, `more_horiz`, `folder`, `queue_music`, `dns` and
`switch_account` all return zero hits.

The reason is that `res/drawable` has no tree-shaking. Everything in `res/` ships,
so an icon AAR would add thousands of vectors to every app that touched it. The
one place it *is* solved is Compose, where `material-icons-extended` ships icons
as `ImageVector` **code** that R8 strips per symbol — unusable here, because this
is a Views app that needs a real drawable resource for `artworkUri`.

So vendoring is the sanctioned workflow, and this repo had already established
it: the three original `ic_aa_*` tab icons are hand-copied Material Symbols
paths. Four more were added the same way (`more`, `library`, `server`, `info`,
`warning`), keeping upstream's path data verbatim and supplying the missing
viewBox origin with a group `translateY` rather than rewriting every coordinate,
so each glyph stays diffable against upstream.

The one exception is the shuffle row, which uses media3's own
`media3_icon_shuffle_on`. It is a playback control, which is media3's domain, and
the heart already relies on media3 icon constants. That drawable has no
`public.xml` entry, so a rename on upgrade would break the reference — as a
compile error, which is why depending on it is acceptable.

### The picker's two sentence-shaped rows carry severity instead

`messageRow` is a warning: all four of its strings are dead ends — plex.tv
unreachable, server gone, server unreachable, no music libraries on it — so none
of them lets the user pick anything. `confirmationRow` is info, because it
reports a selection that succeeded.

Both are baked into their functions rather than passed per call, which keeps the
media-id round trip honest: `MediaBrowserTree` rebuilds a message row **from its
id alone** when tapped, so a per-message severity would have to be encoded into
the id to survive that.

## Decision: the server list ticks the current server

`LibrarySelection.isCurrentServer` matches on **machine identifier only**, with
no `serverUri` fallback, unlike its sibling `isCurrent`.

Listing servers deliberately does not probe them, so a server row has no resolved
address to compare against: plex.tv advertises a *list* of candidate addresses per
server while the session holds the single one that answered, and comparing
against the wrong candidate would tick a server the user is merely browsing past.
A session saved before that field existed therefore shows no tick anywhere rather
than a guess — the same fail-closed choice `isCurrent` makes.

`libraryRowTitle` became `rowTitle`, since it now titles both kinds of row.

## Decision: the car draws its own transport controls

`BaseSessionCallback` pinned previous, play/pause and next into
`setMediaButtonPreferences` ahead of the custom buttons. Upstream added that for
the **Android 13 notification** (#663, #787). This fork has no phone audience,
and in the car it produced a mini player with **no next button at all** — the
car's rating star on the left, "Previous" on the right.

`com.android.car.media` is a legacy `MediaControllerCompat` client, so media3
publishes preferred buttons into the PlaybackState custom-action list; `dumpsys`
showed custom actions literally named "Previous" and "Next". The mini player fills
its two side slots from that list instead of drawing transport. Pinning transport
by hand stopped it being transport, as far as the car was concerned, and made it
just another extra.

Removed, the car draws its own from the `actions` bitmask, which already
advertises play/pause and both skips from the player's available commands. The
browse UI then reports `skip_prev` / `play_pause` / `skip_next` view ids, and both
surfaces read correctly — the expanded row's transport is also in order rather
than reversed.

The risk #663 described, a custom button taking a transport slot on the last
track, does not apply once transport is not competing for those slots.

## Decision: a real heart in the transport row, state in the extras bundle

The control left of transport was a **star**, and it was not ours. It was the
car's own rating widget, drawn because the metadata carried a `HeartRating`;
tapping it made the same `/:/rate?rating=10` call our heart button does. So there
were two affordances for one action and the prominent one was the car's.

That star also outranked our heart button, which is why the heart was stuck
behind the overflow. Publishing no rating removes the star and our heart takes
the slot.

Hearted state therefore moves into the extras bundle as `EXTRA_HEARTED`, resolved
by one reader, `readHearted`: `userRating` wins when present, because that is
where a tap lands via `applyRatingToQueue`, and the mapped-in extra is the
fallback. Both call sites go through it — the button's filled-versus-outline
choice, and the toggle's decision whether to send `rating=10` or `rating=-1`. If
those two disagree, the first tap on an already-hearted track re-hearts it
instead of clearing it.

This knowingly undoes the browse/playback spec's "the heart and the stars are one
field". That claim remains true of Plex, where the heart *is* `userRating=10`;
what changed is that publishing the field to media3 has a side effect in the car
that the earlier spec had no way to anticipate.

Measured on a track Plex held at `userRating=10.0`, cold started so nothing could
come from a tap:

| | before | after |
|---|---|---|
| cold load | unfilled, `Toggle Heart off` | **filled, `Toggle Heart on`** |
| first tap | `rating=10`, re-hearts | **`rating=-1`, Plex cleared** |

Six existing tests asserted the old contract and were updated rather than
dropped. One new test pins that a tap outranks the mapped value, and an
`assertNull` on `userRating` guards the reason none is published.

## What the car owns, and three experiments proving it

The arrangement of the transport row is not ours, and this is recorded because
each attempt looked plausible:

- **Reordering the buttons we send changes nothing.** Sending
  `[next, playPause, previous]` produced a row identical to
  `[previous, playPause, next]`. The car's arrangement is fixed, not derived from
  our order. An earlier reading of the row as "mirrored against our list" was a
  coincidence.
- **Slots do not promote a custom button into the row.**
  `SLOT_FORWARD_SECONDARY` and declaring no slots at all were both tried on the
  heart; the row was unchanged either way.
- **The car will show one custom control there, and prefers its own.** The heart
  never appeared beside transport while a rating was published, and appeared
  immediately once it was not. So the row does accept a custom button — the
  rating widget was simply outranking it.

The heart therefore cannot be positioned to the *right* of transport, which is
where it was wanted, on the grounds that it is used rarely. Nothing available to
an app expresses that.

## Corrections, recorded rather than deleted

In the spirit of the browse/playback spec's own recorded error, three conclusions
reached during this sweep were wrong in ways a later reader might repeat.

**A blank Now Playing screen was diagnosed as an app bug.** It is an artifact of
the development loop: `adb install -r` kills our process while
`com.android.car.media` stays bound to the session that died with it, so the UI
renders empty while a freshly started service plays on — `dumpsys media_session`
reporting `state=3` and an advancing position throughout. Restarting only the car
app restored the UI bound to the same track at the same position, proving playback
had never stopped. Now documented in CLAUDE.md beside the multi-user
`/data/user/10` note, which is the same shape of hazard: the tooling makes a
working app look broken.

**"Custom buttons can never render in the transport row" was asserted and is
false.** See the third experiment above.

**"Un-hearting is broken" was asserted from a misread log.** Two lines matching
`rating=10` were the `--> GET` and `<-- 200 OK` of a *single* request, not two
taps. Tested properly afterwards, one tap per request, correct direction each
time. The lesson that generalises: in this codebase's logging, every HTTP call
appears at least twice, so counting log lines is not counting events.

## Dropped, deliberately

**Stale progress bars on browse rows** — [#30]. The bars belong to
`com.android.car.media`'s own process state; we publish no completion data at
all. They clear correctly on an in-timeline transition and go stale when a
selection replaces the timeline, which is what tapping a browse row does.
`notifyChildrenChanged` cannot nudge a rebind, because the car subscribes to root
rather than to the browsed node. A `ForwardingPlayer` translating a content-equal
`setMediaItems` into a `seekTo` would fix it, and was rejected: a structural
change inside `BaseMediaService`'s player construction, in service of a cosmetic
artifact in AOSP's reference UI, which a shipping head unit may not even
reproduce.

**Tapping a track under shuffle plays a different track** — [#29]. Found while
testing something else. `onSetMediaItems` echoes the car's `startIndex`, which is
`-1` for a browse tap, so ExoPlayer opens at the shuffled head. Filed rather than
fixed because the fix has to distinguish a shuffle row's deliberately random
opener from a tapped track that must be honoured, and that deserves its own pass.

**Hearting that returned 200 and did not persist.** Observed once, early, and not
reproduced afterwards. Recorded only so a future sighting is not treated as new.

**Choosing between app-side and player-side shuffle** — [#31]. Today's rows
defer to the player's toggle, with the costs written up above. Left open rather
than reversed, because neither option dominates.

[#29]: https://github.com/codingismy11to7/siskin/issues/29
[#30]: https://github.com/codingismy11to7/siskin/issues/30
[#31]: https://github.com/codingismy11to7/siskin/issues/31

## Not in scope

Moving the heart to the right of the transport controls — nothing available to an
app expresses it. Restyling the car's rating widget. Any settings surface to
choose grid-versus-list per tab. The `com.cappielloantonio.tempo` package rename.
