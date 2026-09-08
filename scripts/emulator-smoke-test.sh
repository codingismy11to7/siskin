#!/usr/bin/env bash
# Drives the emulator smoke test gate against an already-booted AVD.
#
# Three invocations rather than one, because a test cannot clear its own app's
# data or force-stop itself -- both kill the process the instrumentation runs
# in. That is why Google describes these as manual steps, and why the cycling
# lives here rather than in a @Before. See
# docs/decisions/2026-09-07-emulator-smoke-test-design.md.
set -euo pipefail

PKG="us.codingismy11to7.siskin.debug"
RUNNER="${PKG}.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="com.cappielloantonio.tempo.service.MediaServiceBindTest"

# `am instrument` exits 0 even when tests fail -- it reports failure in its
# output, not its status. Without this every red run would be a green job.
run_scenario() {
  local label="$1"
  echo "::group::${label}"

  # A bare `output="$(cmd)"` assignment under `set -e` dies at this line if
  # adb itself fails (device offline, transport reset) -- before the group is
  # closed and before any diagnostic is printed. Wrapping it in `if !` lets
  # the failure path still emit ::endgroup:: and an ::error:: annotation
  # instead of leaving a silent, unclosed group in the log.
  local output
  if ! output="$(adb shell am instrument -w -r -e class "${CLASS}" "${RUNNER}" 2>&1)"; then
    echo "${output}"
    echo "::endgroup::"
    echo "::error::${label}: adb invocation failed (device or transport problem, not a test failure)" >&2
    return 1
  fi
  echo "${output}"
  echo "::endgroup::"

  # `INSTRUMENTATION_CODE: 0` is documented as the signature for abnormal
  # instrumentation termination -- a native crash, an uncaught exception on a
  # non-test thread, or the process being killed -- the one failure mode
  # where JUnit never gets to print a `stack=` line or `FAILURES!!!`. This
  # script's own testing (a clean pass and a JUnit-caught assertion failure)
  # never exercised that path, so do not remove this clause on the strength
  # of those two cases alone.
  if echo "${output}" | grep -qE "^INSTRUMENTATION_STATUS: stack=|FAILURES!!!|INSTRUMENTATION_CODE: 0"; then
    echo "::error::${label} failed" >&2
    return 1
  fi
  if ! echo "${output}" | grep -q "OK ("; then
    echo "::error::${label} produced no OK result -- the run did not complete" >&2
    return 1
  fi
}

# Scenario 1 and 2: the service runs before any Activity opens, and when no
# Activity can be shown. Structural here -- there is no launcher activity, so
# binding without starting CarHostActivity is both cases at once.
run_scenario "Scenario 1+2: service serves the tree with no Activity"

# Scenario 3: the service runs when the user is not signed in, on a genuinely
# cold profile rather than an assumed one. `pm clear` also stops the app.
if ! adb shell pm clear "${PKG}"; then
  echo "::error::pm clear ${PKG} failed (device or transport problem)" >&2
  exit 1
fi
run_scenario "Scenario 3: service serves the tree after clear-data"

# The service comes back clean from a kill rather than only from a fresh
# install, which is the state a dependency bump actually breaks.
if ! adb shell am force-stop "${PKG}"; then
  echo "::error::am force-stop ${PKG} failed (device or transport problem)" >&2
  exit 1
fi
run_scenario "Scenario 4: service serves the tree after force-stop"

# Screenshots last, after all cycling. They are artifacts rather than
# assertions -- nothing is compared against a baseline, so nothing here can
# fail the build on a restyle. Reinstalling while com.android.car.media is
# bound leaves its UI rendering empty, which is why nothing reinstalls below.
SHOTS="build/emulator-screenshots"
mkdir -p "${SHOTS}"

# The car's own media UI, showing our tree. Google's pixels, but this is the
# screen a person would actually look at after a dependency bump.
if ! adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
  -e android.car.intent.extra.MEDIA_COMPONENT \
  "${PKG}/com.cappielloantonio.tempo.service.MediaService" >/dev/null; then
  echo "::error::am start MEDIA_TEMPLATE failed (device or transport problem, not a test failure)" >&2
  exit 1
fi
sleep 5
if ! adb exec-out screencap -p > "${SHOTS}/car-browse.png"; then
  echo "::error::screencap car-browse.png failed (device or transport problem, not a test failure)" >&2
  exit 1
fi

# Our own pixels. Signed out this renders PlexSignInFragment in its
# Disconnected state; viewModel.connect() sits behind the retry button's click
# listener, so this mints no PIN and makes no plex.tv call.
if ! adb shell am start -n \
  "${PKG}/com.cappielloantonio.tempo.ui.activity.CarHostActivity" >/dev/null; then
  echo "::error::am start CarHostActivity failed (device or transport problem, not a test failure)" >&2
  exit 1
fi
sleep 5
if ! adb exec-out screencap -p > "${SHOTS}/sign-in.png"; then
  echo "::error::screencap sign-in.png failed (device or transport problem, not a test failure)" >&2
  exit 1
fi

# The documented recovery order, so a later run does not inherit a wedged
# car media app.
adb shell am force-stop "${PKG}" --user 10 || true
adb shell am force-stop com.android.car.media --user 10 || true

ls -la "${SHOTS}"
echo "All scenarios passed."
