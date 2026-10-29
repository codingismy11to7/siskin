# A dev shell for aarch64-linux, without the Android SDK

**Date:** 2026-10-29
**Status:** Approved

## Context

The flake offered `devShells` for `x86_64-linux` only. Development also happens
on an aarch64-linux machine, which could run `nix run .#siskin-cut` and nothing
else. Both shells should also carry adb and `scrcpy`, for mirroring a device.

## Decision

### The aarch64 shell has no SDK, JDK or emulator

nixpkgs does evaluate `androidenv.composeAndroidPackages` on aarch64-linux, but
only by fetching Google's `linux` zips: `platform-tools_r37.0.0-linux.zip`,
`build-tools_r37_linux.zip`. Their binaries are x86_64. The one that decides the
matter is `aapt2`. AGP needs it for resource processing, which every build and
unit-test task depends on, and nixpkgs has no native build for aarch64-linux
(its `aapt` package covers `x86_64-linux` and `aarch64-darwin`). So the app
cannot be built on that host, and an SDK, a JDK or `gr` in the shell would only
suggest otherwise. The emulator is out for the same reason, and the AAOS system
image is x86_64 besides.

The aarch64 shell carries what never runs an SDK binary: `siskin-cut`,
`siskin-render-web`, `gh`, `ktlint`, `scrcpy`, and adb from nixpkgs'
`android-tools`, which is built from source and runs natively.

### One adb per shell

On x86_64, adb comes from the SDK's platform-tools, which the emulator and AGP
already use, so `android-tools` is not added there. `scrcpy`'s wrapper, though,
prefixes `android-tools`' adb onto its own `PATH`. The x86_64 shell sets `$ADB`,
which scrcpy consults first, to the SDK's adb so that scrcpy and the shell use
the same one.

The differing versions (SDK 37.0.0, android-tools 36.0.1) speak the same adb
protocol, 41. A 37.0.0 client against a server started by 36.0.1 was tested and
does not restart it, so a mismatch is not currently a dropped connection. `$ADB`
is about keeping one adb in play, not about averting a known failure. A future
protocol bump on one side would change that, and only the shell is covered: a
scrcpy started outside it uses the wrapper's adb.

### Two nixpkgs handles, one per system

The x86_64 shell needs an import with `allowUnfree` and the SDK licence
accepted; aarch64 needs neither and uses `legacyPackages`. `pkgsFor` picks
between them, so `nix run .#siskin-cut` and the shell's `siskin-cut` are the
same derivation.

## Alternatives considered

- **`scrcpy.override { android-tools = <the SDK's adb>; }` on x86_64.** It would
  drop the second adb from the closure and cover scrcpy outside the shell, and
  make `$ADB` unnecessary. Rejected because the override misses the binary
  cache, turning scrcpy into a local build on every machine that enters the
  shell, in exchange for avoiding a version difference that does not currently
  break anything.
- **The SDK on aarch64 anyway, for its Java parts.** The jars and
  `android.jar` would run, but `aapt2` is required before anything they do
  matters, and an SDK whose binaries fail with `Exec format error` is worse
  than none.
- **`android-tools` in both shells.** Two adbs on x86_64's `PATH`, with the
  order deciding which the emulator and AGP get.
