# Open-source credits come from a committed SBOM

**Date:** 2026-10-25
**Status:** Approved

## Context

`siskinapp.com/credits` (#244) credits the Tempus ← Tempo lineage and states
the licence, but not the hundred-odd libraries the APK actually ships. #243
asks for those, generated from the build rather than maintained by hand, and
reachable from the car through the debug screen's QR code to `/credits`.

The constraint that shapes everything here is the site's build. Workers Builds
runs `scripts/build-site.sh` on an image with no JDK, no Android SDK and no
`jq`, so the site cannot run Gradle, and the dependency list only exists where
Gradle does.

## Decisions

### CycloneDX, from the release runtime classpath

The two industry SBOM formats are CycloneDX and SPDX. CycloneDX has an official
Gradle plugin, `org.cyclonedx.bom`, which supports the configuration cache this
build enables.

It is restricted to `releaseRuntimeClasspath`. That is what ships; the test,
lint and annotation-processor classpaths are not part of the app and do not
belong in its credits.

Google's `oss-licenses-plugin` was the other candidate. It generates resources
*inside* the APK for an in-app licences activity — a phone pattern, with no
place in an app whose UI is the car's — and its output is not an SBOM anyone
else can read.

### The SBOM is committed, and CI checks it is current

`docs/web/sbom.cdx.json` is checked in. A step in CI's `lint` job regenerates
it and fails when `git status --porcelain` reports a difference, the same
pattern as the `app/schemas` check. A dependency change therefore cannot merge
without its regenerated SBOM, and the page tracks `main`.

The plugin's output is not committed as-is. Its serial number is random per run
(it can be switched off) and `metadata.timestamp` is always the current time
(it cannot), and either would make the check fail on every build. The task that
renders the table also writes the committed copy, with both removed.

Both files live under `docs/web/`, which the Cloudflare project's build watch
paths already cover, so a regenerated SBOM redeploys the site with no dashboard
change.

Rejected:

- **CI deploys the site.** A workflow running Gradle and then `wrangler deploy`
  would need nothing committed, but it undoes
  `2026-09-07-site-deploy-design.md`: repository secrets come back, and the
  preview URLs move off Workers Builds.
- **Regenerate at release cut.** The page would describe exactly what is on
  Play, but `scripts/cut-release.sh` would need Gradle and the SDK, which it
  avoids so that it runs anywhere, aarch64 included. Between releases the gap is
  a few library bumps on a page about credit, which does not justify that.

### Gradle renders the table; the site splices it

The same Gradle task writes `docs/web/credits-oss.html`, an HTML table fragment,
also committed and checked. `credits.html` carries a marker line where the
section goes, and `build-site.sh` replaces it with the fragment. The site build
stays a copy plus a splice, with no parser to fetch.

Rejected:

- **Render in the browser** from the published JSON. One committed file instead
  of two, but the section is empty without JavaScript, and nothing else on the
  site needs it.
- **Parse in `build-site.sh`.** Means fetching `jq` the way pandoc is fetched,
  or relying on whatever Node or Python the Cloudflare image happens to carry.

### One row per artifact, collapsed by default

The section is a `<details>`, closed by default, under a heading of its own.
The table has one row per artifact — name, version, licence, link — sorted by
group then artifact, which is what the SBOM says without interpretation.
Grouping by Maven group was rejected because versions and licences would have
to be merged across artifacts that do not always share them.

A component whose POM declares no licence says so in its row rather than being
dropped: an omission would read as a claim that the list is complete.

The raw SBOM is published at `/sbom.cdx.json` and linked beneath the table.
