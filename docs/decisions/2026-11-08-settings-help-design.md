# Settings explains itself, from a file the website renders too

**Date:** 2026-11-08
**Status:** Approved

## Context

Settings has outgrown its labels. "Show library quick switch", "Volume
leveling" and "Precache" each name a setting without saying what it does, and
nothing in the app says more. Issue #290 asks for help text, opened from
Settings.

The same explanations are wanted on siskinapp.com, for someone deciding whether
to install the app or looking something up away from the car. Two copies would
drift, so there is one source and both are generated from it.

The debug screen's Changes tab is the precedent for most of this. It already
bundles a markdown file from the repository as a generated asset and draws it
with a parser written here (`2026-10-25-changelog-tab-design.md`). The site
already renders markdown with pandoc, for `/privacy`
(`2026-09-07-site-deploy-design.md`).

## Decision

**`docs/web/settings-help.md` holds one section per Settings category. Each
category's pane gets a Help button that shows that category's section, and the
site renders the whole file at `/settings-help`.**

### A Help button per category, opened in the pane

The pane's heading row gets a borderless **Help** text button, icon and label
in the primary color, at its end, where Customize tabs keeps its reset button.
It is the full 72dp touch target and does not push the heading down, so the
heading sits where it does on About and on the help screen. It shows on every category that has a
help section, which is every category except About. Tapping it opens
`SettingsHelpFragment` in the pane, as the pickers open, so back, the rail and
rotary focus already behave (`2026-11-07-settings-two-pane-design.md`).

Keeping the heading in place takes negative margins. The button is 72dp
tall, and its -18dp top and bottom margins count it as 36dp, the heading's
line. The heading row's 18dp padding, cancelled by its own negative margins,
keeps the button inside the row's bounds, where it can be touched. Neither
that padding nor the pane's may clip it, or its ripple and focus highlight
shrink to the heading's height. An overlay, as Customize tabs places its
reset button, would avoid the arithmetic, but a rotary controller would then
reach Help only after every row.

The help is only text, so a rotary controller has nothing to stop on but its
scroll view. That view carries car-ui-lib's
`com.android.car.ui.utils.VERTICALLY_SCROLLABLE` content description, which
makes RotaryService spend a turn scrolling it rather than moving focus away,
and handles the scroll RotaryService injects itself: the injected event is
`AXIS_VSCROLL` from a rotary encoder, and `ScrollView` reads only
`AXIS_SCROLL` from one. At the end of the text, a further turn moves focus on
as it would from any view.

Help is per category because a driver wondering what Cross-fade does is
already looking at Playback. A single Help category on the rail was the
simpler first idea, but it would make that driver scroll past the other
categories' text to reach their answer.

The help screen's heading is the category's translated name, the same as the
rows' pane, so no new string is needed for it.

Two other placements were mocked at 1024×768 and rejected:

- **Help always beside the rows**, in a second column. It needs nothing
  tapped, but it narrows the rows the two-pane redesign made full width, and
  portrait has no room for the column.
- **An info button on every row**, opening that setting's help in a dialog.
  It is the most precise, and it puts a second tap target inside a row that is
  already one, so a missed tap toggles the setting. A rotary controller also
  steps through twice as many targets.

### English only

The help text is not translated. It is long, it will change with every
setting added, and keeping four translations of it current would cost more
than it returns. A German driver gets a German **Hilfe** button that opens
English text. The button's label is an ordinary string with four
translations; nothing else in this change is in `strings.xml`, so
`MissingTranslation` stays at zero without an exception.

### One markdown file, two readers

The file lives in `docs/web/`, which the Cloudflare project's build watch
paths already cover, so it needs no dashboard change to reach the site.

Its shape is fixed: a `#` title and intro, then for each category a
`## <Category> {#<id>}` heading with a `### <setting>` heading per row and
prose under each. The id is the `SettingsCategory` entry's name in lower case
(`playback`, `browse`, `storage`, `account`). The app finds a section by that
id, never by its heading text, so the heading can be reworded and the
category label can be translated without breaking the lookup. Pandoc reads
the same `{#id}` natively, so `/settings-help#playback` links to a category.

**The site** is one more pandoc call in `build-site.sh`, beside the privacy
policy's, with `style.html` included. `index.html` links to it.

**The app** gets the file through a Gradle task registered as a generated
asset directory, as `trimChangelog` is. The task copies the file and does not
validate it, since it cannot see the Kotlin enum.

The changelog's parser becomes a general one for the subset both files use.
It learns `###` as a second heading level, strips a trailing `{#id}`, and can
return one `##` section by id. Dropping empty headings is the changelog's
rule, not the parser's, and stays with the changelog. The span builder moves
out of `DebugChangelogFragment` so both screens use it.

**The hazard is that pandoc reads far more markdown than the app does.** A
table, a nested list or a second paragraph inside a bullet renders correctly
on the site and as plain text in the car. The file is held to the changelog's
subset plus `###`, by convention rather than by a test.

### The categories and the file are checked against each other

A unit test reads the bundled asset and fails unless every category except
About has exactly one section and every section's id names a category. That
makes adding a category without its help a failing PR, and so does renaming
an id on one side only. `SettingsCategory` becomes `internal` so the test can
see it.

The check works per category, not per setting. A new row in an existing
category can still arrive without its `###` entry, and review is what catches
that. Keying help to each preference would make that checkable too, but it
would mean a structured format of our own instead of markdown, with a
renderer for each side.

### What was not chosen for the source

- **String resources**, one `translatable="false"` string per category, with
  the site's HTML generated and committed as `credits-oss.html` is. It keeps
  the text inside Android's resource system, at the price of writing long
  prose inside XML escaping and committing a second generated file for the
  site.
- **Structured YAML keyed by setting.** Described above: precise, but a
  format to invent and two renderers to write.

## Testing

Under Robolectric:

- The parser: `###` headings, `{#id}` stripped, a section returned by id, an
  unknown id returning nothing. The changelog's existing tests move into
  `CarMarkdownTest` with their heading level made explicit, and keep the
  empty-heading rule. The one that fed `###` as syntax outside the subset uses
  `####` instead, since `###` is now in it.
- The asset: one section per category except About, and no section without a
  category.
- Settings: Help shows on every category except About. Tapping it adds
  `SettingsHelpFragment` to Settings' child back stack, headed by the
  category's name and showing that category's text: Playback's help names
  Cross-fade and does not name Storage's Cache size. Back closes it, and so
  does choosing a category on the rail. The help text's scroll view takes
  focus when the help opens, carries the rotary scrollable marker, and scrolls
  on the event RotaryService injects for a turn; back returns focus to Help.

The site, rendered with `siskin-render-web` and checked in a real browser.
Then by hand on the landscape AVD: each category's Help, closed by back and by
the rail, and the button reachable with a rotary controller.

## Changelog

User-visible in the car: Settings categories have a Help button that
explains their settings.

## What this does not buy

- **Translated help.** The button is translated and the text is not.
- **Help per setting.** A category's help is one screen.
- **A general user guide.** Sections with no category, such as signing in or
  voice requests, would fit the format, and the guard would have to learn to
  ignore them. None are written yet.
- **A portrait layout.** The help is a scrolling pane and should hold up at
  800×1280, but it was designed on the landscape AVD.
