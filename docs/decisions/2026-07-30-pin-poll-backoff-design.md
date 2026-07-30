# Back off the sign-in PIN poll

**Date:** 2026-07-30
**Status:** Approved
**Issue:** #22

## Context

`PlexSignInViewModel.awaitApproval` polls `GET /api/v2/pins/{id}` on a flat
two-second cadence for the pin's whole 15-minute life. Nobody who completes a
sign-in pays for that — they approve on their phone within a minute and the loop
ends. The cost falls entirely on the attempt nobody finishes: 900 / 2 is **~450
identical requests**, every one of them answered `200` with an unapproved pin.

The abandoned case is not hypothetical. `CarSignInActivity` is the media
session's activity, so the car's app affordance can open the sign-in screen at
any time — including by accident, and including while already signed in. The
screen has no idea whether anyone is still looking at it.

**This is politeness and metered data, not a defect.** Measured against live
plex.tv on an AAOS API 33 emulator: 174 consecutive polls, all `200`, zero rate
limiting. Plex is not objecting, and 2s is in line with what its link flow
expects. What justifies the change is where the app runs — a head unit, possibly
tethered to a phone or on a connection the vehicle is paying for by the byte.

The fast cadence earns its keep in exactly one window: the seconds between the
user approving on their phone and the car noticing. That window is early. After
a couple of minutes with no approval, the odds that the *next* two-second poll
is the one that succeeds are low enough that the interval can grow a long way
before anyone feels it.

## Decisions

### A three-step ladder keyed on elapsed time

```kotlin
fun pollDelayMillis(elapsedSeconds: Long): Long = when {
    elapsedSeconds <  60L -> 2_000L
    elapsedSeconds < 180L -> 5_000L
    else                  -> 15_000L
}
```

| Elapsed | Interval | Polls |
|---|---|---|
| 0s – 60s | 2s | 30 |
| 60s – 180s | 5s | 24 |
| 180s – 900s | 15s | 47 |
| | **total** | **101** (was 450) |

A fully abandoned attempt costs **101 requests instead of 450**, and the
responsive case is untouched: anyone who approves inside the first minute — which
is nearly everyone who approves at all — sees exactly what they see today. The
worst case is a user slow enough to still be on their phone after three minutes,
who then waits up to 15 seconds for the car to notice. That is the price, and it
is paid only by the people least likely to be watching the screen.

**Keyed on elapsed time, not poll count.** The two are equivalent only while
every poll succeeds. On a dropped poll the loop `continue`s, so a count-based
schedule would advance the ramp on requests that never reached plex.tv — car Wi-Fi
would make the interval widen faster the worse the connection got, which is
backwards. Elapsed time is also the unit `shouldKeepPolling` already works in,
and it makes the function trivially testable at its boundaries.

A smooth exponential was considered and rejected. It reaches roughly 48 polls,
closer to the order-of-magnitude cut the issue imagined, but it hits its ceiling
about a minute in — degrading the one window that matters — and a curve is
harder to read off a test than three flat steps.

### It lives beside `shouldKeepPolling`

`PlexPinState`'s KDoc says the type is about what a poll *means*, and a delay
schedule is not that. But its companion has not been only that for a while:
`shouldKeepPolling` is poll-loop policy, put there so the loop's bounds could be
tested without a network or an Android class in sight. `pollDelayMillis` is the
same kind of thing tested the same way, and splitting the two halves of one
loop's policy across two files to honour a docstring would cost more than it
buys.

The ViewModel keeps none of it. `POLL_INTERVAL_MS` is deleted rather than
rescaled, which leaves `awaitApproval` with no cadence number of its own:

```kotlin
delay(PlexPinState.pollDelayMillis(nowEpochSeconds() - startedAt))
```

Everything else about the loop is deliberately unchanged. The delay still comes
*before* the poll, `shouldKeepPolling` still runs after the sleep, and a dropped
poll is still recovered rather than bound.

### `HARD_CAP_SECONDS` stays at 900

Shrinking the cap is the bigger lever on the abandoned case — five minutes would
take the tail down to ~62 requests — and it is still the wrong move. The cap
matches the pin's real lifetime, which is what makes `SignInError.PinExpired`
true when the loop gives up. Cut the cap and the app stops watching a pin that is
still live, then tells the user a code still on their screen has expired. Being
truthful there is worth more than 40 requests.

What made a 15-minute cap expensive was the tail costing 360 polls. It now costs
47. The cap should be revisited on its own merits, if ever, and not smuggled in
here.

### A clock seam on the ViewModel

`nowEpochSeconds()` reads `System.currentTimeMillis()` directly, and wall-clock
time does not move under `StandardTestDispatcher`. Nothing about the loop's
*timing* is testable today: `advanceUntilIdle()` on an unapproved pin never
terminates, because the cap it is waiting for is measured on a clock that virtual
time cannot advance. The existing `aDroppedPollDoesNotFailTheSignIn` passes only
because its pin is approved on the third poll.

So the clock becomes a defaulted constructor parameter, alongside the `PlexApi`,
`AuthClient` and `ServerProbe` seams already there:

```kotlin
private val nowMillis: () -> Long = System::currentTimeMillis
```

Production behaviour is identical. Tests point it at the dispatcher's scheduler
and drive fifteen minutes of poll loop in milliseconds.

## Hazards

Three things a later reader will be tempted to "fix":

- **The interval is chosen before the sleep, not after it.** Elapsed is read at
  scheduling time, so crossing a boundary costs at most one extra poll at the
  faster rate. Computing it after waking would be marginally more precise and
  would buy nothing.
- **A dropped poll retries at the current ladder step, not at 2s.** Late in the
  flow a blip is followed by a 15-second wait. That is correct: it is precisely
  the regime in which nothing is expected to change.
- **The clock parameter exists for the tests.** It is not dead code and not a
  hook for a future feature; deleting it silently un-tests the cap and the
  cadence, both of which pass vacuously without it.

## Scope

**In:** `pollDelayMillis` and its tests, the `awaitApproval` call site, deleting
`POLL_INTERVAL_MS`, the `nowMillis` seam, and the two ViewModel tests it enables.

**Out:**

- `HARD_CAP_SECONDS`, per the decision above.
- Any change to what the sign-in screen shows. No "still waiting" indicator, no
  countdown, no new state. The ladder is invisible.
- The `evaluate` / `shouldKeepPolling` signatures and their existing tests.
- Backoff anywhere else. `ServerProbe` races connections once and does not poll.

## Verification

`nix develop --command ./gradlew testDebugUnitTest`, with three new tests:

- **`PlexPinStateTest`** — the ladder at its boundaries: 0 and 59 give 2s, 60 and
  179 give 5s, 180 and 899 give 15s. Same shape as the existing
  `shouldKeepPolling` cases, and it is the whole policy in one place.
- **`PlexSignInViewModelTest`** — the executable statement of this spec. With the
  clock on virtual time, `advanceTimeBy(181_000)` then
  `verify(authClient, times(54)).getPin(42L)`: 30 polls at 2s plus 24 at 5s. A
  flat 2s interval would make this 90. This is the test that fails if the call
  site is ever reverted.
- **`PlexSignInViewModelTest`** — an unapproved pin reaches `Failed` at the cap.
  Newly possible with the seam, and it covers the loop's other exit.

`nix develop --command ./gradlew assembleDebug` for the build. Lint is unchanged:
no new user-facing strings, so the `MissingTranslation` baseline does not move.

On the emulator, the check is that nothing looks different — sign in through the
QR flow and the car picks up the approval as promptly as it does today, because
the approval lands inside the two-second window.
