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

      # An AAOS screen does not rotate and the car's system UI is built per
      # hardware profile, so a second orientation or resolution is a second AVD
      # rather than a setting flipped at runtime. `wm size` / `wm density` will
      # override both on a running device, but that only stretches the existing
      # profile's UI -- it is a way to fake a screenshot size, not a way to see
      # what the car actually renders.
      #
      # These two profiles are not chosen for how they look. Play requires an
      # Android Automotive OS listing to carry at least two portrait
      # screenshots at 800x1280 and two landscape at 1024x768, and these are
      # the stock profiles that render at exactly those sizes. Anything else
      # would have to be resized before upload, and a resized screenshot
      # misrepresents what the car actually draws.
      #
      # `automotive_1024p_landscape` is tagged `android-automotive-playstore`
      # rather than the `android-automotive` image nixpkgs carries. That turns
      # out not to matter: avdmanager pairs the two without complaint and a
      # device profile only supplies hardware parameters. Verified created and
      # booted at 1024x768.
      #
      # `automotive_ultrawide` is unusable regardless of taste -- at 3904px it
      # is wider than Play's 3840px maximum for a screenshot side.
      avdVariants = {
        landscape = "automotive_1024p_landscape";
        portrait = "automotive_portrait";
      };

      defaultVariant = "landscape";

      avdNameFor = variant: "siskin-aaos-api${emulatorSdkVersion}-${variant}";

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

      # Resolves $variant to $device, or exits 2 naming the ones that exist.
      # Shared verbatim by both scripts so they can never disagree about which
      # variants are real.
      resolveVariant = ''
        case "$variant" in
        ${pkgs.lib.concatStringsSep "\n" (
          pkgs.lib.mapAttrsToList (name: device: ''  ${name}) device="${device}" ;;'') avdVariants
        )}
          *)
            echo "siskin: unknown AVD variant '$variant'" >&2
            echo "known variants: ${pkgs.lib.concatStringsSep " " (pkgs.lib.attrNames avdVariants)}" >&2
            exit 2
            ;;
        esac
      '';

      siskin-avd = pkgs.writeShellScriptBin "siskin-avd" ''
        set -euo pipefail
        export ANDROID_HOME="${androidSdk}"
        export ANDROID_SDK_ROOT="${androidSdk}"
        export JAVA_HOME="${jdk.home}"
        ${avdHomeExport}

        variant="${defaultVariant}"
        if [ $# -gt 0 ]; then variant="$1"; fi
        ${resolveVariant}
        name="siskin-aaos-api${emulatorSdkVersion}-$variant"

        # avdmanager only respects ANDROID_AVD_HOME if the directory already
        # exists, silently falling back to the XDG path otherwise, so create
        # it first.
        mkdir -p "$ANDROID_AVD_HOME"
        if "${avdmanager}" list avd -c | grep -qx "$name"; then
          echo "AVD '$name' already exists."
          exit 0
        fi
        echo "no" | "${avdmanager}" create avd \
          --name "$name" \
          --device "$device" \
          --package "system-images;android-${emulatorSdkVersion};${systemImageType};${abiVersion}"
        echo "Created AVD '$name' ($device)."
      '';

      # siskin-emulator [variant] [emulator flags...]
      #
      # A leading argument starting with `-` is an emulator flag rather than a
      # variant, so `siskin-emulator -no-snapshot` keeps working unchanged
      # against the default variant.
      siskin-emulator = pkgs.writeShellScriptBin "siskin-emulator" ''
        set -euo pipefail
        export ANDROID_HOME="${androidSdk}"
        export ANDROID_SDK_ROOT="${androidSdk}"
        ${avdHomeExport}

        variant="${defaultVariant}"
        if [ $# -gt 0 ]; then
          case "$1" in
            -*) : ;;
            *) variant="$1"; shift ;;
          esac
        fi
        ${resolveVariant}
        name="siskin-aaos-api${emulatorSdkVersion}-$variant"

        exec "${androidComposition.androidsdk}/bin/emulator" -avd "$name" "$@"
      '';

      # Renders the documents under docs/ that are published to
      # codingismy11to7.us into standalone HTML. Markdown stays the source of
      # truth; nothing hand-edited lives on the web server, so a page can
      # always be regenerated from this repository rather than being lost with
      # whatever shell session produced it.
      #
      # Output goes to build/web/, which is already gitignored. Copy up with:
      #
      #   scp build/web/privacy.html \
      #     steven@192.168.0.2:/mnt/teeb/docker/appdata/swag/www/siskin/
      #
      # That copy is deliberately not automated here: it writes to a public
      # web root, which should stay a decision rather than a side effect of
      # running a render.
      siskin-render-web = pkgs.writeShellScriptBin "siskin-render-web" ''
        set -euo pipefail
        root="$(${pkgs.git}/bin/git rev-parse --show-toplevel)"
        out="$root/build/web"
        mkdir -p "$out"

        # pagetitle, not `--metadata title`: the latter also emits a title block
        # into the body, which duplicates the heading the markdown already
        # starts with. This sets <title> alone and leaves the document's own h1
        # as the only one -- which is also what docs/web/style.html's `h1+p`
        # subtitle rule expects.
        render() {
          "${pkgs.pandoc}/bin/pandoc" "$root/docs/$1.md" \
            --from markdown --to html5 --standalone \
            --variable pagetitle="$2" \
            --include-in-header "$root/docs/web/style.html" \
            --output "$out/$3"
          echo "  $out/$3  ($(stat -c %s "$out/$3") bytes)"
        }

        echo "rendering:"
        render privacy-policy "Siskin Privacy Policy" privacy.html
      '';
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          jdk
          androidComposition.androidsdk
          siskin-avd
          siskin-emulator
          siskin-render-web
          # Siskin is developed on GitHub — PRs, CI logs and issues are all read
          # through gh, so it belongs in the shell rather than being reached for
          # ad hoc via `nix run`.
          pkgs.gh
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
