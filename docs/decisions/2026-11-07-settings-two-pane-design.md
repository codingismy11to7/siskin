# Settings becomes a two-pane screen

**Date:** 2026-11-07
**Status:** Approved
**Supersedes:** the picker's placement in `2026-10-25-cache-settings-design.md`,
and where `2026-08-14-customizable-browse-tabs-design.md` puts the reorder
screen

## Context

Settings is one centered 480dp column of rows under a "Settings" heading. That
column was sized when it held nothing but a Sign out button. It now holds eleven
rows: six toggles, three value rows, Switch user and Sign out. At the landscape
emulator's 1024×768 it shows five of them, then fades into a scroll, with more
than half the screen's width empty on either side. The car shows the same
thing. It reads as a phone screen scaled up, and that is how it was built.

The screens Settings opens have the same shape. The Cache size and Precache
pickers, and the Customize tabs reorder screen, each replace the whole screen
with another centered column.

A head unit is closer to a tablet than to a phone, and the debug screen already
has the arrangement that suits one: a navigation rail down the start edge with
content beside it (`2026-10-25-debug-tabs-design.md`).

## Decision

**Settings gets the debug screen's rail, with four categories on it. The
selected category's rows fill the pane beside the rail. The pickers and
Customize tabs open inside that pane instead of covering the screen.**

### Categories

| Category | Rows |
|---|---|
| Playback | Keep the music playing, Volume leveling, Cross-fade between tracks |
| Browse | Show artists by initial, Show Artist Mix, Show Artist Radio, Customize tabs, Show library quick switch |
| Storage | Cache size, Precache |
| Account | Switch user, Sign out, the version line |

At 1024×768 every category fits without scrolling, Browse included. Playback
and Storage leave most of the pane empty. That is accepted: the grouping is
what makes a new setting's home obvious, and a sparse pane costs nothing.

The pane's heading is the category's name. There is no "Settings" title,
because the selected rail item already says where you are. That matches the
debug screen.

The version line stays the debug screen's entry point and moves to the bottom
of Account. The debug screen's About tab is expected to move into Settings as a
fifth category, but in a separate change. Once it does, the version line
probably moves with it.

### The rail is the debug screen's rail

It has the same `NavigationRailView`, the same sizes (32dp icons, `TitleMedium`
labels, items at least 72dp), and the same header spacer under
`activity_car_host`'s overlaid back button. The two screens should read as one
app's screens, and the rail's width was already judged right in the car.

Its menu ids are resource ids in `ids.xml`, for the reason the debug doc gives.
A private `SettingsCategory` enum carries each category's id, label and icon,
and the rail is built from `SettingsCategory.entries`. The four labels are
translated. Unlike the debug tabs, drivers see these.

The selected category is held on the fragment, not on its view. A picker or
Customize tabs does not rebuild Settings' view any more, but switching to Switch
user and back still does, and so does going to the debug screen and back.
Settings has to come back on the category it was left on in both cases. A fresh
visit opens on Playback, and nothing is persisted.

### Rows read as one control

On a full-width pane a switch sits about 800dp from its label. The whole row has
always been the tap target (see `addToggle`), but nothing showed it, and at that
distance the label and the switch stop reading as one control.

Every settings row is therefore drawn on a rounded, filled surface
(`settings_row_surface`: `colorSurfaceContainerHigh` at a 16dp radius, with the
ripple on top). The fill is what ties a label to a control at the far end of
the row. The picker rows and the reorder rows use the same surface, so every
list in Settings looks the same.

A row that opens something carries a trailing chevron: Customize tabs, Cache
size, Precache and Switch user. A toggle row changes in place and has none.

### Sign out is outlined

A full-width filled button becomes a slab of the primary color across the
whole pane, which would make it the most prominent control in Settings.
Sign out becomes an outlined button in the error color, as wide as its label
and no narrower than `plex_sign_in_button_min_width`. It stays last in Account
and keeps the 72dp height.

`addChoice` is unchanged. The sign-in screen's pickers still build filled
buttons with it.

### Pickers and Customize tabs open in the pane

Tapping Cache size, Precache or Customize tabs adds that screen to the pane, as
a child fragment of Settings, on Settings' own back stack. The rail stays
visible with the category still selected, so it is clear where you are and
how to get back.

- **Back closes the picker, not Settings.** An `OnBackPressedCallback` is
  enabled only while the child back stack is non-empty. With nothing open it is
  disabled, and back falls through to finish the activity as it always has.
  `activity_car_host`'s back button routes through the same dispatcher, so it
  behaves the same way.
- **Choosing an option closes the picker.** `ValuePickerFragment` already pops
  `parentFragmentManager`, which is now Settings' child manager. It needs no
  change.
- **Selecting another category closes whatever is open.** Otherwise a picker
  would stay showing beside the wrong rail item.
- **Closing refreshes the rows' values.** Settings used to refresh by having its
  view rebuilt when a pushed screen popped. Its view now outlives the picker,
  so the Storage values and the Customize tabs summary are re-read from
  preferences when the child back stack empties.

Customize tabs' parked-only restriction is unaffected. It came from being
hosted by `CarHostActivity`, which has no `distractionOptimized` meta-data, and
Settings is hosted there too. The car still learns about a new order from
`BrowseTabOrderWatcher` (`2026-10-26-tab-order-invalidation-design.md`), which
watches the preference rather than the screen.

The picker and reorder layouts become start-aligned: the heading in
`HeadlineMedium`, the explainer under it in `TitleMedium`, and the rows the full
width of the pane. Customize tabs' reset button stays at the top end, level
with the heading.

### Switch user still covers the screen

Switch user pushes `PlexSignInFragment.pushed()` onto the activity's fragment
manager, as before. It is the sign-in flow reused, with its own back handling
and the router guard that `CarSettingsFragment.switchUser` documents. Moving it
into the pane would mean hosting the whole sign-in state machine as a child of
Settings, which is a bigger change than this one and can be made separately if
it is wanted.

## Alternatives considered

**A single page of grouped cards, two columns, no navigation.** This puts every
setting on screen at once, one tap away. It was described but not built. It
would have needed scrolling for the right column at 768 tall, and it would not
match the debug screen. The rail also gives About an obvious place to land.

**A wider rail with larger labels.** This was proposed and dropped. The
debug screen's rail already works in the car, and matching it was the point.

**Pickers kept full-screen.** They were tried with the new row surface, as
centered columns. Compared side by side, the version inside the pane was
clearly better, because the rail stays visible and the picker lines up with
the rows it came from.

## Testing

Under Robolectric, through `CarHostActivity`, extending
`CarSettingsFragmentTest`:

- The rail has one item per `SettingsCategory`, in enum order, and Settings
  opens on Playback.
- Each row is in its category. Selecting a category shows its rows and hides
  the others'. Existing assertions that find a row by label first select the
  category that row belongs to.
- Tapping Cache size, Precache or Customize tabs adds the screen to Settings'
  child back stack. The activity's back stack stays empty. This replaces the
  current assertions that the picker is in `car_host_container`.
- Back with a picker open closes the picker and leaves Settings showing, on the
  same category. Back with nothing open finishes the activity, as now.
- Choosing an option in a picker updates the row's value line without
  rebuilding the view.
- Selecting another category while a picker is open closes it.
- The selected category survives Switch user being pushed and popped.

`ValuePickerFragmentTest` keeps its assertions. It opens the picker on the
activity directly, and the picker's own behavior (writing the preference and
popping its parent manager) does not change.

Then by hand on the landscape AVD: each category; both pickers, by back and by
choosing; a drag in Customize tabs with the summary updating on the way out;
Switch user and back; the version line into debug and back. With a rotary
controller, the rail and every row should be reachable.

## Changelog

This is user-visible in the car, so it earns a bullet: Settings is organized
into categories beside a rail, and its pickers and Customize tabs open inside it.

## What this does not buy

- **No About in Settings.** That is its own change.
- **Nothing for the sign-in screen.** It keeps its centered column and is
  untouched.
- **No portrait-specific layout.** At 800×1280 the rail and pane should still
  fit, but this was designed and checked on the landscape AVD only.
