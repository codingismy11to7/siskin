# Warnings as errors on both compilers, and an empty `gradle.properties`

Follow-up to the 2026-08-13 build-hygiene work, which put Kotlin on `-Werror`
and left javac and the configuration phase alone. This finishes #69.

## `-Werror` on javac is inert without `-Xlint`

javac does not report deprecated or unchecked use as warnings by default. It
emits one summary line per compilation:

    Note: Some input files use or override a deprecated API.
    Note: Recompile with -Xlint:deprecation for details.

A note is not a warning, so `-Werror` alone passes a build that is full of them.
This is why #69 had to inject `-Xlint` through an init script merely to *read*
what the deprecations were. The flags go on together:

    tasks.withType(JavaCompile).configureEach {
        options.compilerArgs << '-Xlint:deprecation' << '-Xlint:unchecked' << '-Werror'
    }

`tasks.withType(...).configureEach` rather than a per-variant hook, so it reaches
release as well as debug. Kotlin's equivalent `kotlin { compilerOptions { } }`
block is at the top level of `app/build.gradle` for the same reason: written
inside `android { }` it covers main and silently misses test.

## Both were probed, not assumed

A build that compiles clean proves nothing about whether a *new* warning would
fail it — a misconfigured flag looks identical. So a deprecated call was planted
in each compilation, confirmed to fail, and removed:

| Compilation | Fails on a new warning |
|---|---|
| `compileDebugKotlin` | yes |
| `compileDebugUnitTestKotlin` | yes |
| `compileDebugJavaWithJavac` | yes, deprecation and unchecked |
| `compileReleaseJavaWithJavac` | yes, deprecation and unchecked |
| `compileDebugUnitTestJavaWithJavac` | yes, deprecation and unchecked |

The last is `NO-SOURCE` — every test file is Kotlin — so no ordinary build
exercises it and a gap there would have stayed invisible until the first Java
test source arrived to find it.

Two things that probe taught:

- **A javac deprecation probe needs the declaration and the caller in different
  classes.** javac exempts uses within the same outermost class, so a
  self-contained one-file probe compiles clean and reads as a missing flag.
- **Kotlin's `-Werror` is narrower than "any code smell".** The K2
  *command-line* compiler does not emit `UNUSED_VARIABLE`; an unused local
  compiles clean and lights up only in the IDE. Deprecation does fire, which is
  the case that matters here.

## The three media3 deprecations

ReplayGain stays. #69 raised deleting it as a way to clear two of these for
free; the migrations turned out cheap enough that the question did not need
answering that way.

`BaseAudioProcessor.onFlush()` → `onFlush(StreamMetadata)`, both in
media3-common. The delegation runs `flush(StreamMetadata)` →
`onFlush(StreamMetadata)` → `onFlush()`, so overriding the new method alone is
correct and always invoked.

`androidx.media3.exoplayer.MetadataRetriever` →
`androidx.media3.inspector.MetadataRetriever`. Separate artifact, published at
the pinned 1.9.2, API shape-identical — same `Builder`, still `AutoCloseable`,
same `ListenableFuture<TrackGroupArray>`. Costs 33 KiB in the release APK.

## Why a deprecated `gradle.properties` option is worth removing

AGP deprecates one of these flags when the behaviour it toggles is being
removed; the flag exists as an opt-out from a new default during a migration
window. So a deprecated option holding a non-default value is an escape hatch
AGP intends to weld shut, and it stops working when they get round to it rather
than when this project is ready. The warning is a countdown, not noise. All
seven are now gone.

Four were inert — `targetSdk` is set explicitly, the manifest has no
`<uses-sdk>`, nothing switches on an `R.id`.

**`android.defaults.buildfeatures.resvalues` was not.** `resValue` generates
`plex_account_type`, and with the feature off it would generate nothing,
silently, taking the system account type with it. Migrated to an explicit
`buildFeatures { resValues = true }` rather than deleted.

### `android.r8.optimizedResourceShrinking` was measured, because it ships

| Release APK, unsigned | Bytes |
|---|---|
| before | 6,992,305 |
| + media3-inspector | 7,025,662 |
| + optimized shrinking | 6,524,289 |

457 KiB smaller overall; the shrinker returns 490 against the inspector's 33.

This one needed checking rather than trusting because of `ResourceUris`. AAOS
resolves `android.resource://` URIs **by name only** — it reconstructs a
resource name and hands it to `Resources#getIdentifier`, and a miss means
`getDrawable(0)` on a background thread whose uncaught `NotFoundException` takes
down the car's whole media process. A shrinker that collapsed resource names
would break every browse icon in a way no unit test can see.

It does not. `aapt2 dump resources` on the shrunk APK lists all ten
`ic_browse_*` icons and `media3_icon_shuffle_on` under their own names, plus
`authenticator`, `automotive_app_desc`, `locale_config` and `plex_account_type`.
The saving is unused resources going away, not renaming. Confirmed on the
emulator too: the release build renders its browse tabs and the car app
survives. The check is recorded in `ResourceUris`' javadoc, because a new icon
routed through there wants the same treatment.

### `android.newDsl=false` was defensive, and is no longer needed

This reverses what the 2026-07-31 release pipeline design and CLAUDE.md both
recorded as a permanent keeper, so the evidence matters.

It was never set in response to a failure. That design cites a **closed** GPP
issue noting AGP 9 needs "either a forward-port in the plugin or
`android.newDsl=false`", observes this project already had the latter, and calls
the landmine pre-defused. On GPP 4.0.0 the forward-port is evidently present:
`publishBundle --dry-run` succeeds, `publishBundle` / `promoteArtifact` /
`publishListing` all register for all variants, `bundleRelease` produces a valid
AAB, and `-Pandroid.debug.obsoleteApi=true` — which names legacy-variant-API
callers — reports nobody.

**What none of that covers is `publishBundle`'s execution**, which needs Play
credentials. If GPP resolves the artifact through a removed API at upload time,
the first sign is a failed release rather than a failed build. The next cut is
the real test.

### `android.dependency.useConstraints` was not deprecated, and went anyway

AGP nagged four times a build that
`android.dependency.excludeLibraryComponentsFromConstraints` "should be enabled
to improve performance". #69 correctly declined — that is advice for very large
projects — and kept the nag.

The suppression flag AGP recommends gives the game away:
`android.generateSyncIssueWhenLibraryConstraintsAreEnabled=false`. The sync
issue exists *because* library constraints are enabled, so the opt-in is the
cause. Removing it silences the warning at source rather than muting the symptom
or enabling a second flag to tune something unwanted. Nothing depends on it:
`releaseRuntimeClasspath` and `debugRuntimeClasspath` resolve identically with
and without, 519 lines each.

Do not "fix" this later by adding the suppression flag or enabling the property
AGP asks for. Neither is needed.

## What configuration emits now

Exactly one warning: `android.aapt2FromMavenOverride`, experimental rather than
deprecated, and not in `gradle.properties` at all — `flake.nix` injects it
through `GRADLE_OPTS`, and without it AGP fetches an aapt2 that cannot run on
NixOS. It warns forever and should. That makes it the entire expected output of
a configuration phase, so anything else appearing there is new.

## Not covered

ReplayGain prefetch has not been exercised under R8. That is the only place the
inspector's `MetadataRetriever` actually runs, and reaching it needs a signed-in
release build playing a track. The risk is low — a plain static call-graph edge
rather than reflection, and the inspector AAR ships no consumer proguard rules,
which a library needing keep rules would provide — but it is untested.

`ReplayGainAudioProcessor` has no test coverage, direct or indirect, and its
`onFlush` override is the one structural change here. A future media3 bump that
reshuffles the flush chain would break the audio silently rather than the build.
