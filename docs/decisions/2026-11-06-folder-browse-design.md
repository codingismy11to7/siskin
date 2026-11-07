# Folders browses a library by its layout on disk

**Date:** 2026-11-06
**Status:** Approved
**Issue:** #295

Builds on the windowing design
(`docs/decisions/2026-08-09-browse-list-windowing-design.md`).

## Context

A driver on an EX30 asked whether Siskin could browse the music library by its
folder structure, as Plexamp can. Siskin browses only by metadata. #295 was a
spike to find out whether Plex allows it and whether it is worth having.

Plexamp has had a Folders view since 2020. Neither it nor Plex Web can play or
queue a whole folder, which fits the endpoint below: it returns one level at a
time and has no recursive form.

## What the server does

Measured on PMS against a section with 553 top-level folders and two root
locations: as the owner, and as a managed Plex Home user (`owned=false`) through
the app's own session on the AAOS emulator.

- **`GET /library/sections/{id}/folder`** returns the root, and
  **`?parent=N`** returns a level below it. Both answered 200 for the owner and
  for the Home user.
- **Folders and tracks both arrive under `Metadata`.** A folder row is only
  `{key, title}`, its key being `/library/sections/{id}/folder?parent=N`: no
  `ratingKey`, no `type`, no artwork. A track row is the same full metadata
  `/children` returns, `parentTitle`, thumbs and `Media`/`Part` included, so it
  plays without a second lookup.
- **Paging is honoured.** `X-Plex-Container-Start`/`-Size` were respected,
  including a request at offset 550 of 553. `totalSize` comes back only when
  they are sent, which Siskin always does.
- **Order is the server's**, by name, and the endpoint takes no sort.
- **Two root locations merge** into one name-ordered root. Nothing marks which
  location a folder came from.
- **`GET /library/sections/{id}/location`** answered 403 for the Home user, so
  nothing here depends on it.
- A level answered in about 25 ms.

**Not measured:** a library shared from another account's server, as opposed to
a Home member's view of the owner's. A friend's share may be restricted
differently. If it fails, it fails as an ordinary HTTP error (below), not
silently.

## Decision

Build it, as a destination in the tab pool.

### Folders joins the tab pool

`FOLDERS_ID` is appended last to `BrowseTabOrder.DEFAULT_ORDER`, so a fresh
install and an upgrade alike see it as More's last row (`resolve`'s append rule
places it last for anyone with a saved order). Customize tabs can promote it to
a root tab. There is no Settings switch.

### A typed request, never a server-supplied URL

A folder row's `key` is a path the server chose. Following it with `getByPath`
would need a guard like `isSafeHubKey` to keep the token from going wherever a
key points. Instead, `LibraryClient.getFolder(section, parent, start, size)`
requests the endpoint itself, and Siskin keeps only the digits after
`parent=`, and only from a key naming the current section's folder endpoint.

They travel as a `FolderToken` value class in `PlexKeys.kt`. The community API
spec warns that a folder token is not a rating key, which is the confusion
`SectionKey` and `RatingKey` already exist to make uncompilable.

Folders and tracks are both read from `Metadata`, which is where both captures
(owner and Home member) put them. A key under `/library/metadata/` with `type`
`track` is a track, a key naming this section's folder endpoint is a folder, and
anything else is dropped and logged.

### Ids carry the library they belong to

A folder is `FOLDER_ID + scope + token`, and a window in one is
`FOLDER_WINDOW_ID + scope + token + start`. The scope is the one hub ids carry,
from `CompositeArt.currentScope`. A token is a number that means something only
on one server, so a row the car cached before a library switch would otherwise
open whatever folder that number names on the new one. A scope that no longer
matches makes no request and answers the empty-level row, as `followHubKey`
does for a stale hub.

### Large levels are windowed

A level larger than `Constants.WINDOW_SIZE` becomes range windows labelled by
their boundary titles, as Artists and Albums do. The 2026-08-09 measurements
still apply: the car keeps roughly the first 227 KB of a list and drops the
rest without a sign, parked or not, so the 553-folder root above would end
about two-thirds of the way through the alphabet with nothing to say more
exists. `windowed` and `windowRows` are generalised over the request they page,
so the three destinations share one implementation of the labels, the boundary
timeout and the positional fallback.

Windows are for reaching everything while parked. They do not make a large tree
usable while driving, where the car's own restrictions limit any long list.

### Rows

A folder shows its name exactly as on disk with one generic folder icon. The
names are the point for anyone who browses this way, and rewriting them
(`Academy Is…, The` to `The Academy Is…`) guesses at conventions. Borrowing a
folder's artwork from its first track would cost a request per row, and a
folder of folders has no track to borrow from.

Every folder level is a list, never a grid, because a folder's name is its only
content and a grid truncates it.

Tracks look like tracks anywhere else.

There is no shuffle row and no Play Folder action. The car's shuffle control
works on a folder's tracks like any other queue.

### Tapping a track queues the list it is in

A tapped folder track queues through the same path as every other browse track:
the playable rows of the list it was tapped in, starting at the tapped track.
Folder rows are never queued, and nothing recurses: Plex has no recursive call,
so it would cost a request per subfolder. For a folder longer than one range,
the queue is the range on screen, at most 50 tracks.

There is no folder-aware queue, because there is nothing for one to read. A
car's tap reaches Siskin with its extras stripped (measured on the emulator:
empty request extras, null metadata extras), so no code on the queue side can
see which folder the track came from.
The per-list snapshot the tap does reach already queues the right list,
including after the driver presses back.

### Errors

`getFolder` goes through the existing `fetch`, so a 401/403 becomes the sign-in
affordance, a transport failure reads as unreachable, and a 500 (which a
corrupted Plex database produces on this endpoint) is an ordinary browse error.
An empty level, or one whose every row was dropped, shows the normal message
row.

## Alternatives considered

- **Following `key` with `getByPath`.** Rejected: one more path that follows
  server-supplied URLs, whose guard has to stay correct forever.
- **A capped list with no windows.** Rejected for the reason windowing exists:
  the tail of the alphabet becomes unreachable and nothing says so.
- **Fetching a whole level and grouping it A to Z on the device.** Reaches
  everything in one request, but adds a second grouping scheme beside the one
  already built, and a letter with more folders than fit hits the same wall.
- **A Settings switch, off by default**, as Select Library has. Rejected: a
  preference is code to maintain and test for no payoff while More is short
  (three rows with Folders: Albums, Discover, Folders). Worth revisiting if More grows long enough that an
  unused row costs something.
- **Hiding Folders on servers the account does not own.** Unnecessary: a Home member's
  requests succeeded.
- **`/services/browse`, the server's file system browser.** Rejected: it lists
  every file on disk, including ones Plex never scanned, with no playable
  metadata.

## Consequences

- "Folders" and the empty-folder string need de/es/fr/it translations, and the
  change earns a changelog bullet.
- Folders sits deeper than any other destination: More, Folders, a window, an
  artist, an album, then tracks. That path is five levels deep, which
  `2026-07-29-more-tab-library-selection-design.md` measured rendering at
  DO=255 on this emulator; it could not be re-measured here because the
  emulator's VHAL now reports vehicle speed 0 about ten times a second,
  overriding an injected speed, and `enable-uxr` needs a permission only root
  holds. A head unit that enforces the advertised depth of 3 would cut the path
  short, and deeper on-disk layouts (an album with CD1/CD2 subfolders) go
  further still.
- A friend's shared server is untested and may refuse the endpoint.
