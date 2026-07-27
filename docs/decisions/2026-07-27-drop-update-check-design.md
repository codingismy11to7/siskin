# Remove GitHub release update checking

**Date:** 2026-07-27
**Status:** Approved

## Context

Siskin inherits an update checker from upstream Tempo. On launch, `MainActivity`
calls `GET repos/{owner}/{repo}/releases/latest` against the GitHub API, compares
the returned tag to `BuildConfig.VERSION_NAME`, and if the remote is newer shows a
dialog whose **Download now** button fires an `ACTION_VIEW` intent at the release
page. A settings toggle enables or disables the check; a "Remind me later" button
suppresses it for 24 hours.

Siskin is moving to Play Store distribution, which makes the feature both
redundant and non-compliant:

- Play updates the app itself. A second, parallel update path is noise.
- Play policy prohibits an app from directing users to APKs distributed outside
  the store. The **Download now** button does exactly that.

There is no in-app installer to unwind. The manifest declares no
`REQUEST_INSTALL_PACKAGES` permission — the dialog only opens a browser — so
removal touches no permissions.

The whole `github/` package exists solely to serve this feature. Nothing else in
the app consumes it, so this is a clean excision rather than an untangling.

## Decisions

### Delete the feature entirely

**Files deleted outright:**

| Path | Notes |
|---|---|
| `app/src/main/java/com/cappielloantonio/tempo/github/` | Entire package, 10 files: `Github.java`, `GithubRetrofitClient.kt`, `api/release/ReleaseClient.java`, `api/release/ReleaseService.java`, `models/{Assets,Author,LatestRelease,Reactions,Uploader}.kt`, `utils/UpdateUtil.java` |
| `app/src/main/java/com/cappielloantonio/tempo/ui/dialog/GithubTempoUpdateDialog.java` | |
| `app/src/main/res/layout/dialog_github_tempo_update.xml` | |

**String keys deleted**, from `values/strings.xml` and every localized
`values-*/strings.xml` that carries them:

| Key | Present in |
|---|---|
| `github_update_dialog_title` | 14 files |
| `github_update_dialog_summary` | 14 files |
| `github_update_dialog_positive_button` | 14 files |
| `github_update_dialog_negative_button` | 14 files |
| `settings_github_update` | 12 files |
| `settings_github_update_title` | 12 files |
| `settings_github_update_summary` | 12 files |

**Translations are partial and the counts differ by key** — 14 files carry the
dialog strings, only 12 carry the settings strings. The deletion must tolerate a
key being absent from a given locale rather than assuming a uniform edit across
all files. A tool that errors on "key not found" will fail on the two locales
that never translated the settings block.

**These are distinct-file counts, not resource-directory counts.** Counting by
resource *directory* instead yields 15 and 13, because
`app/src/main/res/values-zh-rCN` is a git-tracked symlink (mode `120000`) to
`values-zh` — the two directories resolve to one physical `strings.xml`, so a
directory-based count double-counts it. A deletion script that filters and
edits distinct tracked files will correctly report **14** files touched for the
dialog keys and **12** for the settings keys, not 15/13; this mismatch already
caused confusion once during implementation.

**Preference screen:** the entire `ClickablePreferenceCategory` keyed
`settings_github_update_category_key` in `app/src/main/res/xml/global_preferences.xml`
(around lines 638–648), including the nested `SwitchPreference` keyed
`github_update_check`.

**Files edited in place:**

- `App.java` — the `github` field, `getGithubClientInstance()`, and the import.
- `repository/SystemRepository.java` — `checkTempoUpdate()` (around line 144) and
  the `LatestRelease` import. The rest of the class is unrelated and stays.
- `viewmodel/MainViewModel.java` — the `checkTempoUpdate()` passthrough and import.
- `ui/activity/MainActivity.java` — the `checkTempoUpdate()` call (around line 122),
  the private method (around line 570), and the `UpdateUtil` /
  `GithubTempoUpdateDialog` imports.
- `util/Preferences.kt` — constants `GITHUB_UPDATE_CHECK` and `NEXT_UPDATE_CHECK`,
  and the functions `isGithubUpdateEnabled()`, `showSiskinUpdateDialog()`,
  `setSiskinUpdateReminder()`.

### Leave the stored preference values orphaned

Removing the code leaves any previously written `github_update_check` and
`next_update_check` entries in `SharedPreferences` with nothing reading them.

They are deliberately not cleaned up. A migration would add a code path running on
every launch, forever, to reclaim one boolean and one long. Siskin has no install
base to migrate — it has never shipped a release, and the fork has zero tags. This
is the YAGNI call.

### Retrofit and OkHttp stay

Deleting `GithubRetrofitClient.kt` does not orphan the HTTP dependencies. 61 files
outside `github/` use Retrofit, across the Subsonic client and the newer Plex API
layer. No dependency changes.

## The `share_update_dialog` trap

The single most likely way to get this change wrong is pattern matching on
`update`.

The Subsonic **share** feature owns names that match every obvious pattern but
have nothing to do with update checking:

- `share_update_dialog_title`,
  `share_update_dialog_positive_button`, `share_update_dialog_negative_button`,
  `share_update_dialog_hint_description`,
  `share_update_dialog_hint_expiration_date`
- `app/src/main/res/layout/dialog_share_update.xml`

A regex sweep on `update_dialog` or `update` deletes the share feature silently —
it compiles right up until the share dialog is opened.

**Deletions must be driven by the seven exact string keys and the enumerated file
paths above, never by pattern.** Verification below asserts these share names are
still present afterwards, not merely that the update names are gone.

## Scope

**In:** the deletions and edits listed above.

**Out:**

- Any other Play Store readiness work — store listing, target API review,
  privacy policy, the `dependenciesInfo` block, signing configuration. Real, but
  each is its own decision.
- Migrating or clearing the orphaned preference values.
- The `github/` package name is not reused or replaced. Nothing takes its place.

## Verification

The compiler is the primary check, and an unusually strong one for a deletion:
removing a class still referenced anywhere fails compilation, and removing a
string still referenced as `R.string.x` also fails, because the generated `R`
field disappears.

- `nix develop --command ./gradlew assembleDebug` succeeds. This is the real
  evidence that the removal is complete and internally consistent.
- `nix develop --command ./gradlew testDebugUnitTest` — expect the same **27
  tests across 5 classes, 0 failures**. `SystemRepositoryTest` does not touch
  `checkTempoUpdate()`, so a changed count means something unintended moved.
- Absence sweep over `app/src` returns nothing for: `tempo.github`,
  `LatestRelease`, `UpdateUtil`, `GithubTempoUpdateDialog`, `github_update`.
- **Presence sweep** confirms `share_update_dialog` keys and
  `res/layout/dialog_share_update.xml` still exist and are still referenced.
- On the emulator: Settings shows no "Updates" category, and launching the app
  shows no update dialog.

No new tests. There is no behavior left to assert; a test that the app does not
check for updates would be asserting absence, which the compiler and the sweeps
already establish. The existing suite's role here is regression detection.

This lands as a pull request against `main`, gated by the required check added in
`2026-07-27-pr-ci-design.md` — the first change to go through it.
