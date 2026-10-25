# The debug screen opens on an About tab

**Date:** 2026-10-25
**Status:** Approved

## Context

The debug screen is a rail of tabs (`2026-10-25-debug-tabs-design.md`):
Server, Car and Changes. None of them answers the first question asked of a
build in the car, which is which build it is and whether the update has
landed. Settings' version line shows the version, but it is also the button
that opens this screen, so it is no help once you are here.

New builds reach the car through its own Play Store. "Did the new one install
yet?" comes up after every release cut, and the version string alone answers
it only if you remember which version you are waiting for.

The app also credits nobody. Siskin is a GPLv3 fork of Tempus, which is a fork
of Tempo, and the build bundles a long list of open-source libraries. That
belongs somewhere a driver can reach, even if only at a standstill.

## Decision

**An About tab, last in the rail and the tab the screen opens on, shows the
build's identity and install facts and a Credits button whose dialog points at
`siskinapp.com/credits` with a QR code and the URL as plain text.**

### Last in the rail, first on screen

The tab sits after Server, Car and Changes, and a fresh visit opens on it.
The debug-tabs design opened on the first tab, so the rail order and the
opening tab were the same thing. They are now separate: the rail still follows
the order of `DebugTab.entries`, and `DebugTab.DEFAULT` names the tab a fresh
visit opens on.

Everything else about selection is unchanged. The tab that is showing is still
whichever tab fragment is not hidden, so a screen recreated while on Car stays
on Car. `DEFAULT` applies only when no tab has been shown yet.

### What the page says

- The app icon, "Siskin", and `versionName (versionCode)`, the same pair
  Settings shows. Play goes by the code.
- **Updated**: `PackageInfo.lastUpdateTime`, as both relative and absolute
  time. This is the line that answers "did it land?" It is redrawn each time
  the tab is shown, since tabs are hidden rather than rebuilt, and a clock
  behind the update time reads as "0 minutes ago" rather than in the future,
  because a head unit's clock can lag until GNSS or the network sets it.
- **Installed by**: the installer's raw package name from
  `getInstallSourceInfo`, or the deprecated `getInstallerPackageName` below
  API 30, which is the only form Android 9 and 10 have. This is `com.android.vending` for Play, and nothing
  for an `adb install`, which reads "(no installer recorded)". It is not
  translated into "Play Store" or "adb": the package name is the fact, and
  reading an absent installer as adb is a guess.

There is no commit SHA. It needs `git` at build time, which makes every build
depend on repository state and breaks a build from a source tarball, and the
version plus the update time cover what the page is for.

The rail icon is Material Symbols' "info", `ic_browse_info`, which the app
already ships. The Siskin bird was the alternative, and its detail turns to
mush at 32dp beside the rail's outline icons.

Every string is a `debug_` string with `translatable="false"`, like the rest of
the screen.

### Credits

A full-size **Credits** button, like the Server tab's rows, opens a stock
Material dialog titled "Credits". The dialog shows a QR code for
`https://siskinapp.com/credits` and the URL beneath it as selectable text, so
the code is a shortcut and never the only way there. The dialog keeps
Material's small Close button. That is under the app's arm's-length target
size, which is accepted for a debug screen, and tapping outside the dialog also
closes it.

It is a `DialogFragment`, so it survives the activity being recreated, which a
day/night switch does.

The QR is 200dp, about as large as it can be while the URL beneath it stays
visible in the dialog on the 1024×768 head unit; a larger one clipped the URL off
the bottom. On a shorter head unit the dialog body scrolls, so the URL can
still be reached.

The credits get their own page rather than a section of the privacy policy:
third-party credits are not privacy content. #244 serves a minimal version (the
lineage, GPLv3 and the source repository, and a link to the privacy policy), and
#243 later fills it from a processed SBOM.

### A checked-in QR, not a QR library

The URL is a constant, so the code is generated once, by
`scripts/render-qr-drawable.sh` from nixpkgs' `qrencode`, and checked in as the
vector drawable `qr_credits.xml`. That means no runtime dependency and no APK
cost, and it stays sharp at any density. The drawable paints its own white
background and four-module quiet zone, because a scanner needs dark on light
and the dialog behind it is dark at night.

The alternative was ZXing at runtime, which would encode any URL. It costs a
dependency to draw one fixed string.

The risk of a checked-in drawable is going stale against the URL the app
prints. `QrDrawableTest` closes that: it rebuilds the module grid from the
drawable's path data, scans it with ZXing as a camera would, and compares the
result with the same constant the dialog displays. `zxing-core` is a test-only
dependency for that test.

## Testing

Under Robolectric, through `CarHostActivity` as `CarDebugFragmentTest`
already does:

- The screen opens on About, and About is the last tab in the rail.
- The existing tab tests no longer assume Server is showing on open: those that
  need it select it first, and those that checked Server was hidden now check
  the default tab instead.
- Credits opens a dialog showing the credits URL.
- `QrDrawableTest` decodes `qr_credits.xml` to that URL.

Then by hand on the landscape AVD: the tab, both install lines, and the dialog
with the URL visible under the code.

## Changelog

One `**Debug:**` bullet, which the cut leaves out of the Play note.

## What this does not buy

- **No dependency list.** That is #243.
- **Nothing for a moving car.** The screen is still readable only at a
  standstill.
