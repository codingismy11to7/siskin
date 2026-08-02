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

**`lintDebug` fails on `main` with 9 pre-existing errors.** CI does not run it
— only `testDebugUnitTest` and `assembleDebug` — so a red lint is not
necessarily yours. The baseline breaks down as:

- **8 × `UnsafeOptInUsageError`** across `database/dao/QueueDao.java` and
  `SessionMediaItemDao.java`
- **1 × `UseAppTint`** in `res/layout/fragment_plex_sign_in.xml`

Check the delta against that baseline rather than the absolute count.

**`MissingTranslation` is not in that baseline, and a new one is a real defect.**
Siskin ships five locales — English, German, Spanish, French, Italian — and all
five are complete, so the count is zero. **Every user-facing string you add needs
four translations added with it**, in `res/values-{de,es,fr,it}/strings.xml`;
lint is what tells you when one is missing. This inverts what the section above
used to say: the fork once carried fifteen near-empty locales and 30 expected
`MissingTranslation` errors, which trained everyone to ignore the check. See
`docs/decisions/2026-08-02-five-locales-design.md`.

A string that should not be translated — a proper noun, a URL — takes
`translatable="false"` rather than four copies of itself.

The locale set lives in two places that must agree: `localeFilters` in
`app/build.gradle` and `res/xml/locale_config.xml`. The filter is load-bearing
for more than tidiness — without it the bundle inherits every locale AndroidX,
Material, Glide and media3 ship, which is ~85 and is what made the Play listing
claim 88 languages.

## Toolchain

`flake.nix` supplies JDK 21, the Android SDK, the AAOS emulator image, and `gh`
— `nix develop`, or direnv via `.envrc`. Two helper scripts come from the shell:

    siskin-avd                    # create the AAOS AVD (idempotent)
    siskin-emulator               # boot it

    siskin-avd portrait           # the same, for a portrait head unit
    siskin-emulator portrait

Both take an optional **variant**, defaulting to `landscape`:

| Variant | Device profile | Screen |
|---|---|---|
| `landscape` | `automotive_1024p_landscape` | 1024×768, mdpi |
| `portrait` | `automotive_portrait` | 800×1280, ldpi |

**Those two sizes are Play's, not a preference.** An Android Automotive OS
listing must carry at least two portrait screenshots at 800×1280 and two
landscape at 1024×768, and these are the stock profiles that render at exactly
those sizes — so a capture needs no resizing, which would otherwise misrepresent
what the car draws. Higher-resolution profiles exist
(`automotive_1408p_landscape_with_google_apis` at 1408×792,
`automotive_large_portrait` at 1280×1606) and are fine for looking at the app,
but their captures cannot be uploaded as-is.

Each variant is its own AVD (`siskin-aaos-api33-<variant>`) because an AAOS
screen does not rotate — the car's system UI is built per hardware profile, so
orientation is a property of the AVD and not something to toggle at runtime.
`wm size` / `wm density` will override both on a running device, but that only
stretches the existing profile's UI; it fakes a screenshot size rather than
showing what the car renders. Portrait head units are real hardware — Volvo and
Polestar ship them.

`siskin-emulator` still forwards emulator flags, and a leading `-` is treated as
a flag rather than a variant, so `siskin-emulator -no-snapshot` works unchanged.

Adding a variant is one line in `avdVariants` in `flake.nix`. Only profiles
tagged `android-automotive` are usable: `automotive_1024p_landscape` and the
`automotive_distant_display` pair need `-playstore` and `-distantdisplay` images
that nixpkgs does not package at API 33. `automotive_ultrawide` is excluded
deliberately — at 3904px it is wider than Play's 3840px cap for a screenshot.

**`automotive_1080p_landscape` is 1080 pixels wide and 600 tall, not 1080p.**
It was the original profile here and the name is why the store screenshots and
the emulator disagreed about their size for a while.

The emulator is pinned to **API 33** because that is the only API level for
which nixpkgs carries an `android-automotive` system image.

This is a NixOS machine. When a CLI tool is missing (`jq`, or `adb`/`gh` outside
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

There is **no launcher activity**; `am start` on the package will report "No
activities found to run". That is correct, not a broken build.

There *is* a tile in the car's app grid, which is not a contradiction. AAOS
synthesizes the launcher entry from `MediaService`'s
`androidx.car.app.launchable` meta-data, and tapping it opens the car's media
UI on Siskin rather than starting an activity of ours. Do not go looking for a
`MAIN`/`LAUNCHER` intent-filter to explain the icon, and do not add one.

Reach it the way the car does:

    # Browse tree, through the AAOS media app
    adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
      -e android.car.intent.extra.MEDIA_COMPONENT \
      "us.codingismy11to7.siskin.debug/com.cappielloantonio.tempo.service.MediaService"

    # Sign-in screen directly (normally reached via a PendingIntent on a browse error)
    adb shell am start -n \
      us.codingismy11to7.siskin.debug/com.cappielloantonio.tempo.ui.activity.CarSignInActivity

**Reinstalling kills this app but not the car's UI, and the car does not
recover.** `adb install -r` stops our process while `com.android.car.media`
stays bound to the session that died with it. Now Playing then renders
completely empty -- no title, no artwork, no transport controls, no mini player
-- while the freshly started service plays on: `dumpsys media_session` reports
`state=3`, `error=null` and an advancing position the whole time. It reads
exactly like "playback is broken", and it is an artifact of the install. Restart
both, in this order:

    adb shell am force-stop us.codingismy11to7.siskin.debug --user 10
    adb shell am force-stop com.android.car.media --user 10
    # then the MEDIA_TEMPLATE start above

Restarting only the car app is enough to prove it: the UI comes back bound to
the *same* track at the same position, because playback never stopped.

**AAOS is multi-user, and the app does not run as user 0.** The driver profile
is typically user 10, so app data lives under `/data/user/10/<pkg>/`, *not*
`/data/data/<pkg>/` (which is user 0). Reading the latter shows an empty
directory and invites the false conclusion that nothing was written. Confirm
with `adb shell am get-current-user` first.

Reading that data is `run-as`, and **`run-as` needs the user too**. Plain
`run-as <pkg>` resolves to user 0's data dir, so it answers "No such file or
directory" for files that exist — the same false "the app wrote nothing"
conclusion as above, one step further on:

    adb shell run-as us.codingismy11to7.siskin.debug --user 10 cat shared_prefs/<pkg>_preferences.xml

**Do not reach for `adb root` when something like this fails.** `adb root` and
`adb unroot` restart adbd on the device, which drops every existing adb
connection — including a `scrcpy` mirror that may have been running for days.
`run-as` needs no root. If root is genuinely unavoidable, ask first.

`ServerProbe` builds its own bare `OkHttpClient` with no logging interceptor, so
**its requests never appear in logcat**. An absence of `/identity` calls in the
log means nothing.

## Architecture

### Package naming

`applicationId` is `us.codingismy11to7.siskin` (`.debug` suffix on debug
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
signed-in and make the app query the wrong server. There are two session writes
in `app/src/main` — `PlexSignInViewModel.chooseLibrary` and
`LibraryPickerRepository.selectLibrary` — and both construct the whole session
in one assignment. Sign-in and the More tab both talk to a *candidate* server
without persisting anything until a library is chosen.

`serverToken` is legitimately null for a server the account owns (those accept
the account token), so it is not required for a session to exist.

### Media service — `service/`

`MediaService` is a media3 `MediaLibraryService`; AAOS discovers it through the
manifest intent filter. `MediaBrowserTree` defines the static browse root (four
tabs: Playlists, Artists, Albums, More) — four is the maximum the car renders,
it silently drops a fifth, so nest anything new under More instead of adding a
root tab. `PlexBrowseRepository` serves their contents,
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
