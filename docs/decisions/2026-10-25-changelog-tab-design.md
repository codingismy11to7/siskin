# The debug screen gets a Changelog tab

**Date:** 2026-10-25
**Status:** Proposed

## Context

`2026-10-25-debug-tabs-design.md` gave the debug screen a rail of tabs so that
a new area has an obvious home, and named a Changelog tab as the next one.

The point is to answer "what is in the build on this head unit?" from the head
unit itself. Builds reach the car through Play's internal track and sit beside
debug builds installed by hand, and the version line in Settings says which
number is installed but not what that number contains.

`CHANGELOG.md` is 935 lines, and only the first ~210 are Siskin's. Everything
from `[4.22.2]` down is inherited Tempus history in GitHub's "What's Changed"
format, describing an app that no longer exists.

## Decision

**A Changelog tab, last in the rail, shows Siskin's own sections of
`CHANGELOG.md`, cut out at build time and formatted by a small renderer written
here, with no markdown library.**

### Siskin's sections only, cut at build time

The tab shows `## [Unreleased] — Siskin` through the end of the `[0.99.0]`
section. That drops the intro blockquote, which is about the fork's lineage,
and all the Tempus history. `[Unreleased]` stays in: on a debug build it is
exactly what the build has beyond the last release, which is half of why the
tab is useful.

A Gradle task does the cut and hands the result to the build as a generated
asset, so the Tempus history never ships. The cut is anchored on those two
headings, not on line numbers or version patterns. A release cut rewrites
`[Unreleased]` into a version heading and opens a fresh one above it, which
leaves both anchors where they were. If either heading goes missing, the task
fails the build rather than shipping an empty tab or the whole file.

Two other scopes were rejected. The whole file is the simplest step but mostly
documents Tempus. Only the current version's section is a narrower "what's
new", but it loses the context of the releases before it, and a debug build
would show only `[Unreleased]`.

The tab is in release builds too, like the rest of the debug screen. There,
`[Unreleased]` is always empty: `cut-release.sh` leaves the heading with nothing
under it, and the cut's own merge is what publishes. **A heading with nothing
before the next heading is dropped**, so a Play build opens on its own version
rather than on an empty section that reads as though something failed to load.
A debug build with entries still shows them under Unreleased. The rule is the
renderer's, not the Gradle task's, so it is tested with the rest of the parser.

### A renderer written here, not a library

Three renderers were built as a throwaway spike and compared on the AAOS
emulator, all showing the same trimmed content:

- **Markwon 4.6.2**, core only. Faithful CommonMark rendering, and 43,934
  bytes of release APK after R8 (about 0.4%). Its last release was in 2021, and
  it pins the pre-move `com.atlassian.commonmark` 0.13 coordinates of a parser
  that has since moved to `org.commonmark` and is maintained. Size was not the
  objection; a dependency nobody maintains is.
- **Build-time HTML**: commonmark-java 0.28 on the buildscript classpath only,
  rendering the cut to HTML for `Html.fromHtml`. It ships no library, but
  `Html`'s styling is the plainest of the three, and it needs workarounds such
  as mapping `<code>` to `<tt>`.
- **Hand rendering**: version headings large and bold, bullets with a hanging
  indent, bold, monospace code, and link text with the URL dropped.

Hand rendering looked best at arm's length, so it was chosen. Its cost is that
it understands only the markdown this file uses. That is a small, stable
subset, because one script and a handful of conventions write the file.

### Parser and spans are separate

The renderer has two halves:

- **A framework-free parser**, from markdown to blocks — heading, bullet,
  paragraph — each holding inline runs of plain, bold or code text. A link
  keeps its text and drops its URL, and a heading drops the brackets around
  its version. Syntax outside the subset, such as a nested list, a table or
  an unclosed `**`, comes through as plain text rather than failing. Being
  framework-free, it can be asserted directly, as the debug screen's other
  panel bodies are.
- **A thin span builder** that turns blocks into a `Spanned`. Colours come
  from the theme, so the bullets follow day and night mode.

Blocks are separated by a half-height blank line, so a tight list stays tight
and a loose one does not sprawl.

Links are text, not tap targets. The screen is reachable only at a standstill
and has no browser to send anyone to.

### Placement

The tab is last in the rail, after Server and Car: the other two are the ones
reached for when diagnosing something. It follows the recipe in `DebugTab`
exactly: one fragment, one icon (Material Symbols "history"), one id, one enum
entry and one non-translatable label.

## Testing

- Parser tests on the subset: heading brackets stripped, bullet continuation
  lines joined, tight and loose lists, bold, code and link runs,
  out-of-subset syntax left as plain text, and an empty section dropped.
- Under Robolectric, which sees the debug variant's merged assets: the bundled
  changelog starts at `## [Unreleased]`, contains `[0.99.0]`, and contains no
  `## [4.` heading.
- The rail test grows to three tabs, and selecting Changelog shows a non-empty
  body.

Then by hand on the landscape AVD.

## What this does not buy

- **General markdown.** A new construct in `CHANGELOG.md` shows as plain text
  until the parser learns it.
- **Live links.** Issue references like `(#53)` read as text.
- **History before Siskin.** The Tempus sections stay in the repository and out
  of the app.
