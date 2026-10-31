# Select Library is hidden unless Settings turns it on

**Date:** 2026-10-30
**Status:** Approved
**Issue:** #288
**Amends:** the "Why not a settings screen" section of
`2026-07-29-more-tab-library-selection-design.md`

## Context

More ends with **Select Library**, pinned after whatever destinations the tab
order leaves there. It picks a server, then a library: the first level lists
the servers the account can see, and choosing a library on another server
commits a server change along with it.

Most accounts have one server with one library. For them the row opens a server
list with a single ticked entry, then a library list with a single ticked
entry. Tapping that library shows one "Now browsing" row, which is not a
heading and leads nowhere. A tester opened it expecting a folder browser, found
nothing to do, and read the screen as stuck. The row cannot help most drivers,
and it invites exactly that misreading.

## Decision

Select Library is off by default, behind a **Show library quick switch** switch
in Settings. The label names what the row is for rather than repeating its
title: it is the only way to change library while driving; sign-in and the
debug Server tab are parked-only. The row keeps its title. While the switch is
off, `MediaBrowserTree.buildTree()` does not add `SELECT_LIBRARY_ID` under More.
While it is on, the row is pinned last, as now.

More still has rows with the switch off, because `BrowseTabOrder.DEFAULT_ORDER`
holds five destinations and only three fit at the root.

### Off for existing installs too

The default flips for everyone, including installs that have switched library
before. We could have kept the row on for an install that has used it, but the
feature has very few users. Not carrying a migration is worth more than
sparing them one trip to Settings.

Hiding the row also makes server switching parked-only by default, since the
row is the only route to it while driving. An account with one library on each
of two servers is affected as well as one with several libraries on a server.

### A switch, not a library count

The row could instead appear only when the account sees more than one library.
Asking at render time would put a plex.tv call in front of a tab that today
renders from memory, so the realistic form is a count remembered in
preferences.

That count goes stale in the bad direction. Once the row is hidden, an account
that gains a library or server keeps it hidden unless a background refresh
notices, and there is no switch to turn it on by hand. Keeping it current is
also not one call: `getServers()` needs plex.tv, counting libraries needs each
server, and some may be unreachable from the car. A switch is simple and cannot
get stuck hidden. Its cost is the one trip to Settings accepted above.

### The parked-only trade-off

The 2026-07-29 spec put library selection in More, not in Settings, because
the settings screen is parked-only: under DO=255 the car dims its gear icon
and ignores taps. Choosing a library is a browse-scope decision, so it should
work while moving.

That still holds for the row. Once shown, Select Library works while driving,
as before. Only *showing* it is now parked-only. Turning the row on is a
one-time setup step, not something done on the move, so leaving it in Settings
costs nothing that matters.

### Reaching the car

The tree is held in memory, and `buildTree()` runs on every `onGetLibraryRoot`
and every `invalidateRoot`, so flipping the switch has to tell the car that
More changed. The switch's callback calls
`BrowseTreeInvalidator.invalidateMore()`, which rebuilds the tree and
invalidates More alone, since the root's children do not change. Without that
invalidation, the car keeps showing its cached list (measured for tab order in
`2026-08-14-customizable-browse-tabs-design.md`).

The invalidation is triggered by the writer, like the neighbouring Artist Mix
and Artists-by-initial switches, rather than by a preference watcher like the
one `2026-10-26-tab-order-invalidation-design.md` added for the tab order. No
reset, restore or migration writer for this key is foreseen, so the switch is
the only writer, and a single tap needs no debounce. If a second writer
appears, it should move to the watcher.

### Placement

The switch sits next to **Customize tabs**. Both decide what More shows, so
they read as one group.

## Consequences

- The label needs de/es/fr/it translations, and the change earns a changelog
  bullet.
- A driver who switches libraries and upgrades will find the row gone until
  they turn the switch on.
