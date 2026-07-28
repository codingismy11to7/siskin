# Reduce Siskin to three browse tabs

**Date:** 2026-07-27
**Status:** Approved

## Context

Siskin is an Android Automotive OS media source. The launcher entry is gone, the
sign-in flow is car-native, and the Plex API client layer has landed. What has
*not* changed is the inherited feature surface: the app still carries a full phone
music client — podcasts, internet radio, folder browsing, genres, starred bundles,
generated mixes, offline downloads, a 671-line preference screen and a Chromecast
player — behind a browse tree whose four root tabs are user-configurable out of
fourteen candidate functions.

That surface is now the main cost of the Plex conversion. Every Subsonic-backed
feature that survives is a feature someone has to reimplement against Plex. The
cheapest reimplementation is the one that is never needed.

So the tab list is not a cosmetic decision. **Deciding what appears at the root
decides how much code exists**, because in this codebase the dependency arrows all
point the same way: UI → viewmodel → repository → subsonic/database/util. Cutting
the root cuts everything beneath it.

## Decision: three fixed tabs

The browse tree collapses to a fixed root, in this order:

```
root
├── Playlists  → playlist tracks
├── Artists    → artist albums → album tracks
└── Albums     → album tracks
```

No preference controls the set or the order. `MediaBrowserTree.buildTree()`
(`MediaBrowserTree.kt`, 708 lines) loses the 14-entry `allFunctions` list, the four
`indexGuard`ed tab slots, the Home/More catch-all that kept unselected features
reachable, and the "this list must be exactly the same as the one in
`aa_tab_titles`" coupling to `arrays.xml:340` and `global_preferences.xml:550-577`.

Removed nodes: Home/More, Last Played, Most Played, Recently Added, Made For You,
Quick/My/Discovery Mix, Starred (bundle, tracks, albums, artists), Tracks, Random,
Recent Tracks, Genres, Folders, Podcasts, Radio, Downloaded.

Surviving `ConstantsAA` ids: `ROOT_ID`, `PLAYLIST_ID`, `ARTISTS_ID`, `ALBUMS_ID`,
`ALBUM_ID`, `ARTIST_ID`, plus `JUMP_TO_ALBUMS_ID`, `JUMP_TO_ARTISTS_ID` and
`ARTISTS_BY_ALBUMS_ID` — the in-list navigation shortcuts `AutomotiveRepository`
injects at lines 239, 439 and 496, which still make sense with albums and artists
present. `JUMP_TO_STARRED_*` die with starred.

Search is untouched. The car path is `MediaLibraryServiceCallback.onSearch` /
`onGetSearchResult` → `MediaBrowserTree.search()` → `AutomotiveRepository.search()`,
independent of the phone's `SearchFragment`. The session's custom commands —
shuffle, repeat, heart — also survive, and the heart command keeps the Subsonic
`mediaannotation` star endpoints alive even though phone-side `FavoriteRepository`
does not.

## Consequent decisions

These follow from the tab decision rather than standing on their own, but each was
considered explicitly because each removes a capability.

### The phone UI goes entirely

`MainActivity` has been unreachable from a head unit's launcher since the
AAOS-only launcher change; only the `tempo://asset` deep link still opened it.
With the tree reduced, nothing else justifies 21,588 lines of `ui/` and 4,014 of
`viewmodel/`.

`CarSignInActivity` becomes the only screen.

### Offline playback is removed, not deferred

This is the one cut that costs a genuinely car-relevant capability, so the
reasoning matters.

Every way to *start* a download lives in the phone UI — `AlbumBottomSheetDialog`,
`SongBottomSheetDialog`, `StarredSyncDialog`, `PlaylistPageFragment` and friends.
The car side only ever reads: `AutomotiveRepository.getDownloadedSongs()` feeds the
`DOWNLOADED_ID` node, which `buildTree()` pins first in Home under the comment
"it is the only section that works with no connectivity."

Deleting the phone UI therefore turns the download machinery into a warehouse with
no loading dock. Keeping `DownloaderService`, `DownloaderManager`,
`DownloadRepository`, `DownloadDao`, `ExternalAudioReader`/`Writer` and
`ExternalDownloadMetadataStore` dormant would preserve Subsonic-shaped code, with
no entry point, that the Plex conversion would still have to port — dead weight
labelled "temporary."

Offline returns later as a car-native feature designed for a head unit. The
existing `PinnedPlaylistDao`/`PinnedPlaylist` pair suggests where that starts.

`DownloadUtil` survives in reduced form: it owns both the ExoPlayer streaming cache
(needed by `QueuePreloader` and `DynamicMediaSourceFactory`) and the download
manager. Only the second half goes.

### No settings surface

`global_preferences.xml` is 671 lines across 57 keys and dies with
`SettingsFragment`. Roughly a third of those keys die on their own — the four tab
slots, the podcast/radio/home view toggles, shuffle-genre, instant mix, download
storage. The rest are read by code that stays (replay gain mode and preamp,
playback speed and pitch, streaming cache size and location, precache count and
wifi-only, tracks-to-keep-in-queue, scrobbling, selected equalizer, custom command
buttons) and become frozen at their defaults, except runtime state such as repeat
and shuffle mode which the session writes.

A minimal parked-only preference screen inside `CarSignInActivity` — using the same
"deliberately not `distractionOptimized`" property the sign-in screen already
relies on — is the obvious follow-up, but not now. Plex's transcoding parameters
do not map onto the Subsonic ones these keys encode, so designing a settings screen
before the conversion means designing it against the wrong backend.

The cost is that credentials cannot be cleared from the car. Accepted: at the size
this app is heading for, reinstalling is a viable sign-out.

### Chromecast goes

`MediaService.kt:4-26` builds a `CastPlayer` and holds a `CastContext`, dragging in
`media3-cast` and the Play Services Cast framework. Casting from a head unit is not
a use case.

### The crash UI goes

`CrashActivity`, `CrashLogsFragment`, `CrashInfoFragment`, `CrashExportFragment`,
`crash_nav_drawer.xml`, `crash_bottom_nav_menu.xml` and the `customactivityoncrash`
dependency are removed. It is a tabbed, phone-shaped log viewer that nobody reads
on a head unit, and logcat is available during development.

It also removes the `CustomActivityOnCrash` configuration block in `App.java`
outright. That block currently names both classes explicitly —
`.restartActivity(MainActivity.class)` and `.errorActivity(CrashActivity.class)` —
the first with a comment explaining that the null default resolves through
`PackageManager.getLaunchIntentForPackage()`, which returns null now that no
activity declares `LAUNCHER`. Deleting both activities dissolves that constraint
rather than requiring the restart target to be repointed at `CarSignInActivity`.

## The keep-list

Kept in full: `plex/`, `equalizer/`, `glide/`, `provider/`, `broadcast/`. Kept with
edits: `App.java` and `service/` — the latter loses `DownloaderService`,
`DownloaderManager` and the Cast player, and has `MediaBrowserTree` rewritten.

The entries below matter because they contradict the natural instinct to delete a
package wholesale.

| Survivor | Why |
|---|---|
| `CarSignInActivity`, `LoginFragment` | the only screen; sign-in |
| `ServerAdapter` (`ui/adapter`), `ServerSignupDialog` (`ui/dialog`) | `LoginFragment` depends on both |
| `LoginViewModel`, `PlaybackViewModel` | the first for sign-in, the second imported by `MediaManager` |
| `LoginHost`, `ClickCallback`, `SystemCallback` (`interfaces/`) | `LoginFragment` implements against them |
| `ThemeHelper` (`helper/`) | `CarSignInActivity.onCreate` calls it |
| Glide (`glide/`, dependency) | `SyncBitmapLoader` and `AlbumArtContentProvider` render media-session artwork |
| `DownloadUtil` (reduced) | owns the streaming cache |
| `QueueRepository`, `SystemRepository`, `ChronologyRepository` | called from `service/` |

`LoginFragment` and its dependencies are Subsonic-shaped and multi-server. The Plex
QR/PIN rewrite replaces them; it does not happen here.

Room shrinks to `QueueDao`, `SessionMediaItemDao`, `ServerDao` and `ChronologyDao`.
Dropped: `DownloadDao`, `FavoriteDao`, `LyricsDao`, `RecentSearchDao`,
`InternetRadioStationDao`, `PlaylistDao`, `PlaylistSongDao`, `PinnedPlaylistDao`,
with a schema version bump and a destructive migration — acceptable because no
install holds data worth preserving.

`subsonic/api` keeps `system`, `browsing`, `albumsonglist`, `playlist`, `searching`,
`mediaretrieval` and `mediaannotation`; it drops `podcast`, `internetradio`,
`bookmarks`, `sharing`, `medialibraryscanning` and most of `open`. Of 77 files in
`subsonic/models`, roughly 30 go.

## Execution: top-down, compiler-driven

Deletions proceed in dependency order so the compiler proves what is dead. After
`ui/` and `viewmodel/` are gone, unresolved-symbol errors identify the next layer
instead of judgement calls about whether something is still reachable.

Each phase ends with a green `./gradlew assembleDebug` and is its own commit.

**Phase 1 — Three tabs.** Rewrite `MediaBrowserTree.buildTree()`; prune
`ConstantsAA`; drop `aa_tab_titles` and the four tab-slot preferences. Nothing else
moves. The car app is *correct* from here on, so every later phase is pure
dead-code removal — if browse breaks afterwards, a deletion went too far.

**Phase 2 — The phone UI.** Delete `MainActivity`, `nav_graph.xml`, all 48 fragment
files but `LoginFragment`, all 35 adapters but `ServerAdapter`, all 22 dialogs but
`ServerSignupDialog`, the crash UI, `navigation/` (536 lines, entirely phone
chrome), the seven files in `helper/recyclerview/`, `AssetLinkUtil` /
`AssetLinkNavigator`, `radiobrowser/`, and every viewmodel but `LoginViewModel` and
`PlaybackViewModel`. The manifest loses `MainActivity`, its `tempo://asset`
intent-filter, `CrashActivity` and the `:error_activity` process. Layouts and menus
go with their owners.

**Phase 3 — Repositories.** Now provably unreferenced: `MadeForYouBuilder` (697
lines), `SearchingRepository` (265), `DownloadRepository` (213), `InstantMixBuilder`
(179), `RadioRepository` (177), `FavoriteRepository`, `PodcastRepository`,
`SharingRepository`, `LyricsRepository`, `DirectoryRepository`, `ScanRepository`,
`GenreRepository`, `OpenRepository`. `AutomotiveRepository` shrinks hard — 1,526
lines serving fourteen node types, reduced to four.

**Phase 4 — Subsonic, util, database, preferences.** Drop the API groups and models
listed above. Delete `IndexUtil`, `RadioCoverArtDownloader`, `ExternalAudioReader`,
`ExternalAudioWriter`, `ExternalDownloadMetadataStore`, `UIUtil`, `TileSizeManager`,
`ClickablePreferenceCategory`. Prune `Preferences.kt` (1,021 lines) to keys still
read and delete `global_preferences.xml`. Apply the Room reduction and version bump.

**Phase 5 — Resources.** 123 layouts across `layout/`, `layout-land/`,
`layout-sw600dp/` and `layout-sw600dp-land/` down to the sign-in handful; 23 menus
to none; `arrays.xml` and the string catalogue across seven locales; the `drawable/`
set (604K). `font/` is 2.6M — the largest single thing in `res/` — and is audited
here.

**Phase 6 — Dependencies.** Drop `media3-cast` (and the Play Services Cast framework
behind it), `media3-ui`, `navigation-fragment-ktx`, `navigation-ui-ktx`,
`swiperefreshlayout`,
`coordinatorlayout`, `sdp-android`, `shimmer`, `customactivityoncrash`. Kept:
`recyclerview` (`ServerAdapter` is a `RecyclerView.Adapter`), Glide, Room, Retrofit,
and Media3 session/common/exoplayer/hls.

`media3-ui` is the reason this phase comes last. No Java or Kotlin source references
it; its only consumers are the four `inner_fragment_player_controller*.xml` layouts,
so it cannot be dropped until phase 5 removes them. Anything that looks unused from
the source alone should be checked against `res/` the same way before removal.

`androidx.preference` is used only for
`PreferenceManager.getDefaultSharedPreferences` at `App.java:55,61`; replacing that
with a plain `getSharedPreferences` call retires it, and is a discrete step rather
than a freebie.

### Rejected execution orders

- **Feature-by-feature vertical slices** — remove podcasts entirely (node,
  repository, fragments, models, strings), then radio, then folders, and so on.
  Tidier on paper, but the features are entangled at exactly the top:
  `SongBottomSheetDialog` and `SongHorizontalAdapter` serve songs from podcasts,
  radio, folders and starred alike. The same shared UI gets touched six times and
  no deletion is clean until the last slice. That shape suits adding features, not
  removing them.
- **Keep-list inversion** — enumerate the survivors, delete everything else in one
  sweep, repair what breaks. Reaches the same endpoint in one commit, and the ratio
  makes it tempting, but it produces a single unreviewable diff over precisely the
  code that will be read closely during the Plex conversion. Its one good idea —
  write the keep-list first — is adopted above.

## Verification

`./gradlew assembleDebug` after every phase.

`./gradlew test` after every phase. Of the 12 test files, `AutomotiveRepositoryTest`
and `SystemRepositoryTest` shrink with their subjects in phase 3. The other ten —
`BaseSessionCallbackTest`, `CredentialGateTest`, `LiveDataUtilsTest` and the seven
Plex tests — stay green untouched. **A red Plex test means a deletion reached too
far**, and is the sharpest signal available that a phase overshot.

Emulator smoke test after phases 1, 2 and 6: browse all three tabs, drill Artists →
albums → tracks, play a track, run a search, and confirm the sign-in resolution
still appears when credentials are cleared.

Record APK size before phase 1 and after phase 6. There is no build in
`app/build/outputs` at the time of writing, so the baseline is taken first.

## Scale

`app/src/main/java` is 49,080 lines today, of which `ui/` is 21,588 and `viewmodel/`
4,014; much of `repository/`, `subsonic/` and `util/` exists only to serve them. The
result should land near a fifth of the current size, but that number is an outcome
of the sweep, not a target to hit. The measure that matters is how little remains to
be reimplemented against Plex.

## Not in scope

The Plex conversion itself. The QR/PIN sign-in rewrite. Offline playback as a
car-native feature. Any settings surface. Renaming the `com.cappielloantonio.tempo`
package.
