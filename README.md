# Siskin

A Plex music client for Android Automotive OS.

Siskin runs on the head unit itself — it is a media source in the car, not a
phone app projecting into one. There is no phone launcher entry and no phone
audience.

**Status: mid-conversion.** Siskin is a fork of Tempo being retargeted from
Subsonic to Plex. The Plex API layer has landed; browse-tree mapping, playback,
QR sign-in and Subsonic removal have not. Design records for each piece are in
[docs/decisions](docs/decisions).

## Building

    ./gradlew assembleDebug

`flake.nix` provides the SDK and toolchain — `nix develop`, or direnv.

## License

GPL v3 — see [LICENSE](LICENSE).

## Credits

Forked from [eddyizm/tempus](https://github.com/eddyizm/tempus), itself a fork
of [cappielloantonio/tempo](https://github.com/cappielloantonio/tempo) (v3.9.0).
