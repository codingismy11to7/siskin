# A tag-driven release pipeline for Play

**Date:** 2026-07-31
**Status:** Approved
**Issue:** #43

## Context

There is no path — local or automated — to an artifact Play will accept.

`assembleRelease` emits ABI-split APKs rather than a bundle, and the `release`
build type has no `signingConfig` at all. Signing existed only inside the
inherited `github_release.yml` / `github_prerelease.yml`, which sign after the
fact with `r0adkll/sign-android-release`. So `bundleRelease` today produces an
**unsigned** bundle, which Play rejects.

Two things turned up while scoping this, and both simplify it:

- **`gh secret list` on this repo returns nothing.** `KEYSTORE_BASE64`,
  `KEY_ALIAS_GITHUB` and the rest have never been set. The inherited workflows
  reference secrets that do not exist and would have failed on the first tag.
  Nothing has ever been tagged, so this was never discovered.
- **There is no keystore anywhere** — no `.jks` under `~/dev`, no
  `~/.gradle/gradle.properties`. The `siskin-release-key.jks` line in
  `.gitignore` is aspirational, inherited from upstream.

So there is nothing to preserve compatibility with and nothing to reuse. This is
a clean start, which is the easiest version of this problem there is.

## Decisions

### The goal is "tag it and it ships", not "document how to ship it"

Manual upload through the Console is less work once. It is the wrong trade for
this project anyway, because the pattern that matters is not the first release —
it is the bug fix eighteen months from now, at night, when the process has been
forgotten. A pipeline is the version of the knowledge that does not decay, and
it is the difference between fixing something alone and needing help to do it.

This is what tips every decision below toward more setup cost and less
tribal knowledge.

### Gradle Play Publisher, not a GitHub Action

`com.github.triplet.play`, over the more common `r0adkll/upload-google-play`.

The deciding property is that `./gradlew publishBundle` is **the same command
locally and in CI**. When the pipeline breaks — and over an eight-year horizon
it will — the release path can be run and watched to fail on the machine, rather
than debugged by pushing tags and reading CI logs. An action-based pipeline
exists only inside CI and cannot be exercised anywhere else. Given the reasoning
above, that difference is the whole point.

Secondary: GPP's `publishListing` can later hold the store listing copy (#45) as
files in the repo instead of in a Console form, and `promoteArtifact` can move a
build from internal to closed or production without rebuilding it.

**GPP's health was checked, because its README is misleading.** It describes
itself as in maintenance mode — "issues are ignored, but pull requests are not"
— which reads worse than reality: 4.0.0 shipped 2026-01-25, and commits have
landed as recently as 2026-06-08. Every AGP 9 compatibility issue is closed,
including the `BaseAppModuleExtension` removal. One of those closed issues notes
AGP 9 requires either a forward-port in the plugin or `android.newDsl=false` in
`gradle.properties` — **which this project already sets**, so the known landmine
is pre-defused.

### Signing credentials come from the environment, and their absence is not an error

`signingConfigs.release` is populated from `SISKIN_KEYSTORE_PATH`,
`SISKIN_KEYSTORE_PASSWORD`, `SISKIN_KEY_ALIAS` and `SISKIN_KEY_PASSWORD`. The
`release` build type applies the config **only when all four are present**.

Absent, the build behaves exactly as it does today — unsigned release output,
and `assembleDebug` / `testDebugUnitTest` completely unaffected. That matters
for two reasons: CI's existing test job must not need credentials, and a fresh
clone by anyone must still build.

Environment variables rather than `gradle.properties` because they unify the two
call sites. CI injects them from secrets; locally `sops exec-env` supplies them
for the duration of one command without writing plaintext to disk. A
`~/.gradle/gradle.properties` holding passwords is exactly the sort of
unmanaged machine state a sops-based setup exists to eliminate.

Same reasoning for the Play credentials: GPP reads
`ANDROID_PUBLISHER_CREDENTIALS` as an environment variable holding the
service-account JSON, rather than a file path.

The keystore is the exception, because Gradle needs an actual file and an
environment variable cannot be one. `SISKIN_KEYSTORE_PATH` points at wherever it
has been materialised, and the two call sites materialise it differently: CI
decodes the base64 secret to a temporary path for the life of the job, and
locally sops-nix can place it at a stable path or `sops exec-file` can produce a
temporary one per command. Neither leaves a decrypted keystore lying around
between builds, which is the property worth keeping. Note `.gitignore` already
carries `siskin-release-key.jks`, so a decrypted copy at the repo root would be
ignored rather than committed if one ever appears there by accident.

### sops is the source of truth; GitHub secrets are a derived copy

The keystore and its passwords live in a personal sops store, not in this
repository. GitHub Actions secrets hold a **copy** — keystore base64, the three
passwords, the service-account JSON — regenerated with `sops -d` and
`gh secret set` if they are ever lost.

Checking a sops-encrypted keystore into the repo was considered and rejected.
It would have been defensible — with Play App Signing the checked-in key is only
an *upload* key, and a compromised upload key is resettable through Play support
— but this repository is public, which means the ciphertext would be archived by
third parties permanently, and its security would rest entirely on age holding
up for as long as anyone cares. Keeping it out costs nothing: sops already makes
it available on every machine.

### A fresh upload key, and Google holds the app signing key

Nothing depends on any existing key, so a new keystore is generated. Play App
Signing is enabled with a **Google-generated app signing key**.

The asymmetry is the point. Losing a self-managed app signing key permanently
ends the app's ability to update — there is no recovery, and the app must be
republished under a new package name. Losing an upload key is a support ticket.
Google holding the app signing key converts the unrecoverable failure into a
recoverable one.

### Releases go up as drafts, for now

`publishBundle` uploads to the `internal` track with a **draft** release status,
requiring a manual confirmation in the Console before it reaches testers.

This is deliberately temporary. The reason is familiarity, not caution about the
artifact: the point of the first several releases is to watch what the pipeline
actually does at each step, and a draft leaves a checkpoint where that is
visible. **Once the process is boring, this flips to publishing directly to
internal** — which is the whole reason for building the pipeline, and a draft
that always gets confirmed is just a manual step wearing a costume.

There is no fixed trigger for the change beyond "it has stopped being
interesting."

### The inherited release workflows are deleted

`github_release.yml` and `github_prerelease.yml` go in the same change. They
build ABI-split APKs for a GitHub release channel that no longer has an
audience — the head unit is locked, so nobody can sideload — and they sign with
secrets that do not exist. Leaving them means a future tag fires a workflow that
fails for reasons unrelated to anything real.

## The one-time bootstrap

The pipeline cannot work until these are done, once, by hand. Recorded here
because it is precisely the sequence that would otherwise be rediscovered
painfully:

1. Generate the upload keystore; store it and its passwords in sops.
2. Build a signed bundle locally via `sops exec-env` + `./gradlew bundleRelease`.
3. **Upload that first bundle manually in the Play Console.** The Developer API
   will not accept uploads for an app that has never had one — the package must
   already exist and Play establishes the signing key from that first bundle.
   This is a hard constraint, not a convention.
4. Complete the Android Automotive OS form-factor opt-in.
5. Create a Google Cloud service account, enable the Google Play Android
   Developer API, and grant the account permissions in Play Console.
6. Populate the GitHub secrets from sops.

Only after all six does `git tag v…` do anything useful.

## Testing

The signing config's conditional behaviour is the part worth verifying, and it
is verifiable without credentials: with the environment unset, `bundleRelease`
must still produce an unsigned bundle rather than failing, and the existing test
and debug-build jobs must be untouched. The publishing path itself cannot be
meaningfully tested without talking to Play, so it is verified by use — which is
another argument for draft releases while it is new.

## What this does not buy

- **No protection from GPP being abandoned.** Maintenance mode means an AGP 10
  breakage depends on someone submitting a PR. The exposure is bounded: GPP is a
  *release-time* tool, not a build dependency, so it breaking never prevents
  building or shipping Siskin — it costs the convenience, and manual upload
  remains available.
- **No automation of the bootstrap.** Six manual steps stay manual, and several
  of them are one-time-only by Google's design.
- **No store listing management yet.** `publishListing` makes it possible, but
  #45 is a separate piece of work and putting listing copy in the repo is not
  part of this change.
