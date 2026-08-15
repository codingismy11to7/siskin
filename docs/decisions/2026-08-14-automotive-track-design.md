# Publishing goes to the automotive track, and the Console step was doing real work

**Date:** 2026-08-14
**Status:** Approved

Finishes what `docs/decisions/2026-07-31-play-release-pipeline-design.md` began
and `#101` changed. That document stands as written — its reasoning was sound
given what was known — and this one records what the change it anticipated
actually revealed.

## Context

`v0.99.4` was cut deliberately early, to run the release process once before the
in-flight Discover work rode on it. It built, signed, uploaded, and then failed
to commit the edit:

> Artifact with version code 6 requires features that are not allowed on this
> track: android.hardware.type.automotive.

Tests passed, the tag matched `versionName`, the keystore decoded, R8 ran, the
bundle uploaded. Only the last call failed.

## What was actually wrong

**Play gives each alternative form factor its own track family, and the plain
tracks are the phone ones.** The API names them `<prefix>:<track>` —
`automotive:internal`, `wear:production`. This app's manifest requires
`android.hardware.type.automotive` as a hard requirement, so its artifact was
never eligible for the `internal` it was being published to.

Asking the Developer API which tracks the app actually has answered it outright,
where reasoning from the error had produced a wrong guess:

    internal              [draft]      versionCode 5
    automotive:internal   [completed]  versionCode 5

Both hold 0.99.3. The draft is what Gradle Play Publisher uploaded. The
completed one is the build that was on the head unit.

`app/build.gradle` now sets `track.set('automotive:internal')`. GPP 4.0.0 carries
the colon-in-track-name workaround (Triple-T#1122, merged well before 4.0.0), so
no plugin change was needed, and it resolves release notes as `<track>.txt`
falling back to `default.txt` — which `#101` had already chosen, so the notes
needed no rename.

## Why it hid for four releases

**A DRAFT release is never live on a track, so Play never validates it against
one.** Every release since 0.99.0 uploaded to `internal` as a draft, which Play
accepts, and the check that would have caught the wrong track never ran.

The manual step is the other half. The 2026-07-31 document described it as a
confirmation and set its own trigger for removal:

> a draft that always gets confirmed is just a manual step wearing a costume

It was not a costume. Confirming the draft in the Console is what moved the
release onto the automotive track, and it had been doing so, by hand, on every
release. Publishing to `internal` has never once put a build where the car could
see it. `#101` removed the step on the reasoning that it did nothing, and the
first release to skip it is the first one Play had to validate.

**The generalisation worth keeping: a manual step that always produces the same
outcome is not proof that it does nothing.** It is proof that it always
succeeds. What it accomplishes is invisible precisely because it never fails,
and deleting it is what reveals the difference. The 2026-07-31 reasoning was
right that the *checkpoint* value had been exhausted; it did not consider that
the step might carry a second job nobody had written down.

## What the bootstrap list was missing

That document's one-time bootstrap ends with six steps, of which the fourth is
"Complete the Android Automotive OS form-factor opt-in." That step was done and
is not the gap. The gap is that opting in *creates* the automotive track family
and nothing then points the pipeline at it — the track name is a seventh step
that was never written, because for as long as releases went up as drafts and
were finished by hand, nothing needed it.

Read as an operational checklist today, that list still produces a pipeline that
uploads where the car cannot see it.

## Verifying it, and a documentation landmine

The Console does not show API track names, so the way to check is to ask the API:
open an edit, list tracks, delete the edit. Nothing is published by that
sequence, and it answers both "which tracks exist" and "what is on them".

**Google's own documentation disagrees with reality here, and cost an hour.**
`developers.google.com/android-publisher/tracks` maps Internal Testing to a
default track name of `qa`, which would make the automotive internal track
`automotive:qa`. This app's actual track list says `automotive:internal`, and
GPP's own PR describing the feature uses `tv:internal` as its example. Trust the
`edits.tracks.list` output for the app in hand over the naming table.

## What this does not buy

- **No protection from the same class of defect on a future track change.**
  Promoting to `automotive:production` will need the same verification; nothing
  checks the configured track against the artifact's required features before a
  tag is pushed.
- **No recovery of the versionCodes spent.** None were: the failed edit was
  discarded rather than committed, so versionCode 6 stayed free and `v0.99.4`
  was re-pointed rather than becoming an abandoned number. That is luck about
  where the failure landed — an edit that fails after commit would not be
  recoverable this way.
- **Nothing about the phone track's stale draft.** `internal` still holds a
  versionCode 5 draft that was never going anywhere. It is inert, and left
  alone deliberately rather than unnoticed.
