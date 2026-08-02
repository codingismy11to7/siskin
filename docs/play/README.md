# Play listing assets

Everything Google Play shows on the store listing, other than the text. These
are **source**, not build output — there is no script that regenerates them, and
recreating one means booting an emulator, signing in to Plex and driving the app
back into the state it captures.

They lived in `build/play/` until 2026-08-02, which was a mistake: that whole
tree is gitignored and `./gradlew clean` would have deleted the lot.

## What is here

Two sets, one per head-unit shape, both captured at their emulator's native
resolution.

| File | Size |
|---|---|
| `screenshots/01-now-playing.png` | 1024×768 |
| `screenshots/02-album.png` | 1024×768 |
| `screenshots/03-artists.png` | 1024×768 |
| `screenshots/04-albums.png` | 1024×768 |
| `screenshots/05-now-playing-portrait.png` | 800×1280 |
| `screenshots/06-album-portrait.png` | 800×1280 |
| `screenshots/07-artists-portrait.png` | 800×1280 |
| `screenshots/08-albums-portrait.png` | 800×1280 |
| `feature-graphic.png` | 1024×500 |
| `ic_launcher_512.png` | 512×512 |

**The two screenshot sizes are Play's, and they are exact.** The Console states:

> Upload up to 8 Android Automotive OS screenshots. You must upload at least 2
> portrait and 2 landscape screenshots. Screenshots must be PNG or JPEG, up to
> 8 MB each, 800 px by 1,280 px for portrait, and 1,024 px by 768 px for
> landscape.

Four of each is the maximum useful spread under the cap of eight. Higher
resolution is not better here — it is rejected. `automotive_1024p_landscape`
and `automotive_portrait` are the stock device profiles that render at exactly
these sizes, which is the only reason those two are the emulator variants in
`flake.nix`; capturing anywhere else would mean resizing, and a resized
screenshot misrepresents what the car draws.

Play also requires the phone, 7" and 10" tablet sections to be filled even for
an app that hard-requires `android.hardware.type.automotive` and so cannot
install on any of them. Those sections take the general rules rather than the
automotive ones, and accept both of these sizes, so the same files serve
everywhere.

## Uploading

**The icon and the feature graphic are AI-generated, and Play requires that to
be declared.** The Console asks per asset, so they have to be marked as
AI-created every time they are uploaded. The screenshots are real captures and
are not marked.

The listing shows the screenshots in the order they are arranged in the Console,
which is worth checking against the numbering here — 01 through 08 is the
intended sequence.

## What each pair shows

Both sets cover the same four states:

1. **Now playing** — expanded player: art, title, artist, album, elapsed/total,
   seek bar, transport row. The leftmost control is the car's own star rating,
   drawn by `com.android.car.media` because `PlexMediaMapper` publishes a
   `HeartRating` — it is not ours.
2. **Album** — a track list, with the mini player docked at the bottom.
3. **Artists** — the Artists tab, four-across grid of artist art.
4. **Albums** — the Albums tab, four-across grid of cover art.

Two, three and four all show the mini player, which is deliberate: it is what
demonstrates that browsing continues while something plays.

**The two shapes are not crops of each other.** In portrait the car puts the app
name in a title row and the four tabs on a second row beneath it, where landscape
runs the logo and tabs inline; and the album list shows twelve tracks against
landscape's three. That difference is why both sets are captured rather than one
being resized — quite apart from Play rejecting a resized one on dimensions.

At 1024×768 the grids fit three across rather than four, and the mini player
covers most of the second row. That is what the profile gives; it is not a
capture mistake.

## Recapturing

The head unit is locked, so screenshots come from the emulator. Momentum cleared
the automotive review with emulator captures, so this is an accepted source
rather than a workaround — see
`docs/decisions/2026-07-31-play-store-release-design.md`.

    siskin-avd landscape            # or: portrait
    siskin-emulator landscape -no-window -no-audio

`siskin-avd` is idempotent. See CLAUDE.md for the variants and the device
profiles behind them. Then install and reach the app the way the car does —
there is no launcher activity, so `am start` on the package will not work:

    ANDROID_SERIAL=emulator-5554 ./gradlew installDebug

    adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
      -e android.car.intent.extra.MEDIA_COMPONENT \
      "us.codingismy11to7.siskin.debug/com.cappielloantonio.tempo.service.MediaService" \
      --user 10

Sign in — a fresh AVD has no session, and the PIN has to be approved on plex.tv.
Start a track so the mini player is present, then for each state:

    adb -s emulator-5554 exec-out screencap -p > docs/play/screenshots/<name>.png

**Use `screencap`, not the Android MCP's screenshot tool.** The MCP tool
downscales to 1280px wide — against a 1408×792 display it silently returns
1280×720. It is for looking at the UI, not for producing a deliverable, and
Play checks these dimensions exactly.

**Watch for a stray focus highlight.** A tab that was tapped on the way to the
state being captured can keep a grey rounded highlight behind it, which on the
listing reads as a stuck control. Land on the target screen, then look at the
tab row before snapping.

**Take a whole set in one sitting.** The status bar clock is visible in every
shot, and a set captured across two sittings shows different times, which reads
as sloppy where they sit side by side on the listing.

## Keeping them honest

**Captured against app commit `c3ab7500`, 2026-08-02.** Keep that line current
when a set is replaced; everything below depends on it.

Play expects screenshots to represent the app as it actually is, and a shot
outliving the UI it shows is the failure mode to watch for. It has happened:
the set these replaced showed a heart button that `a3389645` deleted, and was
missing the settings gear that `f73dc461` added.

**This is not a per-PR obligation.** Recapturing on every commit that
invalidates a shot would be wrong twice over:

- PNGs do not delta-compress, so each replacement adds its full weight to the
  history permanently.
- Nothing about the upload is automated. Replacing one screenshot means deleting
  and re-uploading a whole set by hand in the Play Console, so it is not a step
  that can ride along with a code change even if you wanted it to.

These are a snapshot of a **published listing**, not a mirror of `main`. The UI
can change ten times between releases and only its state at upload matters. So a
set is refreshed when the listing is next updated — and staleness is made cheap
to *detect* rather than expensive to *prevent*.

Before uploading, check whether anything visible has moved since the capture
commit above:

    git log --oneline c3ab7500..HEAD -- \
      app/src/main/java/com/cappielloantonio/tempo/service/ \
      app/src/main/java/com/cappielloantonio/tempo/plex/PlexMediaMapper.kt \
      app/src/main/res/values/strings.xml

Empty means the sets still represent the app. Non-empty is a prompt to look, not
a verdict — those paths cover the browse tree, the metadata behind every row,
the player's controls and the tab titles, which is most of the visible surface,
but plenty of commits touching them change nothing you can see. Both of the
failures above were caught by exactly this command.

If a set does need redoing, redo all four of it — see the note about the clock.

If this ever becomes frequent enough to be annoying, the triplet play-publisher
plugin already in `app/build.gradle` can manage listing graphics from
`src/main/play/listings/<locale>/graphics/`, which would make the upload part
automatic. That is not set up today, and at the current rate of listing changes
it would not pay for itself.
