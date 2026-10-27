# The media service tells the car about tab-order writes

**Date:** 2026-10-26
**Status:** Approved
**Issue:** #266
**Supersedes:** the "Applying the change" section of
`2026-08-14-customizable-browse-tabs-design.md`

## Context

The tab order is one preference, `browse_tab_order`, and the car learns about a
change to it only when something calls `invalidateRoot()` and
`invalidateNode(MORE_ID, 0)`. Until now that something was the reorder screen:
`BrowseTabOrderFragment.onStop` compared the saved order with the order it
believed the car had, and invalidated if they differed.

That put the duty on the writer. There is one writer today, but the next one —
a reset row, a restore, a migration — would have to know to make both calls,
and forgetting gives exactly the stale tabs of #264. #265 fixed that bug for
the screen. It did not fix it for the screen's successors.

## Decision

The media service watches the preference itself. A new
`service/BrowseTabOrderWatcher` registers an `OnSharedPreferenceChangeListener`
for `browse_tab_order`. A write schedules a check on the main looper 500 ms
later, and any later write within that window replaces the scheduled check.
When the check runs, it resolves the saved order and compares it with the one
the watcher last reported. If they differ, it calls
`BrowseTreeInvalidator.invalidateTabOrder()`, which invalidates the root and
More.

`BaseMediaService` owns one watcher per service instance and shares its main
handler with it. It starts the watcher in `onCreate`, right after
`BrowseTreeInvalidator.attach`, and stops it in `onDestroy`. The fragment's `onStop` and its `orderTheCarHas` are deleted. The
screen now only writes.

### Why this covers every writer

A listener on the key reacts to the write itself, not to a code path. A writer
that goes through `Preferences.setBrowseTabOrder` is covered, and so is one
that edits the key directly.

`Editor.clear()` counts as a write too. From API 30 it notifies listeners with
a null key rather than one per removed key, so the watcher treats a null key as
a possible change and lets the comparison decide. Below API 30, `clear()`
notifies nobody, and the car would learn of a cleared order only on its next
connect. That cannot be fixed from a listener.

The listener only runs while the service is alive. That is not a gap: the
invalidation has no one to reach without a live session, and the session lives
exactly as long as the service. A write made while the service is down is read
when the tree is next built from preferences.

### Why debounced

The reorder screen saves on every adjacent swap, so that killing the app
mid-drag cannot lose the change (see `moveAndPersist`). Moving a row from the
bottom to the top is four writes in quick succession. Invalidating on each one
would rebuild the root four times and make a subscribed car re-fetch the root
and More four times. With the debounce, a drag that settles gives one
invalidation. A slow drag with pauses may give more than one, which is harmless.

500 ms is long enough to cover the swaps in one gesture and short enough that
the car is up to date long before anyone looks at it again.

### Why compare before invalidating

A write can leave the resolved order unchanged, for example a drag that ends
where it started or a rewrite of the same value. Comparing against the last
order the watcher reported skips those writes. The fragment's comparison existed
for a different reason: a configuration change also stops the fragment. That
case is gone along with `onStop`. The comparison stays because it is cheap and
unchanged writes are real.

The baseline is the resolved order at `start()`. The tree is built from the
same preference, so at that moment they agree.

The baseline is what the watcher last reported, not necessarily what the car
last received. `invalidateTree()` on sign-in or account removal, and a
reconnect, also send the current order without updating it. The worst case is
a change reported twice, which costs one extra re-fetch of the root and More.

## What is unchanged

The invalidation itself is the same two calls, for the reasons the earlier
design measured. Both calls are needed, because `invalidateRoot()` alone leaves
More serving a cached list. `invalidateTree()` is not used, because it would
re-fetch Playlists, Artists and Albums, whose contents did not change. Those
reasons move with the calls into `BrowseTreeInvalidator.invalidateTabOrder()`,
next to `invalidateTree()`, as the invalidator's other named sequences are.

`invalidateRoot()` must still be called on the main thread. The listener and
the scheduled check both run on the main looper, so that holds.

## What changes for the user

The car redraws about half a second after a drag settles, while the user is
still on the reorder screen, instead of when they leave it. The car's media UI
is not in the foreground then, so the difference cannot be seen. This reverses
the earlier design's "on backing out of the reorder screen". That timing was
chosen to get one invalidation per visit; the debounce now gets one per drag,
without depending on the screen.

## Hazards

`SharedPreferences` holds its listeners in a weak map. A listener created
inline and passed straight to `registerOnSharedPreferenceChangeListener` can be
garbage-collected and go silent with no error. The watcher keeps it in a field.

## Alternatives considered

- **Invalidate inside `Preferences.setBrowseTabOrder`.** This covers every
  caller of the setter but not a write that bypasses it. It would also make a
  `util` class reach into `service/` and hold the debounce state there.
- **Keep `onStop` and add an invalidating setter for other writers.** This is
  the smallest diff, but it keeps two paths, and the next writer still has to
  pick the right one. That is the problem this design exists to remove.

## Testing

`BrowseTabOrderWatcherTest` (Robolectric, paused looper) counts the callback
calls:

- several writes within the window produce one call, after the delay;
- a write that resolves to the same order produces none;
- writes that end where they started produce none;
- a second change after the first settles is reported again;
- `clear()` is reported;
- `stop()` cancels a scheduled check, and later writes produce none;
- a change to another key produces none.

`BrowseTreeInvalidatorTabOrderTest` checks that `invalidateTabOrder()` notifies
both the root and More. That `BaseMediaService` starts and stops the watcher is
not unit-tested, because that would mean constructing the whole media service
under Robolectric. The emulator check below covers it.

On the emulator: move a destination between the root and More, return to the
media app, and confirm that the tab bar and More both show the new order the
first time they are seen.
