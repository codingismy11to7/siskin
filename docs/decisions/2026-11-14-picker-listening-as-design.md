# The server picker says who is listening

**Date:** 2026-11-14
**Status:** Approved
**Issue:** #274

## Context

The server picker lists the servers plex.tv returns for the account token in
use. For a Plex Home managed user that is only the servers the admin shared
with them, which is correct: they can open no other. But the picker does not
say so. Signed in as a managed user, Choose server on the debug Server tab
showed one server where "every server I can reach" was expected, and nothing on
screen explained the short list.

The same confusion is reachable at sign-in, and is worse there. In a Home of two
or more, sign-in shows the member picker and then the server picker, and back
from the server picker goes to `Disconnected`: the PIN is spent, so picking the
wrong member means scanning a new QR code to correct it.

## Decision

**Whenever the account belongs to a Home of two or more members, the server
picker shows a "Switch user" row reading "Listening as <name>", pinned under
the heading.** Tapping it opens the member picker. The rule is the same however
the picker was reached: sign-in, the debug screen's Choose server, and the
server picker Settings' Switch user can end on. A one-member Home shows nothing
new, as in Settings.

One rule rather than a list of routes that get the row. The fragment cannot
tell its two pushed callers apart, and should not need to: whether there is
anyone to switch to is a fact about the account, not about how the screen was
reached.

### The row

Pinned between the heading and the scrolling list, outside the scroll, so it
explains the list before the list is read and stays put however long the list
is. It looks like Settings' Switch user row — "Switch user" over a single
ellipsized "Listening as <name>" — and reuses its two strings, so it adds no
translations.

Rejected placements: as the list's last item, where a long list scrolls it out
of view and it reads as one more server; and as a one-line "Listening as X ·
Switch" subtitle, whose tap target is too small for a car.

### Who is named

`ChoosingServer` carries the Home — its members and which of them is
listening — or nothing, when there is no Home of two or more to show. The row is
drawn from that and from nothing else. Each way in already holds the answer, so
the row costs no plex.tv call of its own:

- **Sign-in** has just listed the Home and switched to a member.
- **The debug screen** reads `HomeUsersRepository.remembered()`, the same
  answer and the same two-or-more rule Settings' row uses. Nothing remembered
  means no row.
- **After a switch** the listener is the member just chosen. Nothing has been
  written yet, so the remembered answer still names the previous user and
  cannot be used.

"Two or more" means the same thing everywhere it is asked — sign-in's member
picker, Settings' Switch user row and this one: a Home of two or more members,
which, wherever someone is known to be listening, includes them. One place
answers it, so the three cannot disagree about whether there is anyone to
switch to. (Sign-in's member picker asks before anyone is listening, so only
the count applies there.)

The Home also records who is **saved** — the member whose session is stored —
when that is known: on the debug screen it is the listener, and after a
Settings or row switch it is the member the switch started from. At sign-in
nobody is, since the stored session (if any) is not known to be in this Home.

### Switching from the row

The row opens the member picker with the listener marked as current, so
tapping them does nothing, as in Settings. Choosing someone switches from the
picker's pending account — any member's token can switch again — and **always
lands on that member's server list**, never committing straight through. The
switch is given no saved session, so the outcome is always `NeedsServer`.

Settings' Switch user keeps the saved server when the new member can still see
it, and that stays right there: someone switching from Settings wants to keep
listening. Someone on the server picker came to choose a server, and switching
from it to have the flow commit and close would skip the very list they were
looking at. Nothing is written until a library is picked, through the existing
commit, so backing out of the new list leaves the stored user in place.

Whether a switch may keep the saved session is a flag of its own on the member
picker, not inferred from where back goes: the two happen to coincide today,
and a later entry point that wants one without the other should not inherit
both.

**Choosing the saved member is not a switch.** After a pending switch to Y,
the row's picker marks Y as listening; tapping X, who is still saved, returns
to X's own server list using the token already stored, with no PIN and no
Plex switch spent. A switch there would mint X a new token, and committing a
changed token clears the queue even when X then picks the very server and
library they had — the hazard `reopenUserPicker` exists to avoid. X carries no
mark of their own: the list reads as Settings' does, with the check on whoever
is listening.

### Back

Back from a member picker opened from the row returns to the server picker it
was opened from, with that list unchanged — one step, as back from the library
picker already is. `ChoosingUser` and `EnteringPin` carry that server picker
the way `ChoosingLibrary` carries its servers; when they carry one,
`PlexSignInFragment.claimsBackPress` claims the press even when pushed. Without
one, a pushed member picker still hands back to the fragment back stack, so
Settings' Switch user returns to Settings as before.

**Back while a switch is in flight returns to the member picker it started
from**, cancelling the switch, rather than to `Disconnected`. That holds for
every switch, Settings' included: at sign-in `Disconnected` costs a new QR
code, and pushed from Settings or the debug screen it shows "Sign in required"
to someone who is signed in. `Working` stays one state; the view model keeps
the picker a switch started from for as long as the switch runs.

Back from the server picker itself is unchanged: out to the debug screen when
pushed from there, to `Disconnected` at sign-in.

Rejected: letting back from the row's member picker fall through to the
fragment back stack. Simpler, but from the debug screen it skips the screen the
user was just on, and at sign-in it abandons the sign-in.

## Testing

- **`PlexSignInViewModelTest`:** sign-in in a Home of two or more carries the
  Home on `ChoosingServer`, and a one-member Home carries none; the debug
  picker carries the remembered Home, and none when nothing is remembered; a
  switch from the row lands on `ChoosingServer` naming the new member even when
  the saved server would have carried over; back from that member picker, and
  from its PIN pad, returns to the original server list; backing out writes
  nothing; choosing the saved member after a pending switch spends no switch
  and commits the stored token; back during a switch returns to the member
  picker it started from, at sign-in and from Settings.
- **`PlexSignInFragment`:** a pushed member picker opened from the row claims
  back; one opened from Settings does not.
- **On the emulator:** as the managed user, open Choose server from the debug
  screen, switch to the owner, and confirm the list grows; back out and confirm
  the stored user is unchanged. Sign in again, pick the wrong member, and
  correct it from the row.
