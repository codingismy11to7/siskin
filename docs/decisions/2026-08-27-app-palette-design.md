# Siskin carries its own palette instead of the car's

Closes #133, which asked for green buttons and found that the request could not
be met by editing a hex value. The buttons rendered `#B0C6FF` while
`colors.xml` said `#D0BCFF`, because `ThemeHelper.applyActivityTheme` ended
with:

```java
DynamicColors.applyToActivityIfAvailable(activity);
```

That call replaces `colorPrimary` and the rest of the scheme at runtime with
the head unit's. Whatever `colors.xml` held was overwritten before anything
drew, so changing it changed nothing — the trap the issue exists to record.

## Why the app takes its own colour

The case for dynamic colour is that a media source ought to look like the car
it is sitting inside. On AAOS that case is weaker than it sounds, and the
measurement is what settled it: **the emulator and a real head unit render the
identical blue.** AAOS has no wallpaper to seed a palette from, so SystemUI
generates one from a default seed. "Matches the car it is in" is in practice
"is this same `#B0C6FF` nearly everywhere", and that blue is a fallback nobody
chose rather than a colour the car asked for.

Deferring to a choice the car never made buys nothing. So Siskin keeps a
palette, and the `DynamicColors` call is gone rather than gated: a setting
would be a fifth preference and four translations to offer a choice between
green and a default blue, which is not a choice worth surfacing.

The cost is real and accepted — on a head unit whose own UI is some other hue,
Siskin will not match it. That is the same cost every app with a brand colour
pays, and the app is now consistent across cars instead.

## The palette

Seeded from **`#1F3D2B`**, the launcher icon's background green, and generated
by Material's own `CorePalette` — the generator that produced the purple set
this replaces, so the file keeps the tonal relationships it already had.

The seed choice was between the icon's two colours. The lime `#D4E157` is the
brighter and more obviously "the logo", and it seeds badly: its primary lands
on `#5B6300`, an olive, and it drags the light background to `#FEFFD7`, a
yellow-tinted white. `#1F3D2B` gives `#006D41` light and `#77DAA0` dark, which
are green in the way the request meant.

Three things were kept rather than regenerated:

- **The error family.** It is seed-independent in Material's algorithm, so
  regenerating it would have changed error colours for no reason connected to
  this issue.
- **The amoled overrides.** Amoled is the dark set with the surface family
  taken to true black. It is not a second accent, and `AppPaletteTest` pins its
  primary to dark's so a later edit does not quietly make it one.
- **`values-night`'s empty `colors.xml`.** All four style files reference the
  colour resources rather than hardcoding, so the palette still moves in one
  file.

## Why `onPrimary` is tested and not merely edited

Both button sites draw their label with `?attr/colorOnPrimary` — `retry_button`
in `fragment_plex_sign_in.xml`, and `addChoice()` in `CarScreenViews.kt`, which
restores it by hand after `setTextAppearance` clobbers it. A primary changed
without its `onPrimary` therefore lands as pale text on a pale fill, which
reads fine on a desk and fails at arm's length in daylight.

`AppPaletteTest` asserts a 4.5:1 contrast ratio for all three palettes, reading
the values back through resources so it fails on a `colors.xml` change rather
than agreeing with itself. 4.5:1 is WCAG AA for body text; a button label is
large enough for the 3:1 allowance and the stricter bar is deliberate, for the
same arm's-length reason that zeroes `MaterialButton`'s insets everywhere else
in this app.

The same test asserts the primary's hue is green in all three, which is the
request itself written down.

## What this does not reach

**Now Playing.** `com.android.car.media` draws it with the system theme, and
nothing here touches it. The green appears on the sign-in screen, the server
and library pickers, and settings — everywhere the app draws its own views.
