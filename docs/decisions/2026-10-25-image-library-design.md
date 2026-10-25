# Coil 3 replaces Glide

**Date:** 2026-10-25
**Status:** Approved

## Context

Glide arrived with the fork: it is in tempo's first commit, and
`CustomGlideModule` with it. Nothing in Siskin chose it. #129 asked whether it
should stay, prompted by #116 and #130.

Four call sites use it, and only one the way Glide is designed for:

- `PlexSignInFragment` loads the QR code into an `ImageView`. This is the one
  idiomatic use, and it was added in #13 because Glide was already there.
- `AlbumArtContentProvider.openAlbumArt` wants a cover's bytes as a file, to
  pipe to the car.
- `CompositeArt.loadCover` wants a decoded bitmap to draw into a canvas.
- `SyncBitmapLoader` wants a decoded bitmap to hand media3 and keep in its own
  `LruCache`.

The last three have no view and no lifecycle. They use `submit().get()`, and a
`FutureTarget` stays registered with the application-scoped `RequestManager`,
holding its resource, until someone clears it. Each site needs a different rule
for when that is safe, because clearing a bitmap target returns the bitmap to
Glide's pool. #116 fixed two of the three. The third, #130, has no correct
`finally` at all: its bitmaps outlive the call in the `LruCache` and in media3,
and prewarming leaks one target per queued track.

Siskin draws no artwork of its own — the car renders every cover from
`artworkUri` — so there is no wider idiomatic use to adopt Glide into. The
question was keep or replace.

## Decisions

### Replace Glide with Coil 3

Coil returns a bitmap as an ordinary object with no target and no pool behind
it. Ownership is the caller's from the moment the call returns, and the garbage
collector releases it. That removes the whole class of bug #116 and #130
belong to rather than fixing instances of it: #130 stops existing, because a
bitmap held by an `LruCache` and by media3 at once needs nothing more.

Measured on `assembleRelease`, from `main` at `a72ab967`:

| Library | APK | `classes.dex` |
|---|---|---|
| Glide 4.16.0 | 10,590,553 | 3,669,496 |
| Glide 5.0.9 | 10,590,553 | 3,680,020 |
| Coil 3.6.3 | 10,525,124 | 3,603,940 |

Coil is 64 KB smaller, and drops the `annotationProcessor` Glide needs for
`@GlideModule`. A throwaway port of all four sites was run on the AAOS
emulator: decade composites rebuilt from a cleared cache, single covers in a
browse list, and Now Playing artwork all rendered, with nothing in the log.

Rejected:

- **Keep Glide 4.16.** The target lifetime problem stays, and #130 needs a
  real fix that depends on settling who owns a bitmap shared with media3.
- **Upgrade to Glide 5.** Its `RequestFutureTarget` documentation still
  prescribes `cancel()` in a `finally` to free resources. The model that caused
  #116 is unchanged, and it is 10 KB larger.
- **No library on the three non-view paths.** OkHttp and `BitmapFactory` give
  the same ownership as Coil, but Siskin would then own a disk cache,
  downsampling and request de-duplication, and still need something for the QR
  code. Not built or measured; Coil already provides each of those.

### Coil's request de-duplication is turned on

`CompositeBuildLocks` relies on the image library collapsing identical
in-flight requests, so that concurrent opens of one album cover from the
car's provider pool cost one fetch. Glide did this by default. Coil does it
only with `DeDupeConcurrentRequestStrategy` passed to
`OkHttpNetworkFetcherFactory`, and that class is `@ExperimentalCoilApi`. The
opt-in is accepted: the alternative is reimplementing it beside the composite
lock, and an experimental API changing shape costs a compile error, not a
silent regression.

### The provider fetches a file without decoding

`openAlbumArt` pipes bytes, so decoding a bitmap it never draws would be waste.
It runs Coil's network fetcher directly (`components.map`, then `newFetcher`,
then `fetch()`), whose result is backed by the disk cache file. Data-saving
mode, which was `onlyRetrieveFromCache(true)`, becomes
`networkCachePolicy(DISABLED)`.

### `IPv6StringLoader` is deleted, not ported

It was upstream's fix for IPv6 literal server addresses. Plex advertises only
`plex.direct` hostnames (see `ServerAddressResolver`), so it never fires here.
It only ever covered `String` models anyway: the provider loads a `Uri`, which
went through Glide's own fetcher. Its 2.5 s connect and read timeouts are the
one thing it set that matters, and the port chooses timeouts on purpose rather
than inheriting them.

### Covers share the API's connections, with short timeouts

Cover fetches derive from `PlexRetrofitFactory`'s shared `OkHttpClient`, so
they reuse the TLS connections the API calls already
hold, with connect and read timeouts cut to 2.5 s. A cover is optional, and a
provider thread waiting on a dead server is one every other cover queues
behind; failing quickly gives the car its placeholder.

### Details of the port

These came out of the spike and are not optional:

- **Failure responses are not cached.** Coil's default cache strategy stores
  404 and a few other error codes. A cover added on the server after a 404
  would stay missing until evicted, where Glide simply retried.
  `SuccessOnlyCacheStrategy` stores 2xx only.
- **Cache-only does not mean offline.** Coil's `networkCachePolicy(DISABLED)`
  still issues the request, marked `Cache-Control: only-if-cached`, and relies
  on OkHttp answering it with a 504 when nothing is cached. A test pins that no
  request reaches the server.
- **Decoded covers stay out of Coil's memory cache.** Every caller keeps its
  own — `SyncBitmapLoader`'s `LruCache`, the composite files — so a memory
  cache would only hold a second copy.
- **Software bitmaps.** `CompositeArt` draws covers into a software canvas,
  which a hardware bitmap cannot be drawn into. `RGB_565` carries over from the
  Glide module's default.
- **The composite crops at draw time.** Coil has no `centerCrop`; filling to
  the cell's edge leaves an oblong cover oblong, so `CompositeGrid.centerSquare`
  supplies the source rectangle rather than letting the draw squash it.
- **`coil-core`, not `coil`.** The latter adds a process-wide default
  `ImageLoader` with its own disk cache, which nothing here should reach by
  accident.
- **Glide's disk cache directory is deleted once.** It lives at
  `cacheDir/cache` and would otherwise sit there at its full size.

The disk cache key still includes `X-Plex-Token`, as it did under Glide. Coil
can key on the URL without it, which would stop a Home user switch from
re-fetching every cover, but that is a change of its own.

`SyncBitmapLoader` still loads covers through our own provider by `content://`
URI, which then fetches them through the image library — a round trip the
library change does not remove. It is left alone here.
