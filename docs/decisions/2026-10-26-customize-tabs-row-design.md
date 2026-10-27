# Customize tabs becomes a value row; Sign out stays a button

**Date:** 2026-10-26
**Status:** Approved
**Issue:** #261

## Context

`docs/decisions/2026-10-25-cache-settings-design.md` gave Settings a third kind
of row: a value row, with the label on one line and the current value beneath
it, which pushes a picker when tapped. Cache size and Precache were built that
way. Customize tabs and Sign out were left as the filled buttons that
`addChoice` draws. On the emulator, the value rows read better than those
buttons, and the buttons now look like leftovers.

The issue asked whether both should become rows.

## What changes

| Row | Today | After |
|---|---|---|
| Customize tabs | `addChoice` button | `addValueRow`, value line showing the current tabs |
| Sign out | `addChoice` button | unchanged |
| Switch user | `addChoice` button | value row, but in #262, not here |

`addChoice` itself is not touched. The server and library pickers use it, and
there a list of buttons is the point.

## Customize tabs shows the tabs it customizes

The value line is the three root tabs, in order, joined with ` · `:

> **Customize tabs**
> Playlists · Artists · Albums

That is how the value rows above it already read: a label naming the setting
and a line showing its current value. Customize tabs is a destination that
pushes a screen, and so are they, so it fits the same shape.

Everything the line needs already exists, so it adds no logic:

- **Which tabs.** `BrowseTabOrder.rootTabs(BrowseTabOrder.resolve(...))` over
  the saved order, the same resolution `MediaBrowserTree` and
  `BrowseTabOrderFragment` use. It names the saved order, which is what the car
  shows once its root is rebuilt. That happens on the reorder screen's back
  press, so leaving the screen any other way — Home, or the car blocking
  `CarHostActivity` once it moves — can let the two disagree until the next
  session connect.
- **Their names.** `BrowseTabOrderFragment.labelFor`, which maps each id to the
  string its browse tab already uses. No new strings, so no new translations.
- **Freshness.** Settings rebuilds its view when the tab-order screen pops, as
  it does for every picker, so the line is current on the way back without any
  observation.

The separator is not translated, for the same reason `versionLabel` is not:
it is punctuation, not prose. The longest value in the five locales is
Spanish: 43 characters by default ("Listas de reproducción · Artistas ·
Álbumes") and 45 at most ("Listas de reproducción · Descubrir · Artistas").
It wraps in the 480dp column the way the cache line already does.

`rootTabs` returns fewer than three ids when the pool is smaller. The line then
lists what there is, with no special case.

## Sign out stays a button

Sign out is the one destructive action in Settings. The button's weight marks
it, and it is already last on the screen. As a plain row it would need some
other signal to read as different, such as a destructive text colour or
separation from the rows above it. A button already gives that signal.

## Switch user is #262's

#262, which closes #259, already makes Switch user a value row, with
"Listening as …" as its value line, to stop the screen jumping when plex.tv
answers. This design does not repeat that. Once both have landed, Sign out is
the only button left on the screen.

#262 also changes `addValueRow` to return its row along with the value line.
Customize tabs only passes a value, so it works with either return type, but
the implementation should land after #262 and be written against that version.

## Alternatives considered

**An explanation as the value line**, such as "Choose which three destinations
appear as tabs". It never goes stale and says what the screen does before
anyone opens it. It was set aside because every other value line in Settings
shows a current value, and an explanation in that slot would be the only line
that never changes. The label "Customize tabs" already says what the screen
does. It would also have cost four translations.

**Both, on two lines.** The most informative option, but also the most text to
take in at a glance in a moving car, on a screen whose other rows have one
value line.

**Sign out as a row**, made distinct by a destructive text colour or a gap above
it. Either works, but both rebuild the emphasis that the button already gives,
on the one row where losing it costs a Plex re-authentication.
