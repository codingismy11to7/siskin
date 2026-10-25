# The Play listing's text lives in the repository and publishes on promotion

**Date:** 2026-10-25
**Status:** Approved
**Issue:** #221

Builds on `docs/decisions/2026-10-21-release-automation-design.md`.

## Context

The store listing's title, short description and full description existed only
in the Play Console. Nothing recorded what they said or when they changed, and
a copy change made elsewhere (the website, in #213) had to be retyped there by
hand. Gradle Play Publisher was already applied for the bundle and the release
note, so managing the listing too meant adding files and one task.

## Decision

The three text fields live in `app/src/main/play/listings/en-US/` and
`promote.yml` publishes them with `publishListing`, as a step after
`promoteArtifact`. They were seeded with `bootstrapListing`, so the history
starts from what the store showed. CI checks each against Play's cap — 30, 80
and 4000 characters, counted after trimming, as GPP sends them.

### Published on promotion, not on the cut

A listing is app-wide, not per track. `release.yml` publishes every cut to
`automotive:internal`, so publishing the listing there would describe the new
version to everyone while only internal testers could install it. Promotion is
the moment the build reaches everyone, so it is the moment the text should
change. The step runs after the promotion so that a failure leaves the listing
behind the build rather than ahead of it.

A consequence: **an edit made in the Console is overwritten at the next
promotion.** The files are the source now.

### Text only

- **The publishing API has no automotive image type.** `AppImageType` lists
  phone, seven-inch, ten-inch, TV and Wear screenshots, the icon, the feature
  graphic and the TV banner. The eight automotive screenshots — the ones the
  listing is for — cannot be uploaded through it, so automating the rest would
  split one manual job into a manual half and an automatic half.
- **The icon and feature graphic need an AI-generated declaration** on every
  upload, and the API has no field for it.
- **GPP replaces the Console's set for every image type it finds a directory
  for**, and leaves alone any it does not. With no `graphics/` directory,
  `publishListing` touches no images.

The graphics stay in `docs/play/`, uploaded by hand as before.

The app-wide details `bootstrapListing` also downloads (contact email, default
language) were discarded for the same reason: absent files leave the Console's
values alone, and nothing here needs to change them.

## Alternatives considered

- **Publish on merge to `main`.** Rejected for the same reason as the cut: the
  listing would describe unreleased work.
- **Publish from `release.yml` alongside `publishBundle`.** Rejected above.
- **Leave the listing in the Console.** The status quo, and what produced a
  description still written for 0.99 after 1.1 shipped.

## Bootstrapping again

`bootstrapListing` resets `app/src/main/play/`. It overwrites `default.txt`
and adds per-track release notes, graphics and app details. The per-track notes
are the dangerous part: GPP prefers `<track>.txt` over `default.txt`, so a
committed `automotive:internal.txt` would silently replace every future cut's
drafted note. Keep the three listing files and `default.txt` and delete the
rest. It also fails at the end on the in-app products endpoint, which Google
has retired. Siskin has no products, and everything before it has already
downloaded.
