# Warnings are fatal, and CI runs lint

**Date:** 2026-08-13
**Status:** Approved
**Issue:** #26

## Context

Nothing gated on warnings or on lint, so both drifted: three Kotlin warnings,
nine lint errors, and a lint task nobody ran.

## Decisions

### Kotlin compiles with `-Werror`

`allWarningsAsErrors` in `app/build.gradle`. It covers test sources too. A
warning nobody is forced to read is scenery, and the point of clearing the three
was that the next one is visible.

It is a `kotlinc` option, so configuration-time Gradle warnings are out of its
reach.

### Lint gates CI, on errors only

`lintDebug` runs in CI with `abortOnError`. Warnings are not fatal: 27 remain
and clearing them is #99.

**Four checks are disabled** — `NewerVersionAvailable`, `GradleDependency`,
`UseTomlInstead`, `AndroidGradlePluginVersion`. They report whether someone else
has published a newer version, which is not a fact about this repository. They
were 31 of the original 58 warnings, and that volume is what made the other 27
hard to see. Dependency freshness is something to go looking for deliberately.

### AGP compiles the Kotlin

`android.builtInKotlin=true`, and the `org.jetbrains.kotlin.android` plugin is
gone.

That plugin was the sole cause of four warnings: its own deprecation, plus the
three obsolete variant APIs (`applicationVariants`, `testVariants`,
`unitTestVariants`) that it — not this build script — was calling.
`-Pandroid.debug.obsoleteApi=true` is what names the caller.

**`android.newDsl=false` stays.** It is what keeps the legacy variant API
available at all, which the Gradle Play Publisher plugin needs; see the
2026-07-31 release pipeline design. Which plugin compiles Kotlin is a separate
question from whether that API exists.

This was the change most likely to disturb the `kotlin-metadata-jvm` force that
Room depends on, so the release path was exercised rather than assumed:
`assembleRelease`, `bundleRelease` and `publishBundle`'s configuration all
survive it.

## What this does not cover

The deprecated `android.*` options in `gradle.properties` are #69 and need
taking one at a time. This change clears one of them, `builtInKotlin`, as a side
effect.
