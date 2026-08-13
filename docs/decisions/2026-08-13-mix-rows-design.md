# The shuffle rows become Mixes, and stop touching shuffle mode

**Date:** 2026-08-13
**Status:** Approved
**Supersedes:** `2026-08-09-car-shuffle-setting-design.md`, and the tap-clears-shuffle
half of `2026-08-02-shuffle-follows-the-tap-design.md`

## Context

`2026-08-09-car-shuffle-setting-design.md` shipped a preference, `car_shuffle`,
answering #31: should "Shuffle this artist" defer to the car's global shuffle
toggle, or hand the player a queue this app shuffled itself? That design
concluded neither option dominates, so the answer was a preference rather than a
decision.

0.99.3 put it on a head unit, and driving on it produced the observation this
change comes from: **the car's native shuffle UI is bad enough that seeing the
shuffled queue is worth more than anything the global toggle offers.** So the
setting wanted flipping.

Flipping the default was the obvious change and it is not the one made here,
because of what it costs. The old design's argument for deferring was that "the
car's shuffle button keeps telling the truth about what the player is doing,"
and a pre-shuffled queue played with shuffle off makes the button read "not
shuffling" while a shuffle plays. Under a row called **Shuffle this artist**
that is a genuine contradiction, and no default setting resolves it.

## The decision

**The three rows are renamed to Mixes** -- Artist Mix, Playlist Mix, Decade Mix
-- and the tie #31 could not break disappears with the word that caused it.

A mix is a queue somebody handed you. Shuffle is a mode the player is in. Those
are different things, and only the shared word made them look like two answers
to one question:

- Tapping a row named **Artist Mix** does not say anything about what the
  transport control should be doing, so leaving the global toggle alone is not a
  half-measure -- it is what the name means.
- The car's shuffle button goes on telling the truth, because nothing else
  writes it. It describes the player's mode, which is exactly what it claims to.
- The queue on screen is the queue that plays, which is what made this worth
  changing.

`car_shuffle` is deleted rather than flipped. With the rows renamed, its "on"
position would make tapping a Mix flip a global transport toggle, which is the
behaviour its own label now denies. A setting whose two positions are "do what
the row says" and "also do something the row says it does not" is not a choice
worth offering.

This also settles #31's actual complaints -- that the toggle persists, leaks
into unrelated listening, and is buried -- at the root instead of two taps deep.
Nothing global is touched, so nothing leaks.

## Nothing writes `shuffleModeEnabled` any more

Two writers existed and both are gone.

`setShuffleForAddedRow` set the toggle from the preference, and goes with it.

`setShuffleForTap` is the more interesting deletion, because it was correct
right up until this change. It cleared shuffle on every non-Mix tap, and
`2026-08-02-shuffle-follows-the-tap-design.md` argued that well: a row could
turn shuffle on, so shuffle stuck, so one tap on "Shuffle this artist" left
every later track tap shuffled. Clearing it made the tapped row decide.

**Once no row turns shuffle on, that write has nothing left to clean up.** The
only shuffle it can encounter is one the driver switched on deliberately with
the car's own control -- and turning that off because they then tapped a song is
the same category of unrequested global change this whole design removes. So it
goes, and the invariant is now total: no tap, of any row, on either the set or
the add path, writes the player's shuffle mode.

The consequence is worth stating because it is the one behaviour that will look
odd: **with the car's shuffle already on, a Mix plays a shuffled queue in
shuffle order.** Random either way, but the visible queue will not match the
play order. That is the driver's own control doing what it says; taking it back
would be the behaviour this design exists to remove.

## What stays as it was

- **The media ids keep their `[shuffleArtistID]` values.** An id is a wire
  format, not a label. The car caches browse nodes and echoes ids back on a tap,
  so an installed build holds these exact strings; changing them would leave
  cached rows unrecognised, and a row the callback cannot dispatch on is a
  playable row with no stream. The constants are renamed, the values are not.
- **A Mix still opens at index 0**, unchanged from the setting's off branch. The
  head of a shuffled list is already a random draw, and drawing again would skip
  a prefix of the queue for nothing.
- **The decade cache-hit path shuffles too.** It feeds the same transform every
  Mix goes through, so tapping Decade Mix straight after browsing that decade
  replays the list already on screen, mixed. Its tests moved from asserting a
  sequence to asserting a multiset, which is what they meant all along.

## Naming

Nouns, not imperatives -- "Artist Mix" rather than "Mix this artist". The noun
names a thing you are handed; the imperative names an action you trigger, which
is the distinction the whole design rests on. It breaks parallelism with the
browse tree's other rows, and that is an acceptable price for the row whose
category is the point.

"Mix" survives untranslated as the head noun in all five locales, with only the
grammar around it localised -- `Interpreten-Mix`, `Mix de l'artiste`. The word is
borrowed into de/es/fr/it in music contexts, where the native alternatives
(`Mezcla`, `Mischung`) read as audio-engineering terms rather than as a
playlist. German takes `Wiedergabelisten-Mix` because the rest of that locale
already says `Wiedergabelisten` for the Playlists tab.

## What this does not buy

- **No control over the mix.** A Mix is a uniform shuffle of everything the row
  stands for. Weighting by rating or recency is a different feature.
- **No help for a driver who wants the queue in library order.** That was the
  `car_shuffle` "on" branch, and it is gone. Tapping an album or a playlist
  still plays it in order -- only the three Mix rows shuffle.
- **No change to the transport shuffle labels.** `Enable shuffle mode` and
  `Disable shuffle mode` are about the player's mode, which is genuinely what
  they control, and they keep the word.
