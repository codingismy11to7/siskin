# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What Siskin is

A Plex music client for **Android Automotive OS**, running on the head unit
itself. It is a *media source* in the car, not a phone app projecting into one:
there is no launcher activity and no phone audience. `AndroidManifest.xml`
declares `android.hardware.type.automotive` as **required**, so the app will not
install on a phone or a standard emulator image.

Forked from tempus ← tempo (v3.9.0). The fork is being reduced to one job —
play a Plex music library in a car — and most inherited features have been
deleted rather than ported.

## Commands

    ./gradlew testDebugUnitTest          # unit tests (what CI gates on)
    ./gradlew assembleDebug              # debug APK
    ./gradlew assembleRelease            # R8 + ABI splits; use to check size deltas
    ./gradlew lintDebug                  # see the caveat below

Single test class or method:

    ./gradlew testDebugUnitTest --tests '*PlexSessionTest*'
    ./gradlew testDebugUnitTest --tests '*PlexSessionTest.readsBackEveryFieldItWasGiven'

**`lintDebug` fails on `main` with 39 pre-existing errors.** CI does not run it
— only `testDebugUnitTest` and `assembleDebug` — so a red lint is not
necessarily yours. The baseline breaks down as:

- **30 × `MissingTranslation`** in `res/values/strings.xml`. The fork inherited
  translations for many locales and new strings are only added in English, so
  **every user-facing string you add raises this count by one per locale.**
  Expected, not a regression.
- **8 × `UnsafeOptInUsageError`** across `database/dao/QueueDao.java` and
  `SessionMediaItemDao.java`
- **1 × `UseAppTint`** in `res/layout/fragment_plex_sign_in.xml`

Check the delta against that baseline rather than the absolute count.

## Toolchain

`flake.nix` supplies JDK 21, the Android SDK, and the AAOS emulator image —
`nix develop`, or direnv via `.envrc`. Two helper scripts come from the shell:

    siskin-avd        # create the AAOS AVD (idempotent)
    siskin-emulator   # boot it

The emulator is pinned to **API 33** because that is the only API level for
which nixpkgs carries an `android-automotive` system image.

This is a NixOS machine. When a CLI tool is missing (`gh`, `jq`, `adb` outside
the dev shell), reach for `nix run nixpkgs#<tool> -- <args>` rather than working
around its absence.

### Kotlin is capped by Room, not by the language

`app/build.gradle` forces `kotlin-metadata-jvm` **up** to the project's Kotlin
version on the annotation-processor classpaths. This is load-bearing: every
`room-compiler` release bundles a metadata reader capped at format 2.2.0, so
without the force, a Kotlin 2.3+ compiler fails `compileDebugJavaWithJavac` on
every annotation-processed file. The comment in `build.gradle` explains it —
do not delete the force when bumping Kotlin.

## Running the app

There is **no launcher icon**; `am start` on the package will report "No
activities found to run". That is correct, not a broken build. Reach it the way
the car does:

    # Browse tree, through the AAOS media app
    adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
      -e android.car.intent.extra.MEDIA_COMPONENT \
      "io.github.codingismy11to7.siskin.debug/com.cappielloantonio.tempo.service.MediaService"

    # Sign-in screen directly (normally reached via a PendingIntent on a browse error)
    adb shell am start -n \
      io.github.codingismy11to7.siskin.debug/com.cappielloantonio.tempo.ui.activity.CarSignInActivity

**AAOS is multi-user, and the app does not run as user 0.** The driver profile
is typically user 10, so app data lives under `/data/user/10/<pkg>/`, *not*
`/data/data/<pkg>/` (which is user 0). Reading the latter shows an empty
directory and invites the false conclusion that nothing was written. Confirm
with `adb shell am get-current-user` first.

`ServerProbe` builds its own bare `OkHttpClient` with no logging interceptor, so
**its requests never appear in logcat**. An absence of `/identity` calls in the
log means nothing.

## Architecture

### Package naming

`applicationId` is `io.github.codingismy11to7.siskin` (`.debug` suffix on debug
builds) but the source package is still `com.cappielloantonio.tempo` from the
upstream fork. Both are correct; do not "fix" one to match the other.

### The Plex layer — `plex/`

Hand-rolled against roughly twelve endpoints, deliberately not generated from
the community OpenAPI spec (see the API-layer decision doc for why). Two
Retrofit instances, built by `PlexRetrofitFactory`:

- **plex.tv** — fixed URL, usable before sign-in (PIN flow, server discovery)
- **the media server** — address discovered after auth, baked into the base URL
  at construction. Clients are *pinned* to what they were built with;
  `PlexBrowseRepository.refreshClients()` rebuilds them when the session
  changes.

Keeping them separate makes "this call works signed out" a compile-time
distinction.

### Errors are values, and typed per operation

Clients return `Either<PlexTransportFailure, T>` — they do not throw.
`plexCall` is the single adapter converting Retrofit's `IOException` /
`HttpException` into values, and it catches **only** those two.

`PlexTransportFailure` carries the **host** it failed against, which is what
makes "could not reach plex.tv" and "could not reach that Plex server" fall out
of the type instead of being reconstructed at each call site. Operations with a
failure of their own get their own error type — `CreatePinError` is the only
one so far.

**The one rule: never put a broad catch lexically inside an `either { }`
block.** Arrow's `raise` short-circuits by throwing a `CancellationException`
subclass, and on the JVM that extends `IllegalStateException` — so
`catch (Exception)`, `catch (RuntimeException)` and `catch (IllegalStateException)`
all swallow it silently, producing a wrong value or a `RaiseLeakedException` far
from the cause.

Broad catches *outside* an `either { }` are fine and several are deliberate.
`PlexBrowseRepository.launchInto` in particular **must** keep catching
`Throwable` including cancellation: media3 waits on that `ListenableFuture`, and
one that never completes leaves the car's tab spinning until the vehicle gives
up. Do not "clean these up".

Relatedly: never `raise` across a coroutine-builder boundary (`launch`,
`async`). `ServerProbe.race()` returns `String?` for this reason.

### Credentials — `PlexSession`

Four values (`accountToken`, `serverUri`, `musicSectionKey`, `serverToken`)
describe **one** connection and are persisted as a unit or not at all. A mixed
set — a section key from one server beside another's address — would read as
signed-in and make the app query the wrong server. `chooseLibrary` holds the
only session write in `app/src/main`; sign-in talks to a *candidate* server
without persisting anything.

`serverToken` is legitimately null for a server the account owns (those accept
the account token), so it is not required for a session to exist.

### Media service — `service/`

`MediaService` is a media3 `MediaLibraryService`; AAOS discovers it through the
manifest intent filter. `MediaBrowserTree` defines the static browse root (three
tabs: Playlists, Artists, Albums), `PlexBrowseRepository` serves their contents,
and `MediaLibraryServiceCallback` turns a 401/403 into the "sign in again"
affordance via `CarSignInResolution`'s `PendingIntent`.

The HTTP-versus-transport distinction matters here: an HTTP failure becomes a
`LibraryResult` error the car can act on, while a transport failure completes
the future *exceptionally* so it reads as "unreachable" rather than "rejected".

`MediaManager.java` is still Java and cannot call suspend functions;
`PlexScrobbler` exists as the Kotlin bridge for exactly that reason.

## Testing

JUnit 4 with Robolectric (`unitTests.includeAndroidResources = true`), Mockito,
and MockWebServer. Roughly half the test classes need
`@RunWith(RobolectricTestRunner::class)` — anything touching `PlexApi`, which
reads `App.getInstance().preferences` and therefore needs a real `Context`.

`unitTests.returnDefaultValues = true` stubs `android.jar`, so a test that only
touches Android framework classes can pass while asserting nothing. Gson,
Retrofit and OkHttp are untouched by it, so round-trip and MockWebServer tests
are real coverage.

Robolectric caches `SharedPreferences` statically **across test methods**, so
tests that write preferences must reset every field they depend on in `@Before`
rather than assuming absence.

## Conventions

Design specs go in **`docs/decisions/`** as `YYYY-MM-DD-<topic>-design.md`, and
are the durable record of *why* — they lean toward rationale and
alternatives-considered over step-by-step mechanics. Read the relevant one
before changing an area; several document hazards that are not obvious from the
code. Implementation plans live in `docs/plans/`, which is gitignored: they are
throwaway and must never be committed.

Existing comments that explain *why* are load-bearing and frequently document a
real hazard. When a type removes the hazard, delete the comment with it; when it
does not, keep it.
