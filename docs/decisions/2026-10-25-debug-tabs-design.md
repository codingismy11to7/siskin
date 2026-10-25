# The debug screen gets side tabs

**Date:** 2026-10-25
**Status:** Approved

## Context

`CarDebugFragment` is one scrolling column: the server address report, its
Re-probe and Choose server buttons, and then the device identity, below the
fold. `2026-08-16-debug-screen-design.md` promoted it from a dialog because
debug information accumulates, and it has — but every addition makes the column
longer, and the second section is already hidden under the buttons with only a
fading edge to say it exists.

More is coming. The next addition is a Changelog tab, and ideas for this screen
keep arriving faster than they get written down. What is wanted is a structure
where a new area of debug information has an obvious home and costs one small,
predictable step to add.

This change is the structure only. It re-homes the two existing sections and
adds no new diagnostics.

## Decision

**The debug screen becomes a shell with a Material `NavigationRailView` down the
start edge and one fragment per tab beside it. A `DebugTab` enum is the list of
tabs.**

### Which rail

Four layouts were built as a throwaway spike and compared on the AAOS emulator:
a wide text rail, an icon rail, a column of the app's own 72dp pill buttons, and
the text rail with the "Debug" title kept. The icon rail won, and on width as
much as looks: at 1024×768 it is the only one beside which every `plex.direct`
address in the report still fits on one line. The text rails cost about 200dp
and wrapped several hostnames, which also left the `<- in use` marker reading as
though it belonged to the wrong line.

There is no screen title. The selected tab says where you are, and the rail
starts below `activity_car_host`'s overlaid back button rather than beside it.

The rail is Material's own, not hand-built, for what comes with it: rotary
controller focus, accessibility roles and selection state. The spike's
programmatic `NavigationRailView` rendered no items, so the real one is declared
in XML; should it misbehave in the car, a hand-built rail like the spike's is
the fallback. Either way its sizes are the head unit's, not Material's phone
defaults — icons around 32dp, labels in `TitleMedium`, items at least 72dp —
because the taps happen at arm's length.

### One fragment per tab

`CarDebugFragment` keeps its name and its entry point, the version line in
Settings, and becomes the shell: the rail and a content container.

- `DebugServerFragment` — the address report, Re-probe and Choose server.
- `DebugCarFragment` — what this car tells Plex it is, and which tier answered.

`buildAddressPanelBody` and `buildDevicePanelBody` move to those fragments
untouched; they are framework-free precisely so they can be asserted directly
under `unitTests.returnDefaultValues`, and that does not depend on their
address.

`DebugTab` carries each tab's label, icon and fragment factory, and the rail is
built from `DebugTab.entries`, so the enum's order is the rail's order. **Adding
a tab is one fragment, one enum entry and one non-translatable `debug_` label
string.**

Two alternatives were rejected. Tabs as view-building functions inside one
fragment is what the spike did and has fewer classes, but every tab's state,
view references and helpers accumulate in one file, which is the column's
problem moved sideways. Tabs as separate back-stack screens would make back walk
through every tab visited and rebuild the rail on every tap.

### Tabs are kept alive, not replaced

A tab's fragment is added to the shell's `childFragmentManager` the first time
it is selected and shown or hidden after that. Replacing it would destroy its
view, and Re-probe's race runs in that view's lifecycle for up to tens of
seconds: glancing at the Car tab mid-race would cancel it and lose the result
that `2026-08-14-server-address-debug-design.md` insists on reporting.

Tab switches never touch the back stack. Tabs are not history; one back press
leaves the debug screen from any tab, as it does today.

The selected tab survives a uiMode flip and a round trip through Choose server
through the child fragments' and the rail's own saved state. A fresh visit
opens on the first tab. Nothing is persisted.

### Choose server still covers the whole screen

`DebugServerFragment` pushes `PlexSignInFragment.pushed()` onto the
**activity's** fragment manager, not the shell's child manager, so the picker
replaces the whole debug screen and back returns to it — onto the Server tab,
by the saved state above. Everything `2026-08-16-debug-screen-design.md` says
about pushing rather than routing still holds; `CarHostActivity`'s
back-stack guard is unchanged, and only its comment, which names the pusher,
moves to the new class.

### Changelog

This is the first change to land under the `**Debug:**` changelog tag (#236): it
earns a bullet, because the Changelog tab will show this file, and the cut
leaves it out of the Play note, because drivers never see this screen.

## Testing

Driven through `CarHostActivity` under Robolectric, as `CarDebugFragmentTest`
already does:

- The rail has one item per `DebugTab`, in enum order.
- Selecting Car shows `DebugCarFragment` and hides the Server tab; selecting
  Server again shows the **same** instance. This is the test with teeth: a
  change to replace-on-select fails it.
- One back press from the Car tab leaves the debug screen for Settings.
- The existing round trip — version line, Choose server, back — also asserts
  that it lands on the Server tab.
- `CarDebugAddressPanelBodyTest` and `CarDebugDevicePanelBodyTest` keep their
  assertions and change only the class they call.

Then by hand on the landscape AVD: both tabs, a Re-probe, and the Choose server
round trip.

## What this does not buy

- **No new diagnostics.** The two sections are the same sections. The Changelog
  tab is its own change.
- **No fix for long addresses in general.** They fit beside the icon rail at
  1024×768; a narrower head unit would wrap them as it always has.
- **Nothing for a moving car.** `CarHostActivity` still carries no
  `distractionOptimized` meta-data, so the screen is readable at a standstill
  only.
