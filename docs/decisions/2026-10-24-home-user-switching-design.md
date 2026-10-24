# Siskin can play as any member of a Plex Home

**Date:** 2026-10-24
**Status:** Approved
**Issue:** #199

## Context

A tester asked for Plex Home managed users. A managed user has no credentials
of their own — no email, no password — so the PIN-link flow cannot sign one in:
plex.tv/link always links the account the browser is signed in as, and offers no
profile chooser even when the browser has switched to a managed profile (tested).
The only way in is the one Plex's own apps use: sign in as an account in the
Home, then switch.

Who Siskin plays as matters more since #208. Timeline reports attribute
playback to whoever's token made them, so a car signed in as the admin files a
child's listening under the admin's history and recommendations. Ratings
(`:/rate`) are per user as well.

## What Plex does

Measured against plex.tv and a live PMS on 2026-10-24, with a Home of an admin,
a full Plex account member, an unprotected managed user and a PIN-protected
managed user:

- **The Home is listable by any member.** `GET /api/v2/home/users` returns every
  member with `uuid`, `title`, `admin`, `restricted` (managed) and `protected`
  (has a PIN). A managed user's token lists it as well as the admin's does.
- **Switching mints a token.** `POST /api/v2/home/users/{uuid}/switch[?pin=]`
  answers 201 with a JSON account whose `authToken` is a new plex.tv token for
  that user. Switching to the admin returns a fresh admin token, not the
  original. The v1 endpoint (`/api/home/users/{id}/switch`) works too but
  answers XML.
- **Any member's token can switch again.** A managed user's token switched to
  another managed user and back to the admin. Siskin therefore never needs to
  keep the admin's token: one plex.tv token, the active user's, is enough.
- **The PIN is enforced for everyone but the admin.** From a managed user's
  token, a protected target without a PIN or with a wrong one answers
  `403 {"errors":[{"code":1041,...}]}`. From the admin's token, *omitting* the
  PIN succeeds — but a wrong PIN still fails with 1041.
- **Switching is rate-limited.** Roughly ten switch calls in a couple of minutes
  drew `429 {"errors":[{"code":1003,"message":"API rate limit exceeded"}]}`.
- **The switched token does not work on the server.** With a managed user's raw
  token, `GET /library/sections` on the admin's server answers 401. Passing that
  token through `GET /api/v2/resources` yields an `accessToken` for the server,
  distinct from both the raw token and the admin's, and that one works.
- **The server enforces content restrictions.** The managed user's server token
  listed four of the admin's five libraries. Siskin filters nothing itself.

## The flow

`PlexSignInState` gains two states:

- `ChoosingUser(users: NonEmptyList<HomeUser>, account: PendingAccount, currentUuid: String?, messageRes: Int?)`
- `EnteringPin(user, users, account, currentUuid, messageRes: Int?)` — `users` is carried so that back
  returns to the picker, the same way `ChoosingLibrary` carries `servers`.

**The account being signed in is carried in the states, not stored.**
`ChoosingServer` and `ChoosingLibrary` gain the same `account: PendingAccount`:
the PIN-approved token, a switch's minted one, or the stored one when a picker
is reopened from settings. The view model's `commit()` is the flow's only
credential write — account token, server and library land together, or, when
the user backs out of any picker, nothing does. A forced re-sign-in therefore
leaves the saved session whole until the new one is complete, and an abandoned
switch cannot leak a member's token into a later commit.

**At sign-in**, once the PIN is approved, the Home is listed. A single-member
Home goes to `ChoosingServer` exactly as before, so most users see nothing new.
A larger one shows `ChoosingUser`; a protected choice goes through
`EnteringPin`; the switched token becomes the pending account and discovery
continues as that member. A switch at sign-in is never given the saved session,
even on a forced re-sign-in that has one, so sign-in always runs the server and
library pickers.

**From settings**, Switch user enters the view model at `ChoosingUser` with no
PIN, as the debug screen's Choose server enters at `ChoosingServer` through
`reopenServerPicker()`. The picker asks plex.tv who is signed in alongside the
Home, to mark that member; if that call fails or names nobody, the picker
fails rather than showing an unmarked list, which would let the user "switch"
to themselves — spending a rate-limited switch and clearing the queue for
nothing. After the switch, Siskin keeps what still works and asks only for what
does not:

- the saved server and library are both visible to the new user → commit
- the server is visible but the library is not → `ChoosingLibrary` for it
- anything else → `ChoosingServer`, which re-probes whatever is chosen

"Anything else" includes a saved server the new member cannot see, one they
have no token for, one that does not answer, and one whose sections call
fails. Only the switch itself and `/resources` fail the switch: by then the
minted token has spent one of Plex's few allowed switches, and the saved address
is often a home LAN one the car cannot reach on the road, so failing would
leave that member unselectable while other servers are listed for them.

Every branch ends in `commit()`, so the session is still written in one place,
whole, and ends as that commit always has: `Done`, the browse tree invalidated
and the settings activity closed, leaving the car on the new user's library.
That is what the debug screen's server change already does, and returning to
settings instead would leave the browse tree describing the previous user until
something else refreshed it.

A commit whose account token differs from the saved session's clears the queue
and stops playback, as `LibrarySelection.invalidatesQueue` already does for a
server change. A switch always changes it, so the session does not have to
record whose it is to tell. A PIN mints a new token too, so even a same-account
re-sign-in clears the queue; that is accepted, since a re-sign-in is rare and
usually follows a refused token. Keeping the queue was rejected because the
player's loaded items carry the previous user's token in their stream URLs, so
playback would continue as one user while timeline reports went out as another.

**The session is written only once the player has stopped.**
`BrowseTreeInvalidator.stopPlayback` posts the stop to the main looper, and
clearing the items is what reports the outgoing track `stopped`. Written
straight after the post, the new session would be stored before that report
was made: the previous member's Now Playing would never close, and the new
member would receive a stopped timeline for a track they never played — the
misattribution this feature exists to prevent, and reachable on any
same-server switch. So the write runs in `stopPlayback`'s completion callback,
and a `stopped` scrobble carries the server and token current when it was
*made* rather than when its queued request is sent (other states resolve them
when sent, off the main thread). `LibraryPickerRepository`'s
commit orders its write the same way.

Back from `EnteringPin` goes to `ChoosingUser`; back from `ChoosingUser` goes to
settings when entered from there, and to `Disconnected` during sign-in, since
the approved PIN is spent and there is no earlier step.

**Siskin always asks for a protected user's PIN and always sends it**, including
when the active user is the admin. The admin's server-side bypass is never
relied on, so the car behaves the same whoever is signed in, and a wrong PIN is
rejected by Plex rather than by Siskin.

## The switch itself

`HomeUserSwitch`, in `plex/`, does the network half: switch, `/resources` with
the new token, and the visibility check against the saved session. It returns
`Either<SignInError, SwitchOutcome>`, where the outcome is `Committable`,
`NeedsLibrary` or `NeedsServer`, and it persists nothing. Keeping it out of
`PlexSignInViewModel` puts the part with the subtle failure modes in a unit
that can be driven directly. Its failures are new `SignInError` cases rather
than a type of their own, because `PlexSignInFlow.messageFor` is already the one
place a sign-in failure becomes a message.

| `SignInError` | From | Shown |
|---|---|---|
| `WrongPin` | 403 with code 1041 | the PIN pad, cleared, "Wrong PIN" |
| `RateLimited` | 429 | the pad or picker, "Too many tries — wait a moment", entry kept |
| `ServerTokenMissing` | no `accessToken` on a server the user does not own | the picker, with a message |
| `Api(PlexTransportFailure)` | anything else | the picker, existing transport messages |

Telling 1041 from any other 403 means reading plex.tv's error body, which
nothing in the Plex layer has needed before. The switch call therefore returns
Retrofit's `Response` rather than a body, so a non-2xx arrives as a value to
inspect instead of an `HttpException` whose body `plexCall` would discard.

## Writing the credentials

**A managed user's account token must never be readable without that user's
server token.** Today `PlexApi.session`'s setter writes the account token, then
the server token. Between them a reader sees the new account token beside the
old server token — or none — and the reader falls back to the account token. For
an owner that fallback is correct. For a managed user it sends the raw switched
token, the server answers 401, and the car offers "sign in again".

Two changes close it. The first is the order of the writes:

- **The setter writes the account token before any server token, and skips the
  write when the token is unchanged.** On a device
  `AccountManager.setPassword` deletes every auth token on the account, so a
  server token written first would be wiped by the account token that follows
  it. `setAccountToken` therefore does nothing for an unchanged token, and a
  changed one is preceded by marking the stored session not owned. Every
  in-between read is then the old session intact, or no session (a not-owned
  session without a server token is incomplete) — a brief signed-out read,
  never a token the selected server refuses. That signed-out read is the
  accepted cost. Robolectric's shadow does not model the wipe; it was found on
  the emulator, and only the wipe-modelling fakes in `PlexApiWriteOrderTest`
  and `PlexAccountStoreTest` can catch an ordering mistake here.
- **The fallback is only for owned servers.** `Resource.owned` becomes a sixth
  `PlexSession` value, written in preferences with the other three, and a
  session that does not own its server and has no server token is incomplete —
  `PlexSession.from` returns null — rather than one that may use the account
  token. A session saved before this has no flag and reads as owned, which
  keeps today's behaviour exactly: a session on a shared server already carries
  its server token, so the fallback it permits is never taken. The rule lives
  in one place, `PlexApi.serverAuthToken` (and `PlexSession`'s, which delegates
  to it), and every reader of the stored session goes through it. A switch
  whose saved server the new member has no token for is not committed either:
  it falls back to `ChoosingServer`, where picking that server is refused with
  `ServerTokenMissing`.

Who is signed in is not stored. Settings asks plex.tv (`GET /api/v2/user`)
each time it opens, alongside the Home list, so a rename shows up and there is
no stored name to fall out of step with the token beside it. Siskin has never
known who it is signed in as — the `PlexAccountStore` KDoc says so — and it
still keeps no record of it; it only asks.

## Screens

**Settings** fetches the Home when it opens, scoped to the fragment. When the
Home has more than one member, it adds a `Switch user` choice directly above
Sign out and a single ellipsized line in the heading area: "Listening as
<name>". A failed fetch or a
one-member Home adds neither — a plex.tv outage should not put a dead row in
settings. The row is built with the others, hidden, and shown when the answer
arrives, so it keeps its place above Sign out without anything being inserted
later.

The name is in the heading rather than the row because the row is a
`HeadlineSmall` button in a 480dp column: "Switch user · codingismy11to7" is at
the edge in English and wraps in German.

**The user picker** is `addChoice` rows, one per member, as the server picker
builds its list. A protected member's label carries a trailing lock glyph; the
current one a check, and tapping it does nothing.

**The PIN** is a numeric password field on the platform keyboard, and submits
itself at the fourth number. The screen is parked-only already --
`CarHostActivity` has no `distractionOptimized` meta-data, so the platform blocks
it, keyboard included, while the car is moving -- so the keyboard needs nothing
extra.

New strings, each in all five locales: `car_settings_switch_user`,
`car_settings_listening_as` (`%1$s`), `plex_sign_in_choose_user`,
`plex_sign_in_enter_pin` (`%1$s`), `plex_sign_in_error_wrong_pin`,
`plex_sign_in_error_rate_limited`, `plex_sign_in_error_no_server_access`. Member names
are data and are not translated.

## Alternatives considered

- **A separate view model for switching from settings.** Simpler pieces, but the
  server and library fallbacks would either be built twice or hand off into the
  sign-in view model mid-flow — two owners of one flow, the shape #18 was.
- **The switch inline in `PlexSignInViewModel`.** Fewer files, but the part with
  the subtle failure modes would be testable only through view-model state, in
  the largest class in the flow.
- **Keeping the admin's token for switching.** Unnecessary — any member's token
  can switch — and it would mean a second plex.tv credential in the account
  store with nothing reading it.
- **Showing Switch user always, or only when the Home was larger at sign-in.**
  Always means most users tap through to a list of one; deciding at sign-in
  misses a member added later. The live check costs one plex.tv call per visit
  to settings.

## Testing

- **`HomeUserSwitch` against MockWebServer:** unprotected and protected
  switches, with the PIN asserted on the request; 1041 → `WrongPin`, 429 →
  `RateLimited`; each of `Committable` / `NeedsLibrary` / `NeedsServer`; a saved
  server without an `accessToken` for the new member, unreachable, answering an
  HTTP error or holding no music → `NeedsServer`; a `/resources` failure still
  fails the switch.
- **`PlexSignInViewModelTest`:** a one-member Home skips to `ChoosingServer`; a
  larger one shows `ChoosingUser`; back from `EnteringPin` returns to the
  picker; a wrong PIN stays on the pad; a settings switch reaches `Done`; a
  switch at sign-in is given no saved session; the settings picker fails when
  plex.tv cannot say who is signed in; backing out of every picker writes
  nothing; the outgoing track's `stopped` report carries the previous session's
  server token.
- **`PlexApi`:** the session setter writes the account token *before* the
  server token, and marks the stored session not owned before changing the
  account token — observed through the wipe-modelling fakes in
  `PlexApiWriteOrderTest`, which fail if a server token is written before the
  account token that would wipe it on a device.
- **On the emulator, by hand:** switch to the protected managed user, browse,
  and confirm the restricted library is absent and that playback appears under
  that user in the server dashboard; switch back.
