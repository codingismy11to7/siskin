# Siskin

**Your car. Your music.**

Siskin is a hyper-focused app that does one thing: play your Plex music library
in your Android Automotive vehicle. No fluff, no ads, no tracking.

It runs on the head unit itself — it is a media source in the car, not a phone
app projecting into one. There is no phone launcher entry and no phone
audience.

## Building

    ./gradlew assembleDebug

`flake.nix` provides the SDK and toolchain — `nix develop`, or direnv.

## License

GPL v3 — see [LICENSE](LICENSE).

## Credits

Forked from [eddyizm/tempus](https://github.com/eddyizm/tempus), itself a fork
of [cappielloantonio/tempo](https://github.com/cappielloantonio/tempo) (v3.9.0).
