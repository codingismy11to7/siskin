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

# AAOS is multi-user and `am`'s subcommands disagree about the default:
# `am instrument` uses the caller's user, `pm clear` and `am force-stop` use
# user 0. Resolved once and passed explicitly to every call below, so they all
# act on the profile the instrumentation actually runs as.
if ! USER_ID="$(adb shell am get-current-user | tr -d '\r\n')"; then
  echo "::error::am get-current-user failed (device or transport problem)" >&2
  exit 1
fi
if ! [[ "${USER_ID}" =~ ^[0-9]+$ ]]; then
  echo "::error::am get-current-user returned a non-numeric user ('${USER_ID}')" >&2
  exit 1
fi
echo "Resolved Android user: ${USER_ID}"

SHOTS="build/emulator-screenshots"
mkdir -p "${SHOTS}"

# Runs on every exit, pass or fail, so a red run still carries a picture of
# whatever was on screen and the documented force-stop order still happens when
# a scenario fails partway through. `$?` must stay the first statement or the
# script's own exit status is lost. Cleanup failures stay non-fatal: by the time
# this runs the gate's question is answered, so a red here is flake unrelated to
# the app.
on_exit() {
  local rc=$?

  if ! adb exec-out screencap -p > "${SHOTS}/on-exit.png" 2>/dev/null; then
    echo "::warning::on-exit.png: screencap failed (device or transport problem)" >&2
  fi

  if ! adb shell am force-stop --user "${USER_ID}" "${PKG}"; then
    echo "::warning::cleanup: could not force-stop ${PKG} (device or transport problem)" >&2
  fi
  if ! adb shell am force-stop --user "${USER_ID}" com.android.car.media; then
    echo "::warning::cleanup: could not force-stop com.android.car.media (device or transport problem)" >&2
  fi

  exit "${rc}"
}
trap on_exit EXIT

# `am instrument` exits 0 even when tests fail -- it reports failure in its
# output, not its status. Without this every red run would be a green job.
run_scenario() {
  local label="$1"
  echo "::group::${label}"

  # A bare `output="$(cmd)"` under `set -e` dies here when adb fails, before
  # ::endgroup:: or any diagnostic is printed. `if !` keeps the failure path
  # able to close the group and annotate.
  local output
  if ! output="$(adb shell am instrument -w -r --user "${USER_ID}" -e class "${CLASS}" "${RUNNER}" 2>&1)"; then
    echo "${output}"
    echo "::endgroup::"
    echo "::error::${label}: adb invocation failed (device or transport problem, not a test failure)" >&2
    return 1
  fi
  echo "${output}"
  echo "::endgroup::"

  # `INSTRUMENTATION_CODE: 0` signals abnormal termination -- a native crash or
  # a kill -- the one failure mode where JUnit never prints `stack=` or
  # `FAILURES!!!`. This script's testing never exercised that path, so do not
  # drop the clause on the strength of the two cases that were tested.
  if echo "${output}" | grep -qE "^INSTRUMENTATION_STATUS: stack=|FAILURES!!!|INSTRUMENTATION_CODE: 0"; then
    echo "::error::${label} failed" >&2
    return 1
  fi
  # Requires at least one digit before "test(s)" so a class emptied by a
  # refactor -- "OK (0 tests)" -- fails loudly instead of matching "OK (".
  if ! echo "${output}" | grep -qE "OK \([1-9][0-9]* tests?\)"; then
    echo "::error::${label} produced no OK result -- the run did not complete" >&2
    return 1
  fi
}

# Bounded poll (wall-clock, not iteration count) for a marker string in the
# on-screen accessibility tree; a fixed sleep was not reliable in CI. See
# docs/decisions/2026-09-07-emulator-smoke-test-design.md for the reasoning
# and how the budget/timeout values were picked.
#
# `timeout` execs its argument directly, so a shell-function `adb` stub
# silently falls through to the real binary -- stubbing this for a re-test
# needs a PATH shim, not a function override.
wait_for_text() {
  local marker="$1"
  local budget_s=15
  local dump_timeout_s=5
  local deadline=$(( $(date +%s) + budget_s ))
  local dump rc
  while [ "$(date +%s)" -lt "${deadline}" ]; do
    if dump="$(timeout "${dump_timeout_s}" adb exec-out uiautomator dump /dev/tty 2>&1)"; then
      if echo "${dump}" | grep -qF "${marker}"; then
        return 0
      fi
    else
      rc=$?
      if [ "${rc}" -ne 124 ]; then
        echo "::error::uiautomator dump failed (device or transport problem, not a test failure)" >&2
        exit 1
      fi
    fi
    sleep 0.5
  done
  return 1
}

# Scenario 1 and 2: the service runs before any Activity opens, and when no
# Activity can be shown. Structural here -- there is no launcher activity, so
# binding without starting CarHostActivity is both cases at once.
run_scenario "Scenario 1+2: service serves the tree with no Activity"

# Scenario 3: the service runs when the user is not signed in, on a genuinely
# cold profile rather than an assumed one. `pm clear` also stops the app.
if ! adb shell pm clear --user "${USER_ID}" "${PKG}"; then
  echo "::error::pm clear ${PKG} failed (device or transport problem)" >&2
  exit 1
fi
run_scenario "Scenario 3: service serves the tree after clear-data"

# The service comes back clean from a kill rather than only from a fresh
# install, which is the state a dependency bump actually breaks.
if ! adb shell am force-stop --user "${USER_ID}" "${PKG}"; then
  echo "::error::am force-stop ${PKG} failed (device or transport problem)" >&2
  exit 1
fi
run_scenario "Scenario 4: service serves the tree after force-stop"

# Screenshots last, after all cycling. They are artifacts rather than
# assertions -- nothing is compared against a baseline, so nothing here can
# fail the build on a restyle. Reinstalling while com.android.car.media is
# bound leaves its UI rendering empty, which is why nothing reinstalls below.

# The car's own media UI, showing our tree. Google's pixels, but this is the
# screen a person would actually look at after a dependency bump.
if ! adb shell am start --user "${USER_ID}" -a android.car.intent.action.MEDIA_TEMPLATE \
  -e android.car.intent.extra.MEDIA_COMPONENT \
  "${PKG}/com.cappielloantonio.tempo.service.MediaService" >/dev/null; then
  echo "::error::am start MEDIA_TEMPLATE failed (device or transport problem, not a test failure)" >&2
  exit 1
fi
if ! wait_for_text "Tap the settings icon to connect"; then
  echo "::warning::car-browse.png: signed-out row had not rendered within the wait budget -- captured anyway" >&2
fi
if ! adb exec-out screencap -p > "${SHOTS}/car-browse.png"; then
  echo "::error::screencap car-browse.png failed (device or transport problem, not a test failure)" >&2
  exit 1
fi

# Our own pixels. Signed out this renders PlexSignInFragment in its
# Disconnected state; viewModel.connect() sits behind the retry button's click
# listener, so this mints no PIN and makes no plex.tv call.
if ! adb shell am start --user "${USER_ID}" -n \
  "${PKG}/com.cappielloantonio.tempo.ui.activity.CarHostActivity" >/dev/null; then
  echo "::error::am start CarHostActivity failed (device or transport problem, not a test failure)" >&2
  exit 1
fi
if ! wait_for_text "Connect to Plex"; then
  echo "::warning::sign-in.png: sign-in content had not rendered within the wait budget -- captured anyway" >&2
fi
if ! adb exec-out screencap -p > "${SHOTS}/sign-in.png"; then
  echo "::error::screencap sign-in.png failed (device or transport problem, not a test failure)" >&2
  exit 1
fi

ls -la "${SHOTS}"
echo "All scenarios passed."
