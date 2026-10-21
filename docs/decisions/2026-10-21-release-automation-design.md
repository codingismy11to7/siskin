# Releases are cut by a script, published on merge, and promoted by hand

**Date:** 2026-10-21
**Status:** Approved
**Issue:** #53

Builds on `docs/decisions/2026-07-31-play-release-pipeline-design.md` and
`docs/decisions/2026-08-14-automotive-track-design.md`, and finishes the cut
that `docs/decisions/2026-08-12-changelog-workflow-design.md` made mechanical
so that this could automate it.

## Context

Play has granted production access, and production holds no releases. Every
build so far has gone to `automotive:internal`, whose only audience is the
author's head unit.

Cutting a release is a hand-edited commit plus a tag. The commit bumps
`versionCode` and `versionName` in `app/build.gradle`, renames the changelog's
`[Unreleased]` heading and condenses it into the Play release note; the tag
`v0.99.x` then fires `release.yml`. Two numbers and a tag have to agree, and the
one nothing checked — `versionCode` — is the one that breaks an upload.
`release.yml` refuses a tag that disagrees with `versionName`, which catches the
mismatch but only as a failed run after the merge.

1.0.0 has been held back deliberately so that it would be cut by the tool
rather than by hand. Shipping to production is the occasion for both.

## Decisions

### The version is one file holding one value

`app/version.properties` holds `versionName` and nothing else. `app/build.gradle`
reads it; it is committed, so a fresh clone, a shallow CI checkout and a source
tarball all build identically, and nothing shells out to git at configuration
time.

A file of its own rather than a line in `build.gradle` for two reasons: the
release workflow can trigger on exactly that path, and the cut script edits a
`key=value` line rather than Groovy.

Deriving the version from git tags at build time was rejected. It is a single
source of truth, but it makes every untagged build invent a name for itself and
breaks in exactly the shallow clones CI uses, and the cut still needs a commit —
the changelog and the release note change — so it buys no reduction in steps.

### `versionCode` is computed from `versionName`

    versionCode = major × 10000 + minor × 100 + patch

1.0.0 is `10000`, 1.2.3 is `10203`. `build.gradle` computes it, and fails the
build if the name is not three dot-separated integers or if minor or patch
exceeds 99.

Considered against a stored counter incremented by the cut:

- **One value cannot disagree with itself.** A counter is a second number every
  cut must bump, which is the defect #53 was filed about.
- **It cannot go backwards.** Every bump raises the semantic version, so it
  raises the code. The release workflow's own check becomes a backstop rather
  than the only defence.
- **The code is legible.** `10200` in the Console or in `dumpsys package` reads
  as 1.2.0; a counter's `14` reads as nothing.

What it costs:

- **The code jumps from 10 to 10000, permanently.** Play only requires each
  upload to exceed the last, and the ceiling is 2,100,000,000, so the floor is
  harmless. It is irreversible, which is why it is written down.
- **A hotfix cannot ship beneath a newer release.** 1.0.1 after 1.1.0 is a lower
  code and Play refuses it. With one line of releases on one track the fix ships
  as 1.1.1 instead; a counter would permit the out-of-order release, and nothing
  here wants one.
- **Minor and patch are capped at 99.** Not a real constraint for this app.

As before, an upload that reached Play spends its version. The fix ships as the
next patch, not as a retry of the same name.

### A cut is a local script, not a workflow

`scripts/cut-release.sh major|minor|patch`, exposed by the flake as
`nix run .#siskin-cut -- <bump>`. It:

1. refuses unless the tree is clean, `main` is checked out and matches
   `origin/main`, `[Unreleased]` holds at least one bullet, and the new tag does
   not already exist;
2. writes the bumped `versionName` to `app/version.properties`;
3. renames `## [Unreleased] — Siskin` to `## [X.Y.Z] (YYYY-MM-DD)` and inserts a
   fresh, empty `[Unreleased]` heading above it;
4. drafts `app/src/main/play/release-notes/en-US/default.txt` from the first
   sentence of each bullet in the section just closed, cut at a word boundary to
   fit 500 characters;
5. commits `Cut X.Y.Z` on a `cut/X.Y.Z` branch, pushes it, and opens the pull
   request with `gh pr create`.

The draft note is a starting point and the cut PR is where it is rewritten.
Condensing changelog prose into a store note is writing, not mechanics, and is
the one part of a cut that stays human.

The script edits text and never runs Gradle, so it works on any machine with
`git` and `gh` — including an aarch64 one where the Android SDK cannot run. The
flake exposes the app for both `x86_64-linux` and `aarch64-linux`; the dev shell
stays `x86_64-linux` only, because every Android binary in it is.

**A cut workflow — a button in the Actions tab — was the first choice and was
rejected for a GitHub constraint.** Events caused by a workflow's
`GITHUB_TOKEN` do not start other workflows, so a pull request the workflow
opened would never run CI and would wait forever on its three required checks.
The repository also has "Allow GitHub Actions to create and approve pull
requests" off. The workarounds — a personal access token held as a secret, or
re-dispatching `ci.yml` onto the branch — each add a credential or a reliance on
GitHub behaviour this repository has not verified. A pull request opened by the
author starts CI the ordinary way. Should a button be wanted later, it is a thin
wrapper around this same script.

This is the release-pipeline document's own principle applied again: the cut is
a command that runs the same everywhere and can be watched failing on the
machine, rather than something that exists only inside CI.

### The release note is checked on every pull request

The lint job gains a step that fails if `default.txt` is empty or longer than
500 characters (counted as characters, not bytes). It runs on every PR, though
only a cut changes the file, and it runs before Gradle so a too-long note fails
in seconds. A step inside the existing job rather than a job of its own, because
a new job would not be a required check without a ruleset edit.

### Merging a cut publishes it

`release.yml` triggers on a push to `main` that touches `app/version.properties`,
and on `workflow_dispatch` for a re-run. The tag trigger is removed.

- **Skip** if `v<versionName>` already exists — that version has shipped.
- **Fail** unless `versionName` sorts strictly above the newest `v*` tag
  (`sort -V`). This compares names rather than codes because the 0.99.x tags
  predate the formula: `0.99.8` shipped as code 10, not 9908.
- Run the tests, publish to `automotive:internal` exactly as now.
- **Then** create and push `v<versionName>` at the published commit.

The tag-must-match-`versionName` check goes, because the tag is now derived from
`versionName` and the check would be circular.

The tag follows a successful publish rather than preceding it, so a tag means
"this reached internal". A failed publish leaves no tag and the workflow can
simply be re-run. Pushing the tag with `GITHUB_TOKEN` is fine here precisely
because of the constraint above: nothing is meant to fire from it. The job gains
`contents: write` for the push.

### Production is a promotion, run by hand

`promote.yml` is `workflow_dispatch` only, run against a tag. It calls

    ./gradlew promoteArtifact \
      --from-track automotive:internal --promote-track automotive:production \
      --release-status completed

Nothing is rebuilt: what reaches production is the artifact that was on the
head unit. Play's review of the production release is the remaining gate, and
rollout is 100%.

**It refuses unless the chosen ref is the newest `v*` tag.** Every cut publishes
to internal, so the newest tag is what internal holds, and `promoteArtifact`
promotes whatever internal holds. Without the check, running it against
`v1.0.0` after `v1.0.1` had landed would quietly promote 1.0.1. The check needs
no Play API call.

The job needs the Play credentials and nothing else — no keystore. Gradle Play
Publisher 4.x registers its release tasks for every non-debuggable variant,
signed or not (`PlayPublisherPlugin.kt` gates on `validateDebuggability()`
alone), and promotion uploads no artifact to sign.

Publishing to internal and production together, or to production directly, was
rejected: either loses the stop on the author's own car before a build reaches
anyone else.

## The one-time bootstrap

Before the first promotion, and only with Play credentials:

1. **Confirm `automotive:production` exists** with `edits.tracks.list` (open an
   edit, list, delete the edit — nothing is published). The automotive-track
   document records why a track name is verified against the API rather than
   reasoned out: Google's naming table has already been wrong once.
2. Confirm production has its countries and regions set in the Console.

Then `siskin-cut major` takes 0.99.8 (code 10) to 1.0.0 (code 10000), merging it
publishes to internal, and once it has been seen working in the car,
`promote.yml` runs against `v1.0.0`.

## Testing

`scripts/test-cut-release.sh` runs the cut script against a throwaway git
repository with a stub `gh`, and covers the bump arithmetic, the changelog
roll, the note's 500-character fit and each refusal. The test job runs it.

The workflows are verified by use. 1.0.0 is their first run, and the tag-after-
publish ordering is what makes a failed first run cheap to retry.

## What this does not buy

- **Nothing checks a PR for its changelog bullet.** The 2026-08-12 decision
  stands; the cut only refuses an `[Unreleased]` that is entirely empty.
- **No staged rollout.** `--user-fraction` exists and is one input away; with
  this audience it is not yet worth having.
- **No protection from a wrong production track name** beyond the bootstrap's
  manual check, the same gap the automotive-track document named.
- **No translated release notes.** The listing is en-US only, as before.
