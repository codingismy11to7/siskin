# The changelog is written per PR, not per release

**Date:** 2026-08-12
**Status:** Approved

## Context

`CHANGELOG.md` exists and is good. It carries a preamble explaining the fork's
version numbering, an `[Unreleased] — Siskin` heading, entries for `[0.99.1]`
and `[0.99.0]`, and below those the inherited Tempus entries from 4.22.2 down.

It is also two releases out of date, and the way it fell behind is the whole
reason for this document.

**The convention existed and lapsed silently.** `1242cc0d Cut 0.99.1` changed
exactly two files — `CHANGELOG.md` and `app/build.gradle` — so "a cut carries
its own changelog entry" was established by the first release that used it.
`2210ff0e Cut 0.99.2` touched only `build.gradle`. `5645a38d Cut 0.99.3` did the
same, earlier on the evening this was written.

Nothing enforced it and nothing recorded it. The word "changelog" did not appear
in `CLAUDE.md`, so the practice lived entirely in the memory of whoever had done
it last, and one skipped release was enough to erase it. The second skip was
then the natural reading of the precedent: the two most recent cuts before it
had both been build.gradle-only.

That is the failure to fix. Backfilling the two missing entries is the smaller
half of this change.

## Decisions

### Entries land under `[Unreleased]` in the PR that makes the change

A PR that changes what the app does adds its own bullet to
`[Unreleased] — Siskin` as part of the change. A cut then renames that heading
to `[0.99.x] (date)` and opens a fresh empty one.

Three things follow from this, and the third is the one that decided it:

- **The entry is written while the change is understood.** A bullet composed at
  cut time is reconstructed from commit subjects by someone holding nine
  unrelated changes in mind at once. Composed in the PR, it is written by the
  same reasoning that produced the change, next to the code, with the design doc
  open.
- **The reviewer sees the user-visible claim.** A PR that says it changes
  behaviour and adds no bullet is visible as such in the diff, which is a weaker
  check than CI but applies at the right moment.
- **A cut becomes mechanical, which is what #53 needs.** Renaming a heading and
  inserting an empty one is scriptable. Composing release prose is not. Since
  #53 intends to automate versioning and releasing outright, every part of a cut
  that stays human is a part that blocks it, and the changelog was the largest.

### Enforcement is a written instruction, not a CI gate

`CLAUDE.md` gains a section covering how work lands and how a release is cut,
naming the changelog obligation. A CI check — fail the release run if nothing
touched `CHANGELOG.md` since the previous tag — was considered and rejected.

The check would work, but it fires at the wrong time. A tag is pushed after the
version is bumped, merged and published; discovering the omission there means a
red release run and a follow-up commit behind an already-shipped tag, which is
exactly the state this change is cleaning up. It also cannot judge the thing
that actually matters — a release whose changes were genuinely invisible to the
user *should* add no bullet, and a check that counts diff lines would push
toward writing filler to satisfy it.

The instruction is weaker but correctly placed. If it lapses again, the CI gate
is the escalation, and this document is the record that it was the second
choice rather than the unconsidered one.

### The Play Store's release notes stay empty

Gradle Play Publisher reads `app/src/main/play/release-notes/<locale>/<track>.txt`
and would publish those as the "What's new" text on each cut. That plumbing is
not added here.

The changelog and a store note are different artifacts wearing similar names.
Play caps a note at 500 characters and wants terse user-facing lines; the
entries here run to explanatory prose and are aimed at whoever is reading the
repository in a year. Feeding one from the other would mean either truncating
the changelog's voice or maintaining two texts per release — and, because Siskin
ships five complete locales, plausibly two texts in five languages.

The internal track's audience is the author's own head unit, and the author
reads the repository. Until the track has readers who do not, a store note has
no audience the changelog does not already serve better.

### What earns a bullet

User-visible behaviour in the car. Documentation, comments, tests, build plumbing
and refactors get nothing.

This is a description of existing practice rather than a new rule. The 0.99.2
range holds four commits and warrants two bullets; 0.99.3 holds nine and
warrants eight. Both ratios come from the same filter, applied by hand, before
it was written down.

Entries state what the app does now, with the reason attached where it is not
obvious from the behaviour. Keep a Changelog's `### Added` / `### Fixed`
headings are deliberately not adopted — the existing entries are flat bullet
lists, the file is read by one person who wants to know what changed on the head
unit, and category headings would sort a two-item release into two sections of
one.

## The backfill

`[0.99.2]` and `[0.99.3]` are written from their release ranges. The 0.99.2
entry is transcribed from the `Cut 0.99.2` commit message, which already names
its two user-visible changes, rather than re-derived from the diff.

**The 0.99.3 entry lands after the tag it describes.** `v0.99.3` was pushed and
published before this work started, so the tagged tree does not contain its own
changelog entry. Retagging was rejected: deleting and re-pushing `v0.99.3` would
re-fire `release.yml` and attempt a second upload of `versionCode 5`. A
one-commit skew in the history is the cheaper defect, and it is confined to the
release this convention exists to prevent repeating.

## What this does not buy

- **No protection from a PR that simply omits its bullet.** The check is a human
  reading a diff. This is a deliberate trade, made above.
- **No retroactive entries below 0.99.0.** The inherited Tempus entries stay
  exactly as they are; they describe releases this project did not make.
- **No automation of the cut itself.** This makes the changelog half of a cut
  mechanical so that #53 can automate it later. It does not do that work.
