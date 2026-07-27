# Tempus → Siskin rebrand and de-flavoring

**Date:** 2026-07-26
**Status:** Approved

## Context

Siskin is a fork of [eddyizm/tempus](https://github.com/eddyizm/tempus), which is
itself a fork of [cappielloantonio/tempo](https://github.com/cappielloantonio/tempo).
The fork exists to target **Android Automotive OS (AAOS)** — running natively on
head units rather than projecting from a phone.

Two things follow from that goal, and this document covers both:

1. The app still identifies as Tempus throughout, including artwork that belongs
   to the upstream fork's author.
2. The build carries a `degoogled` product flavor that AAOS makes pointless.

### What the survey found

The Java package is `com.cappielloantonio.tempo` — untouched by the Tempus fork.
Renaming it would churn 400+ files for no benefit, and it is not a user-visible
identifier. **It stays.** The `tempo` name in source paths, `namespace`, and class
names is inherited upstream heritage, not Tempus branding.

`tempus` appears in 44 files across app identity, build flavors, user-visible
strings (14 locales), the GitHub update checker, CI workflows, fastlane metadata,
and docs.

The launcher artwork was introduced by the Tempus fork in `7a83a03a` and refreshed
in `3cd1bdf2`. It is eddyizm's brand mark. GPL v3 covers the code; it does not
grant rights to a project's identity, so the artwork must be replaced regardless
of license compatibility.

One asset is easy to miss: `ic_toolbar_tempo.xml` has a *Tempo* filename but
carries *Tempus* artwork — its `pathData` is byte-identical to `logo.xml`. It
renders in the toolbar and nav-drawer header across four layouts. The filename
follows the "leave `tempo` alone" rule; the drawing does not.

### Why de-flavoring falls out of the AAOS goal

The `degoogled` flavor exists for exactly one reason: to stub out Google Cast.
`Flavors.java` is the whole difference — one implementation calls
`CastContext.getSharedInstance()`, the other is an empty method body. The rest of
`app/src/degoogled/` (its own `MediaService.kt`, `ToolbarFragment.java`, menu,
icon set) exists only to support that split, and `tempusImplementation
libs.media3.cast` is the only flavor-scoped dependency.

AAOS ships with Google Play Services. A de-Googled variant serves no one on the
target platform, so the flavor is dead weight.

Removing it leaves a single flavor — and a single flavor is no flavor at all.
This is worth noting because it *subsumes most of the rename work*: there is no
`tempus` flavor left to rename, no `assembleTempusRelease` task, no
`app/build/outputs/apk/tempus/` path. The directory simply merges into `main`.
Collapsing the flavor dimension is less work than renaming it.

## Decisions

### Keep the Java package, change the applicationId

| Item | From | To |
|---|---|---|
| `applicationId` | `com.eddyizm.tempus` | `io.github.codingismy11to7.siskin` |
| `namespace` | `com.cappielloantonio.tempo` | unchanged |
| Java package tree | `com/cappielloantonio/tempo/` | unchanged |
| Product flavors | `tempus`, `degoogled` | none |

`io.github.*` was chosen over `com.<user>.*` because it is F-Droid's preferred
convention for GitHub-hosted apps — the namespace is demonstrably controlled via
`github.io`. The applicationId is the permanent store identity; changing it after
a release forces users to reinstall, so it is settled now rather than later.

### Collapse the flavor dimension

Delete `app/src/degoogled/`. Merge `app/src/tempus/**` into `app/src/main/**` and
`app/src/testTempus/**` into `app/src/test/**` using `git mv` so history survives.
Remove `flavorDimensions` and `productFlavors`; `applicationId` moves to
`defaultConfig`. `tempusImplementation libs.media3.cast` becomes a plain
`implementation`.

`BuildConfig.FLAVOR.equals("tempus")` at `MainActivity.java:565` becomes a
constant `true` and is dropped, keeping the meaningful half of the condition
(`Preferences.isGithubUpdateEnabled() && Preferences.showSiskinUpdateDialog()`).

**Cast is retained.** The surviving `Flavors.java` is the Cast-enabled one.
Removing Cast is a functional change beyond a rebrand, and it belongs to the AAOS
work — casting *from* a head unit makes little sense, so that spec is the right
place to decide its fate.

### Replace the artwork

One new siskin vector mark, authored for this project, applied to `logo.xml`,
`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_splash_logo.xml`,
and `ic_toolbar_tempo.xml`. The degoogled copies disappear with the flavor.

`mockup/*_tempus.png` and `mockup/svg/tempus-horizontal-banner.png` are deleted
rather than edited — they are screenshots of a differently-branded app. README
screenshot references are dropped until they can be re-shot against the rebranded
build.

### Rename code identifiers, not preference keys

`Github.java` `OWNER`/`REPO` → `codingismy11to7`/`siskin`.

`Preferences.showTempusUpdateDialog()` / `setTempusUpdateReminder()` → `…Siskin…`.
These are **method names only** — verified that the persisted SharedPreferences
keys are `GITHUB_UPDATE_CHECK` and `NEXT_UPDATE_CHECK`, which contain no brand
string. No user-data migration is needed.

### Edit locale strings per-file, not by global replace

`app_name` → `Siskin`, plus 11 further strings in `values/strings.xml` and 133
across 14 locale files. These are edited per-file rather than swept with `sed`: a
blind global replace silently mangles translations where "Tempus" sits mid-sentence
under different grammar and declension rules.

### Preserve the changelog, fork the history forward

`CHANGELOG.md` has 371 Tempus references describing releases that genuinely shipped
as Tempus. Rewriting them to say Siskin would make the file assert things that
never happened. Existing entries stay untouched under a header noting the fork
lineage; a new Siskin section starts on top.

README, USAGE, and CONTRIBUTING are fully rebranded — they describe the project as
it is now, not as it was.

### Strip distribution badges to what actually exists

README badges for F-Droid, IzzyOnDroid, Obtainium, OpenAPK, and the rbtlog
reproducible-build shield all key off `com.eddyizm.degoogled.tempus`. Siskin is in
none of those channels, so repointing them yields badges that render as 404s.
They are removed and re-added as Siskin is actually accepted. Downloads, license,
and GitHub releases remain.

This also happens to align with the AAOS goal: AAOS distribution runs through Play
(Automotive), so the F-Droid ecosystem is largely irrelevant to this fork.

### AAOS enablement is explicitly out of scope

The manifest currently configures Android **Auto** (phone-projected) via
`com.google.android.gms.car.application` and `auto_app_desc.xml`. There is no
`<uses-feature android:name="android.hardware.type.automotive" />`, which is what
AAOS actually gates on. The `ic_aa_*` drawables and `AutomotiveRepository` are
media-browser code that AAOS reuses, but the manifest side is absent.

Real AAOS support needs emulator testing and car-UI browse-tree work. Bundling it
with a rename would produce a branch where a build failure could stem from either.
It gets its own spec.

## Consequences

- New `applicationId` means stores treat this as a fresh app. Tempus users will
  not receive it as an update — correct, since it is a different project.
- versionCode 38 / versionName 4.22.2 carry over unchanged. Continuity costs
  nothing and keeps the update-checker's comparison logic meaningful.
- The 100 inherited git tags stay unpushed; they describe Tempus releases.
- Gradle tasks simplify to `assembleDebug` / `assembleRelease`, which removes a
  substantial amount of both GitHub workflows.
- Anyone who wanted a Cast-free build loses that option. Acceptable: AAOS has
  Play Services, and that audience is served by upstream Tempus.

## Verification

- `./gradlew assembleDebug assembleRelease testDebugUnitTest`
- `git grep -i tempus` — only CHANGELOG history and fork-attribution lines survive
- `git grep -i degoogled` — no results
- Launcher icon, splash, toolbar, and nav-drawer header visually confirmed as the
  new mark

## Alternatives considered

**Rename the Java package to `siskin`.** Rejected: 400+ files churned, no
user-visible benefit, and it would make future merges from upstream Tempo or
Tempus painful.

**Rename the `tempus` flavor to `siskin` and keep `degoogled`.** Rejected once the
AAOS goal was known — it preserves a variant with no audience and keeps the flavor
machinery that de-flavoring removes outright.

**Global `sed` across all tracked files.** Rejected: cannot distinguish the flavor
name from the word, would rewrite the CHANGELOG we are deliberately preserving,
and risks mangling 13 locale files.

**AAOS-only targeting (`required="true"`).** Deferred. It would drop phone
installs entirely and turn much of the phone UI into prunable dead code — a real
product decision that deserves its own discussion, not a side effect of a rename.
