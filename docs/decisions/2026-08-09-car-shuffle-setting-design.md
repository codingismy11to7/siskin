# Use the car's shuffle becomes a setting

Closes #31, which asked whether a shuffle row should defer to the car's global
shuffle toggle or hand over an already-shuffled queue. The issue concluded that
neither option dominates, and that the answer was therefore a preference rather
than a decision — once there was a settings surface to hold one. There is one
now, behind the car's gear, and it already holds continuous play.

## What the setting is

A boolean, `car_shuffle`, defaulting to **true**. Read through
`Preferences.isCarShuffleEnabled()` and written by
`Preferences.setCarShuffleEnabled()`, in the same shape as the continuous play
pair beside it.

The row reads **"Use the car's shuffle"**. Its neighbour is "Keep the music
playing", so the register is plain language rather than mechanism jargon; the
label names the control being deferred to — the shuffle button in Now Playing —
rather than describing an implementation with a word like "native".

The internal name follows the label. `isCarShuffleEnabled`, not
`isNativeShuffleEnabled`: reintroducing in code the word that was rejected for
the UI would leave the two describing the same flag differently.

## What it controls, and what it does not

Exactly one thing: what tapping **"Shuffle this artist"** or **"Shuffle this
playlist"** does.

It does not touch:

- **The Now Playing shuffle button.** It still toggles the player by hand under
  either setting. With the setting off, turning it on shuffles an already
  shuffled queue — harmless, and the honest consequence of the queue being ours.
- **Continuous play's random tier.** `PlexMixRepository` asks the server for
  `sort=random` and is a different feature that happens to share a word.

## The two behaviours

| | On — the default, and today's behaviour | Off |
|---|---|---|
| Queue handed to the player | tracks in library order | the same tracks, `.shuffled()` |
| `player.shuffleModeEnabled` | set `true` | set `false` |
| Opens at | `Random.nextInt(size)` | `0` |

Under **on**, shuffling belongs to the player: the queue is the artist's real
running order, and turning the car's toggle off mid-listen falls back to it.
That is the property #31 recorded as the reason the current design was chosen,
and it survives as the default.

Under **off**, shuffling belongs to us. The player walks a queue we shuffled,
in order, from the top — which is why the opener is index 0 rather than a random
draw. The head of a shuffled list is already random, and drawing again would
skip a prefix of the queue for no reason.

Re-tapping the row re-fetches and re-shuffles, so a new order costs one trip
back to the row. That is the same cost #31 named for this option, not a new one.

### Why client-side rather than `sort=random`

The server can shuffle: `LibraryClient.SORT_RANDOM` exists, `getSectionContent`
already takes a `sort`, and the artist path would cost one argument.

The playlist path would not. `searchClient.getPlaylistItems` has no `sort`
parameter, so server-side shuffle means adding one *and* establishing that PMS
honours `random` on a playlist listing, which is unverified. Splitting the
difference — server-side for artists, client-side for playlists — puts two
mechanisms behind one switch and lets the two rows drift apart.

`.shuffled()` is one transform for both rows, testable without a server, and
cannot change behaviour when a server is upgraded.

The cost is real and worth recording: `ConstantsAA.MAX_ITEMS` is 500, so an
artist with more than 500 tracks gets the first 500 *in library order*,
shuffled — never the tail of the catalogue. Server-side `random` would have
sampled across the whole of it. A sampled artist in this library ran to 297
tracks, so the cap is reachable but not commonly reached; if it starts to bite,
the fix is to raise the cap or revisit this choice for the artist row alone.

## Why the default stays on

Turning it off would make the new path what ships, and #31's three complaints —
the toggle persists, it leaks into unrelated listening, it is buried — would go
away for everyone rather than for whoever finds the row.

It stays on anyway. An existing install keeps behaving as it did, and the car's
shuffle button keeps telling the truth about what the player is doing, which is
the property the original design was built around and the one a driver reads at
a glance. The lived complaint in #31 is fixable in two taps once the row exists,
and the setting is where that fix belongs.

This is the opposite call from the continuous play spec, which flipped its
default. The difference is that continuous play had no writer at all — its
default was not a default but the value, unreachable and therefore not a choice.
This key has a writer from the day it lands.

## Where the code changes

`MediaLibraryServiceCallback`, in four places. It is where both rows converge:
`resolveQueueForItem` serves the artist and playlist rows alike, and the player
may only be touched from `onSetMediaItems` and `onAddMediaItems`, which run on
the session's application thread.

- **`resolveQueueForItem`**, the `shuffleTracks != null` branch — `.shuffled()`
  when the setting is off. One transform, both rows, at the point the queue is
  unwrapped from its `LibraryResult`.
- **`onSetMediaItems`** — `setShuffleForTap(shuffleRow && carShuffle, player)`.
  The existing call passes `shuffling` straight through.
- **`openingPositionIn`** — a third branch, between the two that exist. It needs
  both facts rather than today's single `shuffling` flag, because a shuffle row
  now has two openers:

      items.isEmpty()          -> carStartIndex
      shuffleRow && carShuffle -> Random.nextInt(items.size)   // unchanged
      shuffleRow               -> 0                            // new
      else                     -> indexOfFirst { … } ?: carStartIndex

- **`enableShuffleIfShuffleRow`**, reached from `onAddMediaItems` — takes the
  setting and becomes `setShuffleForAddedRow`, writing
  `player.shuffleModeEnabled = carShuffle` for a shuffle row. Declining to
  enable is not enough: the toggle persists across process death, so a car that
  adds rather than sets would hand over an already-shuffled queue to a player
  still shuffling from an earlier listen. The rename follows the write becoming
  total.

`PlexBrowseRepository` is **unchanged**. The shuffle lives at the tap, not at the
fetch, so `getArtistTracks` and `getPlaylistTracksForShuffle` keep returning
library order under both settings. Only `getArtistTracks`' KDoc reasoning
changes; `getPlaylistTracksForShuffle`'s says nothing about shuffling, only why
the row is left out of the queue.

### The opening-position branch is load-bearing

It cannot be left to fall through. Today's `else` branch is

    items.indexOfFirst { it.mediaId == tapped.mediaId }.takeIf { it >= 0 } ?: carStartIndex

and the tapped item here is the shuffle row, which is *deliberately* absent from
the queue it builds — `getPlaylistTracksForShuffle` exists precisely to leave it
out, because a queue containing the row would hold a playable item with no
stream. So `indexOfFirst` returns -1 and the expression falls back to
`carStartIndex`, which a browse tap sets to `C.INDEX_UNSET`.

media3 turns that into "open at the player's default position", which with
shuffle off is item 0 — the right answer by accident. Naming it is what
`openingPositionIn`'s KDoc already argues for on the other branch: the car
leaves the opener entirely to us, and an opener that depends on media3's
interpretation of an unset index is not one we chose.

## Comments that stop being true

Four KDocs currently argue for today's behaviour as *the* behaviour rather than
as one of two, and are rewritten to say which branch they describe. Per the
repository's convention these are load-bearing and get corrected, not deleted —
each still documents a real hazard on the branch it belongs to.

- `PlexBrowseRepository.getArtistTracks` — "left unshuffled on purpose: the
  player owns shuffling". True of the function, whose contract is unchanged; the
  reason is now one setting's reason.
- `MediaLibraryServiceCallback.setShuffleForTap` — "Total rather than
  enable-only, and that is the whole point". The totality still matters, and
  still fixes the sticking it was written for.
- `openingPositionIn` — the random-opener paragraph now covers one of two
  openers.
- `enableShuffleIfShuffleRow` — its argument is about continuous play topping up
  the queue mid-listen, and that hazard is untouched by the setting. What
  changes is which property answers it: no longer "this only ever turns shuffle
  on", but "only a shuffle row writes the toggle here, and a mix track is never
  one". The KDoc keeps the reasoning and changes its subject.

## Settings row

`addToggle` before the Sign out choice, after continuous play. Sign out is a
destructive terminal action and stays last; the toggles are an open-ended list
above it, which is the arrangement `applyArrangement` already chose.

The row copies the continuous play row exactly: the row is the tap target and
the switch is `isClickable = false`, because a switch thumb is a phone-sized
target and this is a head unit — a non-clickable switch never consumes the
touch, so dispatch falls through to the row.

The label needs four translations, in `values-{de,es,fr,it}`. Siskin ships five
complete locales and `MissingTranslation` is at zero, so a missing one is a real
lint defect rather than baseline noise.

## Testing

- **`PreferencesCarShuffleTest`** — the key defaults to true and round-trips.
- **`MediaLibrarySessionCallbackShuffleTest`**, extended — with the setting off,
  a shuffle-row tap builds a queue that is a permutation of the fetched tracks,
  leaves `shuffleModeEnabled` false, and opens at index 0. The three existing
  tests assert the on branch and must pass unchanged.
- The `onAddMediaItems` case beside
  `addingTracksToARunningQueueLeavesShuffleAlone` — with the setting off, a
  shuffle row added rather than set clears the toggle rather than merely
  declining to set it.
- **`PlexSignInSettingsTest`** — the row is present with its label, its switch
  is `isClickable == false`, and tapping the row writes the preference.

Every test touching this key must reset it in `@Before`. Robolectric caches
`SharedPreferences` statically across test methods, so a test that assumes
absence reads whatever the previous method wrote — and this key's default is
true, which makes an accidental leak of `false` look like a passing on-branch
test.

## Out of scope

**#35** — the overflow shuffle button offering the action it is already in — is
not fixed here. The manual toggle and the custom layout that publishes it are
untouched. The issue notes that resolving #31 app-side would change that
button's behaviour; with the default staying on, the button's behaviour is
unchanged for anyone who does not opt out, and its stale-label bug is a separate
defect in `selectCommandButton`.
