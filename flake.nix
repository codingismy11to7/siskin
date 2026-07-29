{
  description = "Siskin Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";

      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # JDK 21, matching .github/workflows (setup-java, zulu, java-version 21).
      # Gradle 9.4.1 and AGP 9.2.1 both support it, and pinning the same major
      # as CI keeps local and remote builds honest. Nothing in the dependency
      # graph requires a newer JVM, so the current nixpkgs jdk21 is enough.
      jdk = pkgs.jdk21;

      # Must match compileSdk and buildToolsVersion in app/build.gradle:10-11.
      # Siskin pins buildToolsVersion explicitly, so this tracks that pin
      # rather than AGP's "<compileSdk>.0.0" default.
      compileSdkVersion = "36";
      buildToolsVersion = "36.0.0";

      # The emulator runs Android Automotive OS, which is what this fork
      # targets. API 33 is not a preference: it is the only API level for
      # which nixpkgs carries an android-automotive system image (verified by
      # probing 30/32/33/34/35/36 — only 33 resolves). That sits above the
      # app's minSdk — which cannot drop below 28, because AAOS itself only
      # shipped from API 28 and the manifest requires
      # android.hardware.type.automotive — so the app runs here fine, and the
      # compile platform stays at 36.
      emulatorSdkVersion = "33";
      systemImageType = "android-automotive";
      abiVersion = "x86_64";
      avdDevice = "automotive_1080p_landscape";
      avdName = "siskin-aaos-api${emulatorSdkVersion}";

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ emulatorSdkVersion compileSdkVersion ];
        buildToolsVersions = [ buildToolsVersion ];
        systemImageTypes = [ systemImageType ];
        abiVersions = [ abiVersion ];
        includeEmulator = true;
        includeSystemImages = true;
        includeSources = false;
      };

      androidSdk = "${androidComposition.androidsdk}/libexec/android-sdk";

      # Use the wrappers in the package's bin/ rather than reaching into
      # cmdline-tools, whose directory is versioned (not "latest").
      avdmanager = "${androidComposition.androidsdk}/bin/avdmanager";

      # avdmanager honors $XDG_CONFIG_HOME and would otherwise write the AVD
      # to ~/.config/.android/avd, while the emulator binary's own default
      # search order never looks there. Pin both tools to the same
      # ~/.android/avd so they agree regardless of the host's XDG settings.
      # Shared by the shellHook (so avdmanager/emulator invoked directly in
      # the dev shell also agree) and both helper scripts below (so they
      # still work when invoked outside the dev shell).
      avdHomeExport = ''export ANDROID_AVD_HOME="$HOME/.android/avd"'';

      siskin-avd = pkgs.writeShellScriptBin "siskin-avd" ''
        set -euo pipefail
        export ANDROID_HOME="${androidSdk}"
        export ANDROID_SDK_ROOT="${androidSdk}"
        export JAVA_HOME="${jdk.home}"
        ${avdHomeExport}
        # avdmanager only respects ANDROID_AVD_HOME if the directory already
        # exists, silently falling back to the XDG path otherwise, so create
        # it first.
        mkdir -p "$ANDROID_AVD_HOME"
        if "${avdmanager}" list avd -c | grep -qx "${avdName}"; then
          echo "AVD '${avdName}' already exists."
          exit 0
        fi
        echo "no" | "${avdmanager}" create avd \
          --name "${avdName}" \
          --device "${avdDevice}" \
          --package "system-images;android-${emulatorSdkVersion};${systemImageType};${abiVersion}"
        echo "Created AVD '${avdName}'."
      '';

      siskin-emulator = pkgs.writeShellScriptBin "siskin-emulator" ''
        set -euo pipefail
        export ANDROID_HOME="${androidSdk}"
        export ANDROID_SDK_ROOT="${androidSdk}"
        ${avdHomeExport}
        exec "${androidComposition.androidsdk}/bin/emulator" -avd "${avdName}" "$@"
      '';
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          jdk
          androidComposition.androidsdk
          siskin-avd
          siskin-emulator
        ];

        JAVA_HOME = "${jdk.home}";
        ANDROID_HOME = androidSdk;
        ANDROID_SDK_ROOT = androidSdk;

        # AGP otherwise downloads an aapt2 binary from Maven that cannot run on
        # NixOS. Point it at the Nix-patched one instead.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/build-tools/${buildToolsVersion}/aapt2";

        # androidComposition.androidsdk ships a bin/ directory containing adb,
        # avdmanager, sdkmanager, emulator, d8, r8 and friends, so no PATH
        # manipulation is needed here.
        shellHook = ''
          ${avdHomeExport}
          echo "Siskin dev shell: JDK $(java -version 2>&1 | head -n1 | awk -F'"' '{print $2}'), Android SDK ${compileSdkVersion}" >&2
        '';
      };
    };
}
