# Reset the Room schema to version 1

**Date:** 2026-07-28
**Status:** Approved

Supersedes the Room sections of
`docs/decisions/2026-07-27-three-tab-rip-out-design.md`,
`docs/decisions/2026-07-28-plex-sign-in-design.md` and
`docs/decisions/2026-07-28-plex-browse-playback-design.md` — the three changes
that authored versions 21 through 25. Those remain as written; they record
decisions that were correct against the schema history that existed at the
time, and the three-tab spec in particular still describes a version-21 bump
and DAOs (`ServerDao`, `ChronologyDao`) that no longer exist.

## Context

`AppDatabase` declares version 25, fourteen `@AutoMigration` entries, four
`AutoMigrationSpec` classes, and exports twenty-five schema files totalling
828K. The database it describes is two tables:

| Table | Purpose |
|---|---|
| `queue` | the persisted play queue, restored on service start |
| `session_media_item` | browse-node cache, so tapping one track rebuilds the surrounding list |

Nine queries in total, no joins, no foreign keys.

### Why the history is unreachable rather than merely unused

Versions 1 through 20 are upstream tempo's. They describe upgrade paths for
`com.eddyizm.tempus` and `com.eddyizm.degoogled.tempus`; the fork's
`applicationId` is `io.github.codingismy11to7.siskin`. **Android treats a
different application id as a different app**, so a tempo install cannot upgrade
into Siskin under any circumstance. There is no code path by which those
migrations could execute — not "none in practice", none at all.

Versions 21 through 25 are Siskin's own, added by the three-tab sweep, the
sign-in change and the browse conversion. Siskin has never released: the
`v4.9.x` tags in this repository are tempo's, inherited by the fork, and the
GitHub release list is empty. Those five have therefore only ever run on
development devices.

So the entire apparatus describes upgrades that either cannot happen or have
only happened on machines whose data nobody wants to keep.

## Decision

Version 1. No auto-migrations, no specs, one exported schema file describing
the two tables that exist.

Version 1 is not a renumbering of real history. It is the first version number
this application has ever had that means anything.

**Real migrations are written from here on.** Not "once Siskin ships" — that is
a trigger nobody would notice passing, and it is how the five Siskin-authored
migrations came to be written against an app with no installs. From version 1,
a schema change gets a version bump and a migration, the same as it would for a
released app.

### `fallbackToDestructiveMigration()` is removed

This is the load-bearing part of the decision, and the reason it is not merely
a tidy-up.

Writing migrations only works if forgetting one is noticeable. With the
fallback in place, a missed migration silently drops the database and presents
as the saved queue having emptied itself — a symptom that looks like a playback
bug and is nowhere near its cause. Without it, Room throws when it opens a
database whose version has no path, which happens on the developer's own device
the first time they run the change.

The failure is loud, immediate, and local. That is the trade.

**One case that trade also covers, which is worth revisiting before release.**
The destructive fallback did not only rebuild after a missing migration — it
also rebuilt after a *corrupt* database file. Without it, corruption is a crash
too, and on a head unit the recovery story becomes "uninstall the app", which is
not something a car owner can reasonably be asked to do.

Correct for a pre-release app whose two tables hold a play queue and a browse
cache, both of which are cheap to lose and cheap to rebuild. Genuinely not
correct for a shipped one, where the right answer is probably to catch the open
failure and rebuild deliberately rather than to reinstate a blanket fallback.
Recorded here so the decision gets a second look at ship time rather than being
inherited silently, which is exactly how the migration history this spec deletes
came to exist.

### The database file is renamed to `siskin`

`DB_NAME` is `"tempo_db"`, inherited from upstream along with everything else in
this section. It becomes `"siskin"`.

This is not cosmetic. **A different filename is a different database**, so
version 1 opens a file that does not exist yet and Room creates it fresh. The
whole transition problem — an existing version-25 database with no path down to
version 1 — simply does not arise, and no uninstall is required.

The old `tempo_db` file is left orphaned on any device that has one. Deleting it
would mean a one-time `deleteDatabase("tempo_db")` call that then lives in the
codebase forever to clean up a file that exists on one emulator. Not worth it
for an app that has never released.

### No downgrade fallback

`fallbackToDestructiveMigrationOnDowngrade()` is deliberately not added.

The rename means this no longer bites on *this* change. It still governs what
happens later: checking out a branch whose schema version is lower than the
database on the device will crash on open rather than rebuild, and because the
database is reached through the media service that presents as a service restart
loop rather than a dialog. The fix is to uninstall.

Left out for the same reason the destructive fallback is: a schema surprise
should stop the developer who caused it, on their own device, rather than
resolve itself quietly. Recorded because the symptom does not name its cause.

## Consequences

`AppDatabase.java` loses its fourteen `@AutoMigration` entries, its four
`AutoMigrationSpec` classes, and the thirteen-line comment block explaining a
version-24 identity-hash trap. That comment goes with the machinery it
describes — it is a code comment, not a record, and leaving it would explain a
hazard that no longer exists.

The exported schema directory is deleted wholesale and regenerated by a build
rather than hand-edited, so `1.json` is derived from the entities rather than
transcribed.

`exportSchema` stays enabled. With real migrations coming it stops being
documentation and becomes an input: the exported JSON is what Room diffs
against to generate the next auto-migration.

## Verification

`./gradlew assembleDebug test`. The Room tests do not depend on the removed
fallback — `SessionMediaItemDaoTest` builds an in-memory database, and
`SessionMediaItemRepositoryTest` goes through the singleton against a fresh
Robolectric database at the current version.

Then, on the emulator, **installing over the existing build rather than
uninstalling first**: play a track, force-stop, and confirm the queue is
restored.

Installing over the top is the point of the check, not a shortcut. The rename
means the app should find no `siskin` database, create one at version 1, and work —
while the old `tempo_db` sits beside it untouched. Uninstalling first would
prove a clean install works and say nothing about the transition, which is the
case every existing device will actually take.

That check also earns its place because the fallback that used to rebuild a
broken database silently is gone: this is the first time a schema problem
surfaces as a failure rather than as a quietly empty queue.

Confirm one `1.json` exists afterwards and that it names both tables.

## Not in scope

### Converting `AppDatabase` to Kotlin

The collapse takes the file from 121 lines to 80, which makes converting
it look like a rename. It is not, and the reason is worth recording because it
is invisible until the build breaks.

This project processes annotations with `annotationProcessor`, which runs inside
**javac** and therefore only sees `.java` sources. The current mix works because
of an asymmetry:

| Annotation | Language | Why it works |
|---|---|---|
| `@Database`, `@Dao` | Java | javac compiles them, so Room's processor runs over them |
| `@Entity` | Kotlin | Room reads these as bytecode off the classpath |

Room can read entity metadata from compiled classes, but the `@Database` root
has to be among the sources javac actually compiles. Move it to Kotlin and the
processor never sees it, so Room generates no implementation — the failure is a
missing generated class, not a compile error in the file that was changed.

Converting therefore requires adopting KSP (or kapt) for Room, with Glide
remaining on `annotationProcessor`. That is a build-system change, and it is
kept out of this spec because the two changes fail differently and are verified
differently: this one is proven by the queue surviving a restart, a KSP move by
every processor still generating what it did before. Combined, a broken build
would not say which change caused it.

### Replacing Room

It was raised and examined: nothing stored here is relational,
and SQLDelight or a serialised file would both work. But Room's compile-time SQL
validation is what it is earning here — a column that does not exist is a build
failure rather than a runtime crash, across nine queries including a non-trivial
retention prune. Room is also actively maintained AndroidX, not abandoned. The
transitive `kotlin-metadata-jvm` skew from `room-compiler` is resolved by a
`resolutionStrategy.force` on the Kotlin-bump branch and is a build-time
annoyance, not a reason to change persistence layers.
