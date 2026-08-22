# The ffmpeg decoder is Jellyfin's Maven Central artifact

`libs/lib-decoder-ffmpeg-release.aar` was 898 KB of prebuilt native code
committed to the repository, wired in as `implementation files(...)`. It is now
`org.jellyfin.media3:media3-ffmpeg-decoder`, a coordinate in the version
catalog. See #142 for the survey that preceded this.

Nothing else changed. Both aars carry the same
`androidx.media3.decoder.ffmpeg` classes — identical `classes.jar`, identical
`proguard.txt` — so `DefaultRenderersFactory` finds `FfmpegAudioRenderer` by
the same reflective lookup it always did. No source file names the class, and
none needed to.

## Why the decoder is not an androidx coordinate

Google publishes `androidx.media3:media3-decoder` at every version from
`1.0.0-alpha01` onward and no ffmpeg artifact at any of them. The module ships
as source with a README telling you to build it against the NDK yourself.

The reason is licensing and it is not incidental. FFmpeg is LGPL 2.1+ with
optional GPL parts, so *which decoders you compile in* determines the license
of the resulting `.so`. That makes it an app-level decision, and Google
declines to make it on anyone's behalf. Every consumer of this decoder is
therefore choosing whose build to trust; there is no upstream default.

## Why Jellyfin's

[jellyfin/jellyfin-androidx-media](https://github.com/jellyfin/jellyfin-androidx-media)
publishes to Maven Central under GPL-3.0, which is this repository's license
too, so the strictest reading of the FFmpeg GPL parts is already satisfied.

What that buys over a checked-in file:

- **Dependabot can see it.** `.github/dependabot.yml` used to name the aar
  under "out of reach, so expect nothing of it" — a file dependency is
  invisible to any updater. A coordinate is not. That clause is gone and
  `flake.nix` stands alone there now.
- **The repository stops paying for it.** A binary does not diff, so 898 KB
  landed again on every rebuild.
- **Provenance moves to a named project's CI**, signed on Central, rather than
  a file someone copied in by hand.

It does not resolve provenance. It is still a prebuilt native library compiled
by someone else; the question moves rather than being answered.

## What the decoder set becomes

The old build was `ENABLED_DECODERS=(alac)` — one codec, confirmed by
`strings` on the shipped `.so`, which contained exactly `ff_alac_decoder`.
Jellyfin's carries twelve: `aac`, `aac_latm`, `ac3`, `alac`, `dca`, `eac3`,
`flac`, `mlp`, `mp3`, `pcm_alaw`, `pcm_mulaw`, `truehd`.

Most of that is redundant. `flac`, `mp3`, `aac` and PCM are decoded natively by
the platform, and `EXTENSION_RENDERER_MODE_ON` prefers the platform decoder
anyway. The genuinely new ground is `ac3`, `eac3`, `dca`, `mlp` and `truehd`.

Those matter here because **Siskin has no transcode fallback**.
`MediaUrlBuilder.streamUrl` hands ExoPlayer the raw part; there is no
`/music/:/transcode/universal/start` call and no client profile anywhere in the
tree. What plays is decided entirely by what the app can decode, so a format
the head unit lacks and the extension lacks does not degrade — it fails.

The cost is size, and it is paid in the car rather than in the repository. The
`arm64-v8a` `libffmpegJNI.so` goes from 552 KB to 1,462 KB uncompressed, and
that is the delivered increase — the app ships as an App Bundle and Play
generates a per-ABI split at install, so a car receives one `.so`. The 2.9 MB
figure for the whole artifact is four ABIs and is never shipped as such.

**The `splits { abi { ... } }` block is not what does that**, which matters
because CLAUDE.md sends you to `assembleRelease` to check a size delta. The
block calls `reset()` and adds no `include`, so there are no per-ABI APKs to
emit and the only output is the universal one — carrying all four `.so` files,
5.8 MB of native code. Measuring there suggests the car pays for every ABI. It
does not; the bundle is the shipped path and `bundleRelease` is what to measure.

## The version skew, knowingly accepted

`FfmpegAudioRenderer` extends `DecoderAudioRenderer`, which is `@UnstableApi`
and free to break across minor versions. Nothing in our source references it.
So a media3 release that breaks the renderer leaves the build green, CI green,
and the extension silently not loading in the car.

Jellyfin's newest is `1.9.0+1`, published 2025-12-29 and tracking media3 1.9.0.
The catalog is at 1.11.0 and Dependabot will keep pushing it forward, because
#131 decided nothing is ignored.

**This was accepted rather than guarded, and the reason is that the swap does
not introduce the risk.** The deleted `bin/build.sh` pinned
`MEDIA3_VERSION="1.9.2"` — the blob was built against 1.9.2 while the catalog
sat at 1.11.0, exactly the same distance behind. The difference is that the
blob's skew was invisible and Jellyfin's is at least a version number someone
can read. Options considered and declined: pinning media3 behind a Dependabot
`ignore` entry, which reverses #131 for the library most central to this app;
and a JVM test mirroring the reflective lookup so a bump fails CI instead of
the car. The bet is that a media3 release breaking the renderer is a release
Jellyfin also has to respond to.

Gradle resolves the conflict upward, so there is no downgrade risk in the other
direction — verified, not assumed: `:app:dependencies` reports
`androidx.media3:media3-decoder:1.9.0 -> 1.11.0` and the same for
`media3-exoplayer`.

`1.9.0+1` is a static version despite appearances. Only a version *ending* in
`+` is a Gradle wildcard; the suffix here is Jellyfin's build number.

## Why the capability is kept at all

The library this is tested against may hold no ALAC any more. That is not an
argument for dropping the extension: with no transcode fallback, a re-rip or a
second library brings the format back and the failure mode is a track that will
not play. The decoder is insurance, and the swap makes the insurance cheaper to
hold rather than justifying it by present use.

## What went with it

`bin/` — `build.sh`, `Containerfile`, `README.md` — built the alac-only aar
against media3 1.9.2 and FFmpeg `release/6.0`. It came from upstream and nobody
in this fork ever ran it. It now describes a thing the tree does not ship, so
it is deleted rather than left to read as current; git history has it if the
escape hatch is ever wanted.

Building it ourselves was the other real option, and it is the only one that
answers the provenance question — a Nix derivation could hash-pin the NDK, the
FFmpeg source and the decoder set, keeping both the codec choice and the media3
version ours. It was declined on cost: `ci.yml` is `setup-java` plus Gradle,
with no Nix and no NDK, so it means either teaching CI to build a native
library or standing up a second workflow publishing to GitHub Packages. That is
a project, not a cleanup, and it stays available if Jellyfin's artifact goes
stale.
