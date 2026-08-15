# Customizable browse tabs

**Date:** 2026-08-14
**Status:** Approved

## Context

The browse root holds four tabs and the car silently drops a fifth, so every
destination added since the fork has had to argue for one of three music slots
or accept life under More. Decades lost that argument in
[the decades design](2026-08-09-decades-browse-design.md), and Discover lost it
again in [the hubs design](2026-08-14-hubs-browse-design.md), whose opening line
is the whole problem in one sentence: "Root is capped at four tabs and the
fourth is More, so this nests there."

Twice is a pattern, and the pattern is that the fork keeps deciding on the
user's behalf which three of its destinations matter. That decision is not the
same for everyone. It is not even the same for every library: on the emulator,
against a real server, `GET /playlists?sectionID=4&playlistType=audio` answers
`200 OK` with a 29-byte body — that library has no playlists at all, and
Playlists is the first tab, so the car opens on "Media isn't available for this
list" every cold start. The tab that leads is the tab that is empty.

So the root stops being a constant and becomes a setting.

This does **not** lift the four-tab cap. Four is still what the car renders and
a fifth is still silently dropped. The feature decides *which* four.

## What the user sees

A fifth row in Settings, **Customize tabs**, below "Show artists by initial" and
above Sign out. It is a destination rather than a toggle, so it draws with
`addChoice`'s chevron rather than `addToggle`'s switch.

It opens a second screen in the same activity, replacing the fragment in
`CarSignInActivity`'s existing `car_sign_in_container` with a new
`BrowseTabOrderFragment`. Staying inside `CarSignInActivity` is load-bearing
rather than convenient: that activity is deliberately not marked
`distractionOptimized`, so the platform blocks it while the car is moving. A
reorder screen inherits that for free, and a drag gesture is therefore never
performed in motion. Back returns to Settings rather than leaving the activity.

The screen is one drag-reorderable list with a non-draggable divider row after
position 3, labelled **More**. Rows above the divider are the root tabs, in the
order they appear; rows below live under More. The first row is the tab the car
opens on.

Select Library is not in the list.

### The activity is misnamed, and this makes it worse

`CarSignInActivity` is already the `APPLICATION_PREFERENCES` target and the
sign-in `PendingIntent` target; this adds a third screen to it. `PlexSignInFragment`
is likewise 466 lines rendering both the PIN flow and the settings screen — its
own KDoc concedes it, and `PlexSignInSettingsTest.kt` shows the test names split
before the source did.

Renaming the activity and splitting that fragment is
[#110](https://github.com/codingismy11to7/siskin/issues/110), deliberately not
done here so this PR's diff stays the feature. The cost is real and is being
accepted knowingly: the third screen lands under the wrong name.

What must survive that cleanup is the *reason* the screen lives in this
activity, which is not convenience. The activity has no `distractionOptimized`
metadata, so the platform blocks it while the car is moving, and a reorder
screen inherits that. A restructuring that preserved the class name but moved
this screen elsewhere would silently make a drag gesture available in motion.

## The model

One ordered list of every music destination. The first three become root tabs,
More is permanently the fourth, and everything below the line becomes More's
contents.

Position is the only concept. There is no separate show/hide, which means no
destination can ever be made unreachable, and no state where a user has selected
zero tabs or five. It also means the default-tab feature costs nothing extra:
the car opens on the first root child, so ordering the list *is* choosing the
default tab. That was measured rather than assumed — see below.

`MediaBrowserTree.buildTree()` stops hardcoding its root and reads the resolved
order:

- **Root children** are the first three ids in order, then `MORE_ID` — always
  fourth, always last.
- **More children** are the remaining ids in order, then `SELECT_LIBRARY_ID`,
  pinned last.

A destination's own definition — title, icon, media type,
`browsableChildrenAsGrid` — travels with it and does not depend on where it
sits. This is what makes promotion free rather than a special case: Decades
promoted to root position 0 kept its grid style and its composite artwork with
no code aware that it had moved.

`getChildren()` does not change. It already dispatches on id, and an id's
meaning has never depended on its parent.

### Why Select Library is pinned rather than orderable

It is settings, not music. The hubs design already reached for the same
instinct, keeping "Server Select staying last where it reads as settings", and
making it orderable would allow burying the only route to switching libraries
underneath the destinations it exists to change. Pinning it to the last row of
More means it is always in one known place, and means the drag logic needs no
special case forbidding it from crossing the divider.

## Persistence

A new `Preferences` pair, `getBrowseTabOrder()` and
`setBrowseTabOrder(List<String>)`, on key `browse_tab_order`, storing the
`Constants` ids as a single comma-delimited string. The ids are bracketed
camel-case tokens — `[albumsID]`, `[decadesID]` — so a comma cannot occur inside
one, and the join needs no escaping.

**Not `putStringSet`.** A `SharedPreferences` string set is unordered, and order
is the entire content of this setting. It would survive a first test and shuffle
later.

Storing these ids makes them persisted data. Renaming one stops being a
refactor: the old value reads as unknown and that destination silently returns
to its default position.

The order is written on drop, so a force-quit cannot lose it.

### One rule, three jobs

Reading is not a straight deserialize. A pure `BrowseTabOrder.resolve(saved,
known)` lives in `util/`:

> Take the saved ids that are still known, in saved order. Then append every
> known id that is not in the saved list, in default order.

That single rule covers all three ways the stored list and the shipped list can
disagree:

| Situation | What the rule does |
|---|---|
| First run — nothing saved | Every known id is appended in default order, so the result *is* the default |
| A destination added in a later release | Unknown to the save, so appended last — it lands in More |
| A destination removed in a later release | Still in the save but no longer known, so dropped |

There is therefore no version field, no migration code, and nothing to write
when the next destination lands. Duplicates in a corrupt save collapse, and a
pool of fewer than three destinations yields a shorter root rather than an
exception — not reachable with today's five, but the function should not assume
its own pool size.

The shipped default is exactly today's behaviour — Playlists, Artists, Albums,
Decades — so anyone who never opens the screen sees no change at all.

### Fresh installs and upgrades will disagree about Discover

Recorded here because it will otherwise be found and filed as a bug.

The hubs design puts Discover above Decades as More's first row, so once it
lands the shipped default becomes Playlists, Artists, Albums, Discover, Decades.
An existing user's saved order does not contain Discover, so the append rule
places it *last*, below Decades.

The two are inconsistent, and deliberately so. The alternative — inserting a new
destination at its default rank — pushes everything below it down and can demote
a tab the user deliberately chose into More, which reads as the app forgetting
the setting. Never displacing a chosen tab is worth more than the two cases
agreeing.

## Applying the change

On backing out of the reorder screen, not per drag — one invalidation rather
than one per gesture:

```kotlin
BrowseTreeInvalidator.invalidateRoot()
BrowseTreeInvalidator.invalidateNode(Constants.MORE_ID, 0)
```

**Both calls, because one was measured insufficient.** With `invalidateRoot()`
alone, the tab bar redrew correctly on a demotion while More went on serving a
cached list with the demoted destination missing from it, until the user
navigated away and back. That is exactly the behaviour `invalidateNode`'s KDoc
already describes — "the car caches a browse list and does not re-fetch it when
the user navigates back into it" — arriving at a second call site.

Not `invalidateTree()`, for two reasons. It also invalidates Playlists, Artists
and Albums, forcing re-fetches of large lists whose contents did not change. And
it hardcodes the four ids that this feature makes non-constant, so it would
become actively misleading. A reordered destination always moves between root
and More, so those two ids cover every case.

Threading is unchanged: `invalidateRoot()` must be called on the main thread and
runs synchronously, which holds for a fragment back-callback — the same contract
`CarSignInActivity.onLoginSuccess()` already depends on.

## What was measured

The design rests on emulator measurements rather than inference, taken on the
landscape AAOS API 33 AVD (1024×768, mdpi) against a real Plex server, using a
throwaway build whose root child set could be swapped at runtime.

- **The car does not persist the selected tab.** Force-stopping
  `com.android.car.media` and relaunching through `MEDIA_TEMPLATE` reopened on
  the first root child, not the tab that had been showing. Tab selection is
  in-memory session state, which is what makes ordering equal to choosing the
  default tab.
- **A changed root child set redraws the tab bar in place.** Swapping the root
  from Playlists/Artists/Albums/More to Decades/Albums/Artists/More, with a
  single `notifyChildrenChanged(ROOT_ID, 4, null)`, redrew the bar live: the
  removed tab vanished, the new one appeared at position 0 with its own icon,
  and the two survivors swapped. No back-out and no restart of either app.
  Symmetric on the way back. `invalidateRoot()`'s KDoc had recorded the
  same-children case as verified; this is the different-children case it could
  not speak for.
- **A promoted destination loads its real contents.** Signed in, Decades at root
  position 0 immediately issued `/library/sections/4/decade?type=9` and its
  per-decade artwork queries and rendered as a grid with real covers.
- **Losing the tab you are on is graceful.** The car was showing Playlists when
  the swap removed Playlists; it landed on the new first tab and rendered it,
  with no error and no empty screen.
- **`invalidateRoot()` alone leaves More stale.** As described above.

One measurement was taken signed out, before the rest: the root serves its tabs
without credentials, so the bar was fully present. Everything about content was
re-measured signed in.

## Strings

Four new strings, each needing four translations in
`res/values-{de,es,fr,it}/strings.xml`. `MissingTranslation` is clean, so an
omission is a lint failure rather than noise.

- `car_settings_customize_tabs` — the Settings row
- `car_tab_order_title` — the screen heading, taking the tagline slot the way
  "Settings" already does
- `car_tab_order_drag_handle` — content description on the handle, so a row is
  reachable without the gesture
- `car_tab_order_hint` — "Drag to reorder — the top three become tabs"

The hint is the one of the four that could be cut. The divider may well explain
the model on its own, but this is a novel interaction on a screen reached
rarely, and one line is cheap.

Row labels reuse `browse_playlists`, `browse_artists`, `browse_albums` and
`browse_decades`; the divider reuses `browse_more`, since it names the same tab.
Nothing here wants `translatable="false"`.

## Testing

**Pure JVM, no Robolectric.** `BrowseTabOrderTest` over `resolve()`: an empty
save yields the default, an unknown id is dropped, a known id missing from the
save is appended in default order, a complete save is returned unchanged,
duplicates collapse, and a pool smaller than three does not throw. Extracting
`resolve()` as a pure function over two lists is what buys this — the only
interesting logic in the feature gets tested without a `Context`.

**Robolectric**, for the parts that need one. A `Preferences` round-trip, which
must reset `browse_tab_order` in `@Before` rather than assume its absence,
because Robolectric caches `SharedPreferences` statically across test methods.
And `MediaBrowserTree` composition: root is the first three plus `MORE_ID`, More
is the remainder plus `SELECT_LIBRARY_ID` last, and a promoted destination keeps
its own content style and media type.

**Deliberately untested:** the drag interaction and the car's redraw. Neither is
reachable from a JVM unit test. Both were measured on the emulator instead, and
that is recorded above so the gap is a decision rather than an oversight.

## Alternatives considered

**Chevrons instead of drag.** Up/down buttons per row avoid the auto-scroll
problem — the settings screen fits roughly four rows at the browse list's 116px
pitch, and the pool is five or six — and stay operable by a rotary controller,
which a drag gesture fundamentally is not. Drag was chosen anyway: it is the
directly manipulable thing, and the screen is blocked while moving, so the
gesture is only ever performed parked. Because the auto-scroll case is real
rather than hypothetical, it is specified rather than left to the default.

**A show-as-tab checkbox plus a separate order.** Makes "is this a tab" explicit
instead of implied by position, at the cost of two concepts and of states the
single list cannot represent — zero tabs selected, or five.

**Three fixed slots, each a picker.** Makes the cap impossible to misread, but
reordering means re-picking two slots, and duplicate selections need guarding.

**Per-library order, keyed by machine identifier and section.** This is the case
the empty-Playlists measurement actually describes, and it was still declined: it
needs a keyed store, a fallback for a library's first visit, and cleanup when a
library goes away, and it makes the settings screen answer "which library am I
editing?" A single global order is one list to store, migrate and reason about,
and switching to a library the order suits badly costs one re-drag.

## What deliberately does not change

- **The four-tab cap.** Still four, still a silently dropped fifth. The comment
  in `MediaBrowserTree.buildTree` recording it stays; the sentence beside it
  claiming the root "is fixed at Playlists | Artists | Albums | More" becomes
  the first three of the saved order plus More, in that comment and in
  `CLAUDE.md`'s matching line.
- **`getChildren()`'s dispatch.** Unchanged, for the reason above.
- **Each destination's own presentation.** A promoted destination looks exactly
  as it did nested, which is measured, not assumed.
- **Search.** It is the car's own affordance in the toolbar, not a root child,
  and is untouched.
