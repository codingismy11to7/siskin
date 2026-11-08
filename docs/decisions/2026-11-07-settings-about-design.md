# About moves from the debug screen to Settings

**Date:** 2026-11-07
**Status:** Approved
**Supersedes:** the placement and default-tab sections of
`2026-10-25-debug-about-tab-design.md`, and the version line in
`2026-11-07-settings-two-pane-design.md`

## Context

About was built as a debug tab (`2026-10-25-debug-about-tab-design.md`) because
the debug screen was the only place that had room for it. What it answers,
though, is not a debugging question: which build this is, whether the update has
landed, and whom the app owes credit to. Those are a driver's questions too.

Settings now has a rail (`2026-11-07-settings-two-pane-design.md`), so About
can be a category there. That also gives the debug screen a better entry point
than the bare version line at the bottom of Account.

## Decision

**About becomes Settings' fifth category, last on the rail. Its version line is
the debug screen's entry point, and Account loses its own. The debug screen
loses the About tab and opens on Server.**

### What About shows in Settings

- The app icon, "Siskin", and `versionName (versionCode)`. **Tapping the
  version opens the debug screen**, as Account's version line did. It stays
  focusable, so a rotary controller can reach it.
- **Updated**: the install's relative and absolute time, redrawn each time the
  category is shown, because "3 minutes ago" goes stale. The clamp against a
  lagging head-unit clock stays.
- **Credits**, the same button and dialog as before.

**The "Installed by" line does not come along.** A raw package name such as
`com.android.vending` is a fact for whoever is debugging, not something a
driver should be asked to read. It moves to the debug screen's Car tab, under a
"This build" heading, worded exactly as before.

Every string a driver now sees (the category label, "Updated", "Credits", the
QR description and "Close") becomes a `car_settings_` string translated into
the four other locales, per the five-locale rule. The installer line stays a
`debug_` string, untranslated.

### Built as views, not a fragment

The About category is a layout inflated into Settings' pane, like the other
categories' rows, not a child fragment. Settings' child fragment manager is
already the pane screens' back stack, and putting a permanent fragment there as
well would be one more thing for `closePane` and the back callback to step
around. The few things the old fragment did (fill the version, render
the Updated line, show the Credits dialog) move to a small helper beside
`CarSettingsFragment`. Settings calls it when the view is built and each time
About is selected.

`CreditsDialogFragment` stays a `DialogFragment`, so it still survives a
day/night recreation. It moves out of `DebugAboutFragment`, which is deleted.

The About layout loses its own `ScrollView`, because Settings' pane already
scrolls.

### The debug screen

`DebugTab.ABOUT` goes, and `DebugTab.DEFAULT` becomes `SERVER`, the first tab.
The About design doc split rail order from the default tab, and that split
stays. The two simply agree again for now.

## Changelog

This is visible to drivers, so it earns a bullet: About is in Settings, and
its version opens the debug screen. The debug screen's new default tab gets a
`**Debug:**` bullet.

## Testing

- Settings has five categories, with About last. Selecting it shows the app
  name, the version and Credits.
- Tapping the version pushes `CarDebugFragment`, and back returns to Settings
  on About.
- Account no longer shows a version line.
- The debug screen opens on Server, and has no About tab.
- The Car tab shows the installer line.
- `QrDrawableTest` keeps holding the QR to `CREDITS_URL`.

Then by hand on the landscape AVD: About, Credits, version into debug and back.
