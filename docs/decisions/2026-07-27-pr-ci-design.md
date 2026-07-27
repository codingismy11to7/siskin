# Run the test suite in CI on pull requests

**Date:** 2026-07-27
**Status:** Approved

## Context

Siskin has a unit test suite that has never run in continuous integration. Not
once, on any commit, in this fork or upstream.

`.github/workflows/` contains exactly two files, and both are tag-triggered:

- `github_prerelease.yml` — `on: push: tags: ['v*-dev*']`
- `github_release.yml` — `on: push: tags: ['v*']`

There is no `pull_request` trigger anywhere in the repository, and neither
workflow invokes a test task. Both go straight from `assembleDebug` /
`assembleRelease` to signing and publishing.

Upstream tempo/tempus gets away with this because it has almost nothing to lose:
its only two tests, `BaseSessionCallbackTest` (added in #752) and
`LiveDataUtilsTest` (added in #793), are vestigial. Nobody depends on them
passing because nobody has ever seen them run.

The exposure is sharper for Siskin. Three of the five test classes are the
fork's own, and all three cover the AAOS work that is the reason the fork exists:

| Test class | Added by |
|---|---|
| `AutomotiveRepositoryTest` | #1, Rebrand Tempus → Siskin |
| `CredentialGateTest` | #4, AAOS sign-in flow |
| `SystemRepositoryTest` | #4, AAOS sign-in flow |

Each of those arrived through a pull request whose tests ran only on the
author's laptop. With the Plex API layer now being built on top of that sign-in
flow, the untested-merge path is about to get more traffic, not less.

The suite is currently green — 27 tests across 5 classes, 0 failures, via
`nix develop --command ./gradlew testDebugUnitTest`. So this change turns on a
check that passes today rather than importing a backlog of red.

## Decisions

### Add `.github/workflows/ci.yml`, triggered on PRs and on `main`

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  test:
    name: Unit tests and debug build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: '21'

      - name: Cache Gradle and wrapper
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest

      - name: Build debug APK
        run: ./gradlew assembleDebug

      - name: Upload test report
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-report
          path: app/build/reports/tests/testDebugUnitTest/
```

### Run `assembleDebug`, not just the tests

`testDebugUnitTest` already depends on `compileDebugKotlin` and
`compileDebugJavaWithJavac`, so tests alone would catch a compile break in main
sources. What they would not catch is a failure during resource merging,
manifest processing or packaging — which is precisely the class of breakage this
fork keeps touching, given it edits the manifest and strips a launcher entry.

`assembleDebug` is also exactly what `github_prerelease.yml` runs, so a green PR
means the prerelease tag build will work.

### `push: [main]` is not redundant with the PR trigger

GitHub scopes Actions caches by branch: a cache written inside a PR branch is
visible only to that PR and is never shared with sibling PRs. Caches written on
the **default** branch are readable by every PR. The `main` run is therefore
what keeps PR runs warm — without it, each new PR pays a cold Gradle cache.

It also catches breakage that only appears post-merge.

This is why `cancel-in-progress` is conditional rather than simply `true`.
Cancelling superseded runs is right for a pull request, where only the latest
push matters. Applying it to `main` would work against the cache: two merges
landing in quick succession would cancel the first run before it saved its
cache, which is the very thing the `main` trigger exists to produce.

### Fix the cache key while adding it

The existing workflows key on `hashFiles('**/*.gradle*')` with no
`restore-keys`. Two problems, both inherited, both worth not reproducing:

1. **No fallback.** Touching any Gradle file yields a total cache miss and a
   full dependency re-download. The `${{ runner.os }}-gradle-` prefix key lets a
   dependency bump reuse the previous cache and fetch only the delta.
2. **The glob misses the version catalog.** Dependencies in this repo change in
   `gradle/libs.versions.toml`, which `**/*.gradle*` does not match. Today a
   dependency bump silently reuses a stale cache key. The new key hashes the
   catalog and the wrapper properties explicitly.

### Two smaller departures from the existing workflow style

- **No `chmod +x ./gradlew` step.** `gradlew` is already mode `755` and git
  tracks the exec bit. The step in the existing workflows is vestigial.
- **`checkout` runs before `setup-java`.** The existing files invert this, which
  works only because they pass no `cache:` parameter to `setup-java`. Ordering
  it correctly removes a trap for whoever adds one later.

These are not applied retroactively to the tag workflows; see Scope.

### Gate both tag workflows on the tests

One step added to each, after `Make gradlew executable` and before the build,
using `bash ./gradlew` to match the surrounding style in those files:

```yaml
- name: Run unit tests
  run: bash ./gradlew testDebugUnitTest
```

This closes the hole where a tag is pushed at a commit that never went through a
pull request — which is how the current tests got merged unverified in the first
place. Once sources are compiled the tests add well under a minute.

In `github_release.yml` the step lands **above** `Build Release APKs`, leaving
the `sed -ri '/foojay-resolver-convention/d' settings.gradle` line where it is,
inside the build step. Tests therefore run against an unmodified
`settings.gradle`. That is deliberate and safe: `github_prerelease.yml` already
builds without ever applying that sed, so the un-sed'd configuration is proven
to work on a runner. The sed affects JDK toolchain provisioning, not compiled
output, so testing on either side of it exercises the same code. Hoisting the
sed above the test step is the alternative and buys no behavioural change for a
noisier diff.

### Make the check blocking via branch protection on `main`

Workflows alone make tests *run*, not *block*. A red check is advisory until
`main` requires it, which leaves us close to where we started: failures anyone
can merge past.

`main` is the default branch of a public repo and is currently unprotected
(`GET /branches/main/protection` → 404). The rule to apply:

```bash
nix run nixpkgs#gh -- api -X PUT repos/codingismy11to7/siskin/branches/main/protection \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["CI / Unit tests and debug build"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": null,
  "restrictions": null
}
JSON
```

The context string `CI / Unit tests and debug build` is composed from the
workflow `name:` and the job `name:`; it must match those exactly or the rule
waits forever on a check that never reports.

Field choices, each of which would deadlock a solo repository if set the
obvious way:

- `enforce_admins: true` — the rule binds the repo owner too. A gate that the
  only person using the repo can walk through is not a gate. The emergency path
  is to disable protection deliberately and temporarily, which leaves an audit
  trail and requires an explicit decision, rather than a permanent standing
  bypass that erodes silently.
- `required_pull_request_reviews: null` — requiring an approval would deadlock
  the repo outright, since GitHub does not permit approving your own pull
  request.
- `restrictions: null` — a required key on this endpoint; only meaningful for
  organization-owned repositories.
- `strict: true` — a branch must be current with `main` before merging.

**Ordering constraint.** Protection is applied **last**, after the workflows are
merged to `main`. Applying it first would require a check that does not yet
exist on the default branch, blocking every pull request — including the one
that adds the workflow.

Direct pushes to `main` will be rejected once this lands, including the owner's.
That costs nothing here: all work on this fork has gone through pull requests
already, so the rule formalises existing practice rather than changing it.

## Scope

**In:** the new `ci.yml`; the test step added to both tag workflows; the branch
protection rule.

**Out:**

- **Android Lint.** This is a fork of a large upstream codebase that has never
  had lint enforced. Expect a substantial pre-existing warning baseline needing
  a `lint-baseline.xml` before it could gate anything — its own piece of work.
- **Instrumented / emulator tests.** `testInstrumentationRunner` is declared in
  `app/build.gradle:21` and the flake can boot an AAOS emulator, but running
  that in CI is a much larger job with its own reliability profile.
- **Running CI through the nix flake.** Considered and rejected: it would need a
  new lean `devShells.ci` (the default shell pulls the emulator and the ~1GB
  `android-automotive` API 33 system image that CI never boots), a Nix installer
  action and a binary cache. `setup-java` plus the runner's preinstalled SDK is
  what the existing release workflows already use, so PR CI validates the same
  toolchain that actually ships. The flake stays the local-dev story.
- **`softprops/action-gh-release@v1`.** actionlint flags it under the same
  too-old-runner rule as the actions bumped below, but v2 changed defaults in
  the release-publishing path. That risk does not belong in a CI change.
  Follow-up.
- **Cosmetic cleanup of the tag workflows** — the vestigial `chmod`, the
  `setup-java`-before-`checkout` ordering. Real, but not in service of getting
  tests running.

### Amended during planning: bump `checkout` and `cache` to v4

Originally scoped out as unrelated modernisation. `actionlint` then reported
`actions/checkout@v3` and `actions/cache@v3` in both tag workflows as *"too old
to run on GitHub Actions"* — the Node 16 runtime deprecation, not a style nit.

A test gate added to a workflow that cannot run is worthless, so the bump is now
in scope for the two files this change already edits. Both are drop-in at these
call sites; no input names changed between v3 and v4 for the `path`/`key` usage
here. `ci.yml` uses v4 from the start.

## Verification

- `ci.yml` parses and appears in the Actions tab.
- A pull request against `main` triggers the run; it reports both a test result
  and a successful `assembleDebug`.
- A deliberately failing assertion, pushed to a scratch branch's PR, turns the
  check red and publishes the `unit-test-report` artifact. Revert after.
- A second push to the same PR while a run is in flight cancels the first, and a
  push to `main` does **not** cancel an in-flight `main` run.
- After protection is applied: `GET /branches/main/protection` returns the rule,
  and a PR with a failing check shows merge as blocked.
- A cold-cache run followed by a warm one shows the cache being restored — the
  point of the `restore-keys` change.
