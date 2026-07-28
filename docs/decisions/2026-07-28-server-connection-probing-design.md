# Server connection probing

**Date:** 2026-07-28
**Status:** Approved

## Context

The QR sign-in from PR #13 works: it reaches the server picker with the account's
media servers listed. Picking one then spins for twenty seconds and fails with
*"Could not reach that Plex server."*

The sign-in spec claimed this flow verifies itself — that reaching the library
picker proves discovery, connection selection and server authentication all work.
It does, and this is the claim paying out: connection selection is broken, and
the first real sign-in found it.

## What the evidence showed

`AuthClient.bestConnectionUri` takes the first connection Plex flags `local`:

```kotlin
usable.firstOrNull { it.local == true && it.relay != true }?.uri
```

plex.tv returned six connections for the test server, in this order:

| # | connection | `local` | reachable from the device |
|---|---|---|---|
| 1 | `172-17-0-1….plex.direct:32400` | yes | **no** — adopted anyway |
| 2 | `172-18-0-1….plex.direct:32400` | yes | no |
| 3 | `192-168-192-2….plex.direct:32400` | yes | no |
| 4 | `192-168-0-2….plex.direct:32400` | yes | **yes** |
| 5 | `107-135-52-98….plex.direct:17160` | no | no |
| 6 | `45-79-210-125….plex.direct:8443` (relay) | no | **yes** |

`172.17.0.1` is the Docker bridge gateway. The server runs in a container, so
that address is meaningful only on the machine hosting Plex. The device adopted
it, OkHttp's 20s `connectTimeout` elapsed, and `onFailure` published
`plex_sign_in_error_server_unreachable`. Confirmed on the device: the failed pick
is still persisted as `plex_server_uri`, and the emulator cannot open a TCP
connection to it while reaching plex.tv, `192-168-0-2` and the relay fine.

This is not an unlucky account. Every containerised Plex server in the test
account advertises bridge gateways the same way, one of them sixteen local
connections deep.

## Why ranking cannot fix it

The obvious repair is a better static order — deprioritise `172.16/12`, prefer
the public address when off-LAN. The evidence rules it out:

- `192.168.0.2` is reachable and `192.168.192.2` is not. Both are `local`, both
  are ordinary RFC1918 addresses, and nothing in the payload distinguishes them.
- Reachability is a property of *where the device is*, not of the address. The
  same car is on the home LAN in the garage and on cellular an hour later, and
  the correct answer changes without the resource payload changing at all.

A client cannot rank what it has not tried. This is why Plex's own clients probe
connections rather than ordering them, and Siskin has to do the same.

## The design

Two tiers, because relay is a fallback and not a peer:

```
chooseServer(resource)
        │
        ├── tier 1: every non-relay connection, probed at once
        │      ├─ 172-17-0-1      ✗ timeout
        │      ├─ 172-18-0-1      ✗ timeout
        │      ├─ 192-168-192-2   ✗ timeout
        │      ├─ 192-168-0-2     ✓ first answer → adopt, cancel the rest
        │      └─ 107-135-52-98   (cancelled)
        │
        └── tier 2: relay, only if every tier-1 probe failed
```

Local and public race together in tier 1. Relay is held back rather than raced
because Plex throttles relayed streams, so winning the race is not the same as
being the right answer — a relay that answers in 30ms while the LAN answers in
40ms would otherwise cost the user bandwidth for the life of the session.

**The probe is `GET /identity`.** It needs no token and returns a few bytes. That
it is unauthenticated is the point: it keeps "I cannot reach this server" and "my
token is wrong" as separate answers, which is the distinction the sign-in
ViewModel already goes out of its way to preserve when it refuses to read a
non-2xx sections response as an empty library. Probing with `GET /library/sections`
instead would save a round trip on the happy path and give that distinction back
up; the round trip is worth less than the clarity.

Probes get their own OkHttp client with a ~3s connect timeout. The existing 20s
is right for a server already known to be reachable and useless for a race — five
candidates at 20s is the bug restated.

Raw OkHttp requests, not Retrofit: the whole point is that each candidate has a
different base URL, and building five short-lived Retrofit instances to fetch a
few bytes is ceremony for its own sake.

### Coroutines

"Fire N, take the first, cancel the rest" is the textbook case for structured
concurrency, and doing it with callbacks means hand-rolling cancellation that
the language already has. Coroutines are adopted here rather than worked around:
kotlinx-coroutines and lifecycle-viewmodel-ktx go in, and Retrofit 2.11 supports
`suspend` service functions natively, so the Plex services return their payload
instead of a `Call`.

The conversion covers the probe, `PlexSignInViewModel`, and the three Plex
service/client pairs. Stopping short of that is the one genuinely bad outcome:
a ViewModel holding both a generation counter and a `Job`, two mechanisms
guarding the same flow. The layer has exactly one consumer today — browse and
playback have not been built on it — so the diff is as small as it will ever be.

Nothing outside `plex/` changes. The Subsonic-era repositories keep their
`Call`-based idiom until they are deleted.

### Where it lives

`plex/api/auth/ServerProbe.kt`, beside `AuthClient` — connection selection
already lives there in the shape of `bestConnectionUri`, which this replaces.
It is not really "auth", and a `plex/api/server/` package would say so more
honestly; that is not worth a package for one file, and moving it later is a
rename.

`bestConnectionUri` goes away. `AuthClient.mediaServers` currently filters on it
being non-null, meaning "has a connection we could use"; it keeps that meaning
with a `hasUsableConnection` predicate instead. Filtering the picker by actual
reachability would mean probing every server in the account before drawing the
list, which is a lot of network for a list the user may not even be waiting on.

### Testing

The bug being fixed is a *network* bug — an address that resolves, accepts no
connection, and burns twenty seconds proving it. Tests that stub the network out
would have had nothing to say about it, so the race is tested against real
sockets:

- **Add MockWebServer** as a test dependency, version-ref'd to the okhttp already
  in the catalog (`5.0.0-alpha.14`), so the mock server and the client under test
  agree. Which artifact — the legacy `mockwebserver` or `mockwebserver3-junit4`
  — gets settled at implementation time against what actually resolves for that
  alpha. `kotlinx-coroutines-test` comes with it, for `runTest` and for driving
  the poll loop's `delay` without waiting on a wall clock.
- The failure modes the probe exists to survive all have real analogues: a port
  with no listener is a refused connection, a `MockWebServer` set to hang gives
  the connect/read timeout that `172.17.0.1` produced in the wild, and shutting
  one down mid-race covers a server dropping out. The test that matters most is
  the original bug: a dead candidate listed first, a live one listed fourth, and
  the live one adopted in well under the dead one's timeout.
- Cancellation is observable the same way: the losing servers record no completed
  request after a winner is adopted.

`ServerProbe.candidates(resource)` stays a pure function returning the two tiers,
tested as one — not because the network is hard to reach in a test, but because
tiering is a decision and this codebase keeps decisions separable from I/O, the
same split `PlexSignInFlow` has from `PlexSignInViewModel`.

### What replaces the generation counter

`PlexSignInViewModel` currently carries a `generation` field, sixty lines of
comment justifying it, and a warning not to simplify it back into a boolean. That
warning is correct and this change is not a violation of it: the counter exists
because a Retrofit callback cannot be called off once enqueued, so the only way
to neutralise one is to make its *result* inert. A cancelled coroutine has no
result — the continuation never resumes — so the same three cases collapse into
job cancellation:

| today | after |
|---|---|
| `generation++` in `retry()`, then re-issue | cancel the attempt's `Job`, launch a new one |
| `generation++` in `chooseServer()` | cancel the pin job; the probe is the new job |
| `generation++` + drain in `onCleared()` | `viewModelScope` cancels on clear |
| `creating` flag over the createPin window | `job?.isActive` |

The duplicate-poll-loop bug the counter was written against cannot recur, because
the loop is a `while` inside the job rather than a self-rescheduling `Handler`
message: cancelling the job ends the loop, and there is no queued message that
can outlive it. A boolean would not have done this; ending the coroutine that
*is* the loop does.

Losing probes are cancelled on a win rather than left to time out, so switching
servers twice does not leave a dozen sockets open against addresses nobody is
waiting on.

## Deferred

**The stored URI goes stale.** A car signed in on the home LAN keeps
`192-168-0-2` in preferences and drives away, and every subsequent call fails
until the user signs in again. The fix is re-probing — at app start, or on the
first failure against a stored URI, promoting the relay when the LAN is gone.
That is the same probe with a different trigger, and it belongs with the browse
and playback work where those failures actually surface. This spec only fixes
the pick made *during* sign-in.

## Verification

The failing case is concrete and reproducible: sign in, pick the containerised
server, and the library picker has to appear instead of a twenty-second spinner
and an unreachable-server message. `plex_server_uri` in preferences afterwards
has to be the LAN address, not the bridge gateway.
