# A dependency bump is gated by an emulator smoke test

CI proves three things today: the unit tests pass, a debug APK builds, and lint
and both style checks are clean. None of them proves the app still *runs*. A
dependency bump can leave every one of those green and still break service
binding, manifest resolution or resource loading — and the only thing that
catches it is someone driving the emulator by hand.

That gap is what this closes. The trigger was a pair of dependabot PRs (#184,
#185) whose review consisted of diffing build-log warnings, because there was
nothing else to look at.

## What this is not: a screenshot diff

The obvious shape — boot the emulator, screenshot the app, compare against a
stored image — is wrong here, and for a reason specific to this fork rather than
a general objection to golden images.

**Most of those pixels are not ours.** There is no launcher activity. The app is
reached through `MEDIA_TEMPLATE` into `com.android.car.media`, which draws the
browse UI from the tree `MediaBrowserTree` and `PlexBrowseRepository` hand it. A
golden-image gate over that screen fails every time Google restyles their media
app and passes clean on a regression they happen to render identically. The
baseline would be a photograph of someone else's UI, maintained on their release
cadence.

The browse contract is the tree, not the picture.

## The client is a `MediaBrowser`, and it is honest about its limits

The gate is an `androidTest` suite — the first in this repository — that binds a
real `MediaBrowser` through a `SessionToken` pointed at `MediaService`, on an
AAOS emulator, and reads the tree back.

**Instrumentation shares the app's process, so this is not proof of IPC.**
`AndroidJUnitRunner` runs under the target package's UID and process, so
`bindService` returns the local `Binder` and the AIDL calls short-circuit rather
than marshalling through parcels. Anyone reaching for this suite to prove the
tree survives a process boundary will not get that from it, and the assertions
should not be written as though they do.

What it does prove, and what Robolectric structurally cannot:

- the APK **installs** on a real `android-automotive` image, so manifest
  merging, the `automotive` hardware requirement and the `launchable` meta-data
  all resolve for real
- `MediaService` **starts and binds** under a real Android runtime — real
  `Context`, real resources, real locale, no Robolectric shadows
- the **service lifecycle** survives force-stop and clear-data

Those are the things a dependency bump breaks.

## The assertions stay thin, deliberately

`MediaLibrarySessionCallbackSignedOutTest` already asserts the exact root
ordering and the signed-out row's title, subtitle and flags — on the JVM, in
seconds, on every PR. Re-asserting all of that on the emulator buys nothing and
doubles what breaks when the tree changes.

So the instrumented suite checks that the root returns four children ending in
`MORE_ID`, and that a non-root parent returns exactly one browsable,
non-playable row. Content correctness stays where it already lives.

That argues for widening `SIGNED_OUT_ROW_ID` from `private` to `internal` rather
than asserting on string resources: the id is stable, while the copy is
user-facing, translated five ways, and likely to be reworded.

## Google's three scenarios, and where they overlap

[Test Android apps for cars][testing] names three `MediaBrowserService` startup
cases every media app must handle, tested by force-stopping and by clearing app
data:

1. the service runs before any Activity opens
2. the service runs when no Activity can be shown
3. the service runs when the user isn't signed in

**The first two are nearly the same state for this fork.** With no launcher
activity the service is the only entry point, and the background
activity-launch restriction means it can never show an Activity itself —
`MediaBrowserTree` already documents both. The honest value is not three
distinct discoveries; it is that the force-stop and clear-data cycles prove the
service comes back clean from a cold start.

Google's own guidance offers no CI-friendly validation for any of this. The tool
it recommends is the [Media Controller Test app][mct], driven by hand.

## `pm clear` kills the instrumentation, so the cycling is external

A test cannot clear its own app's data or force-stop itself — both kill the
process the instrumentation is running in. This is why Google describes them as
manual steps, and it rules out a single test method that walks all three
scenarios.

The shape that follows: **one emulator boot, one build, one install, three
separate `am instrument` invocations**, with `adb` cycling state between them
from the workflow script. Gradle assembles `app-debug.apk` and
`app-debug-androidTest.apk`; the script drives them. Re-running
`connectedDebugAndroidTest` three times would pay for a reinstall each round to
get the same coverage.

**That script must be one heredoc or a checked-in file, never a multi-line
`script:` block.** `android-emulator-runner` runs each line of `script:` as its
own `sh -c`, so a variable assigned on one line is gone by the next. The timing
spike hit this: it assigned `elapsed` and echoed it on the following line, and
reported an empty value with no error. A cycling script written the obvious way
would fail the same way — silently, and in a job whose whole purpose is to be
trusted.

## Screenshots are artifacts, not assertions

A picture cannot fail a build, which is exactly why it is useful here. Capturing
the car's browse UI gives the fidelity a golden-image gate was reaching for —
proof the car really consumed the tree — with none of the churn, because nothing
is compared against a baseline.

Two screens are worth capturing, and they are the only two this app renders in
this state:

- **`CarHostActivity`, signed out** — the sign-in screen, `Disconnected`.
  Siskin's own pixels. `PlexSignInFragment` puts `viewModel.connect()` behind
  the retry button's click listener, so launching it mints no PIN and makes no
  plex.tv call.
- **the car's browse UI** — `com.android.car.media` showing the signed-out row.

Both are `am start`. They run last, after all instrumentation cycling, so
nothing reinstalls underneath the car UI and wedges it.

**They are not viewable inline in the PR, and that is a platform limitation
rather than a configuration mistake.** Artifact links do not render as images in
PR comments ([open request][artifact-images]), and `$GITHUB_STEP_SUMMARY` strips
`data:` URIs through its sanitisation layer ([discussion][summary-images]), so
base64-inlining fails too. Inline images would need external hosting — Imgur, or
committing PNGs to a branch — which trades a third-party dependency in the merge
path, or branch churn, for saving two clicks from the run summary page. Not
worth it.

The failure artifacts are the load-bearing ones. A red emulator job with no
evidence is "something happened on a machine you cannot see." That is what the
script's `on_exit` trap is for: it runs on every exit, pass or fail, and takes
one more screenshot before the documented force-stop cleanup, so a scenario
that fails before either named screenshot is reached still leaves a picture of
whatever was on screen. The trap saves `$?` as its first act and re-exits with
that value, so nothing it does — including a failed screencap — can change the
script's own exit status.

### Waiting for rendered content before a screenshot

`am start` only proves the window opened. The browse tabs come from the root
(one round trip) while the row comes from the selected tab's children (a
second one), and a fixed sleep long enough locally was not long enough on a
loaded CI runner: the tabs and a still-empty row both landed in the same PNG.
`wait_for_text` polls for a marker instead, bounded by wall clock rather than
iteration count — `uiautomator dump` is not instant, so counting iterations
would let a slow runner blow well past the stated 15-second bound.

Each dump is itself capped by `timeout` so one hung call cannot consume the
whole budget; its exit 124 there means "still slow," not "device unreachable,"
so it is treated as "not found yet" and polling continues — a slow dump is the
CI condition this exists to survive, not a failure. Screenshots are artifacts,
so an exhausted deadline is logged, not fatal; a genuine adb/transport failure
is neither — that is the file's existing guard convention.

## The CI job

A third job in `ci.yml` beside `test` and `lint`, not folded into either: they
run in parallel, so wall-clock is `max()` rather than a sum, and the file
already documents why that split exists. It recompiles rather than inheriting,
exactly as `lint` does.

`reactivecircus/android-emulator-runner` at `api-level: 33`, `target:
android-automotive`, `arch: x86_64`, `profile: automotive_1024p_landscape`, with
`emulator-boot-timeout` left at its 600s default: the measured cold path is 93s
and warm is 20s, both comfortably inside it, and a boot timeout only earns its
specific diagnostic by sitting well below the job's own `timeout-minutes` cap —
raised close to that cap, the job's generic timeout fires first and the boot
timeout's own message never surfaces. KVM needs the `99-kvm4all.rules` udev
step; hardware acceleration on standard `ubuntu-latest` runners is free for
public repositories and has been [since April 2024][kvm]. Stale issues
asserting there is no acceleration on ubuntu runners predate that.

**The AVD snapshot cache is optional, and marginal.** Measured, a cold path —
SDK install, image download, AVD creation, boot, snapshot save — is 93 seconds
against a warm boot's 20, and the cache's own restore and save cost about 10.
So it buys roughly a minute in exchange for a cache-invalidation failure mode.
Keep it or drop it; dropping it is a legitimate simplification and the gate
still lands inside budget without it.

**Landscape only, no matrix.** Reading the browse tree is screen-independent —
there is no UI in the assertion path — so the portrait AVD would double the
runtime to re-verify identical data. The two profiles exist for Play
screenshots, which this is not.

### Two hazards to write into the job

**The image pin now lives in two places that must agree.** `flake.nix` pins
`emulatorSdkVersion`, `systemImageType` and `abiVersion` for local work; the
action installs the same artifact through `sdkmanager` for CI. Same image, two
independent declarations — structurally identical to the `localeFilters` and
`locale_config.xml` pair. Changing one without the other means local and CI
quietly test different things.

**The job name freezes the moment it becomes a required check**, the same trap
`ci.yml` already documents for "Lint and Kotlin style": renaming it without
editing ruleset 19853130 in lockstep blocks every later PR on a check that never
reports. The name should not mention the scenarios, which will drift.

## Flake is the thing that kills this, not failure

`MissingTranslation` is the case study. Fifteen near-empty locales and 30
expected errors trained everyone to skim the check, and a check nobody reads is
not a check. An emulator job that goes red on boot timeouts earns the same
reflex, and then the required check is decorative.

Three mitigations, in order:

- the boot timeout comes first — kept at the action's 600s default rather
  than raised, since it only fires with its own diagnostic by staying well
  below the job's cap — and costs nothing when the boot is healthy. The
  snapshot cache helps here too, by removing the image download from the
  common path — but see above: its runtime saving is small enough that it
  should be justified as flake reduction rather than as speed
- **no retry wrapper up front.** Retry is mitigation for flake that has been
  measured; adding it pre-emptively hides the rate that decides whether this
  was worth building
- the `MediaBrowser` connection is awaited with a timeout, never a sleep

## The measurement that could have cancelled this

Runtime was an explicit kill criterion, so the first thing built was not test
code: a throwaway workflow that booted the AAOS emulator on `ubuntu-latest` and
reported timings, installing no APK and asserting nothing. The threshold set
before running it was that under ~6-7 minutes of warm job wall-clock the gate is
clearly worth building, and over ~10 it roughly triples a 3.5-minute feedback
loop and should be abandoned.

It came back well inside that.

| Phase | Seconds |
|---|---|
| Whole job | 138 |
| Cold: SDK install, image download, AVD create, boot, snapshot save | 93 |
| — of which the system image download alone | 29 |
| Warm: boot from snapshot | 20 |
| `screencap` round-trip | ~1 |

Four things it confirmed that nothing found in prior art did:

- `/dev/kvm` is present (`crw-rw-rw-`) after the udev rule on a standard
  4-core `ubuntu-latest` runner
- `android-automotive` at API 33 / x86_64 boots headless there;
  `ro.build.characteristics` reads `automotive`
- `avdmanager` pairs `automotive_1024p_landscape` with the `android-automotive`
  package without complaint, the same as `flake.nix` found locally
- `com.android.car.media` is installed on the image, so it is there to be driven
  for screenshots, and `am get-current-user` is 10 rather than 0 — the same
  multi-user shape the app already has to account for on a real head unit

The gate itself adds a Gradle build of two APKs, an install, three
instrumentation runs and two screenshots to the 20-second warm boot. Against the
existing `test` job's 3m21s for unit tests plus `assembleDebug`, that projects to
roughly 4-6 minutes. **The Gradle portion of that is extrapolated rather than
measured** — the spike deliberately built nothing — so it is the number to watch
first once the real job exists.

The rest of the order: the `androidTest` tier, then the scenarios, then the
screenshots, then green on several real PRs, then the ruleset edit that makes it
required. That edit is last deliberately — a check made required before its
flake rate is known makes its first red a self-inflicted block.

## Not covered

**Nothing signed in.** Playback, scrobbling, artwork decode and every browse
path behind credentials are untouched, because CI has no Plex server. The gate
proves the app installs, binds and serves its signed-out tree; it does not prove
music plays.

**Glide's decode path in particular.** #143 (Glide 4 → 5) is the riskiest open
bump and this gate does not exercise it — `AlbumArtContentProvider` is only
reached with a session. A JVM golden test over that provider's bitmap output
would catch it without an emulator, and is the natural companion to this work
rather than part of it.

**R8.** The job builds and installs a debug APK. Release-only failures stay
outside it, as they are outside `test` and `lint` today.

[testing]: https://developer.android.com/training/cars/testing
[mct]: https://github.com/googlesamples/android-media-controller
[kvm]: https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/
[artifact-images]: https://github.com/orgs/community/discussions/188521
[summary-images]: https://github.com/orgs/community/discussions/101814
