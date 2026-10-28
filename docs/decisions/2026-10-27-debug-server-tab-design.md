# The Server tab becomes a list of addresses

**Date:** 2026-10-27
**Status:** Approved

## Context

The debug screen's Server tab (#269) is still the dialog that
`2026-08-14-server-address-debug-design.md` introduced, with a rail beside it:
a block of monospace text listing every stored address, a `<- in use` marker,
and two full-width buttons, Re-probe and Choose server.

Looked at on the landscape emulator against a real shared server, it fails at
the question it was built for — "am I on the LAN, or out to the internet and
back?":

- **Nothing names the server.** Not its name, not the library, not whether the
  account owns it.
- **The distinguishing part of each line is buried.** Every address is a
  ~70-character `plex.direct` hostname, and the 32-character machine hash in
  the middle is identical on all of them. What tells two apart — the IP, the
  port — is a few characters in the middle of each.
- **Direct is not one kind of address.** The server on hand advertises two
  `192.168` addresses, two in `172.17`/`172.18` (Docker's bridge range) and a
  public port-forward, all filed as Direct. plex.tv says which are local and the app
  throws that away.
- **It cannot say why an address is not in use.** The static list was a
  deliberate choice in 2026-08-14, and the cost it accepted — "no per-address
  reachability" — is the cost that shows.
- **The two buttons outweigh the report** they sit under.

The design was settled on a clickable prototype at the emulator's 1024×768
rather than from a description, and the choices below are the ones made on it.

## Decision

**The tab shows a header naming the server, then one list row per address.
Tapping a row opens that address's detail inside the tab. Opening the tab
fetches from plex.tv and probes every address.**

### The header

Server name, owned or shared, library name, Plex Media Server version and
platform, and the machine identifier — once, here, instead of in every address.
Choose server becomes a tonal button in this header, beside the thing it would
replace, instead of a primary button below the list.

Name, version and platform come from the plex.tv resource the tab fetches
anyway (`Resource` gains `productVersion` and `platform`). Owned is the
session's. The library name is the one server call the header adds — the
section list, matched against the session's section key — because the name
lives only in `LibraryPickerRepository`'s in-memory map. It is bounded by a
five-second timeout of its own, because it goes to the address in use, which
may be the dead one the tab is open to diagnose, and the ordinary server client
would wait a minute on it. It does not go through address recovery: a debug
screen must not switch addresses as a side effect of drawing its header.

### One row per address

Title is the address's kind, subtitle is the decoded `ip:port`, trailing is an
**In use** chip on the current one and the probe result: latency, a spinner, or
*No answer*. A chevron says the row opens something.

**Kind comes from plex.tv's own flags**: *Local network* for `local`, *Plex
relay* for `relay`, *Remote* otherwise. Guessing from the IP was the
alternative and was rejected because plex.tv already knows, and a guess would
be wrong about exactly the unusual setups this screen exists to diagnose. When
plex.tv cannot be reached, or does not list that address, the flags are
unknown, since only the URIs are stored, and rows fall back to *Direct* and
*Relay* — the distinction the stored list does carry.

The subtitle is `ip:port`, not the URI. A middle-ellipsized URI was tried in
the prototype and still spent the line on the hash; `ip:port` fits without any
ellipsis. The literal URI is one tap away, in the detail. It is plex.tv's own
`address` and `port` for the connection when it supplied them, and otherwise
decoded from the URI — the dashed IP of a `plex.direct` name, or the host as
written. A cleartext address is shown with `http://` in front: the app refuses
cleartext, so that row always reads *No answer*, and without the prefix it
would look identical to the `plex.direct` row for the same IP that answers.

Two layouts were compared beside this one. `ip:port` as the title separates the
four *Local network* rows best, but buries the answer to the LAN question in
the subtitle. The full URI was rejected above.

### The detail opens inside the tab

Kind (with a hint where one is useful — see below), what plex.tv says
(`local`/`relay`, verbatim), the last probe result with the actual failure,
and the full URI. Two actions: **Probe this address** and **Use this address**.

Inside the tab, with the rail still showing, rather than pushed full-screen the
way Choose server is. It is a view of one of this tab's rows, not a different
screen, and keeping the rail keeps the other tabs one tap away. Back from the
detail returns to the list; back from the list leaves the debug screen, as
before. 2026-10-25's "tabs are not history" still holds — switching tabs
touches no back stack — and a detail left open is still open when its tab is
selected again, because tabs are kept alive.

**The Docker hint lives in the detail only.** An address in `172.16.0.0/12` gets
a line saying that range is Docker's default for bridge networks and is usually
reachable only from the server's own host. On the row it would be a guess
presented as a label; in the detail it is context beside the facts.

### Opening the tab fetches, and saves what keeps the address in use

**This reverses 2026-08-14's static list.** That design called per-address
probing "strictly more informative" and rejected it on cost alone: the re-probe
button already existed, and probing each address needed `ServerProbe.answers`
made public plus a result type. A tab of its own is the room that cost was
waiting for.

On open the tab draws the stored addresses immediately, then in parallel:

- asks plex.tv for resources, filling in the header and the kinds;
- probes every address once, each row filling in as its answer lands.

A probe is the existing `/identity` request under the existing 3-second
timeouts, timed, and it reports *how* it failed — the HTTP status or the
`IOException` — where the race only needs a boolean. That is the new result
type.

**The plex.tv answer is saved**, through the same store the address book's
recovery path writes. Opening the tab is therefore also a manual refresh of the
stored list, and that is the point: a server that has moved shows up as moved,
and the next automatic re-race uses the fresh list instead of only what was
true at sign-in. It goes through `ServerAddressBook`, and keeps the stamp rule:
a list is stored only for the session's own machine identifier.

**The plex.tv call runs outside the address book's mutex**; only the re-check
of the session and the store take it. A debug screen must never stall
recovery: holding the lock across a call bounded at ten seconds would queue
playback's and browse's re-probes behind someone looking at the tab. The store
still needs the lock so it cannot interleave with a race that is adopting from
the list it would replace, and the session is re-read under it because sign-in
and the library picker write the session without it.

**The answer is saved only when it still contains the address in use.**
plex.tv's list can lack the address that actually works — it reports what the
server last told it, not what this car can reach — and replacing the stored
list with it would cost recovery the one address known to answer. When the
address in use is missing, the stored list stands and the tab still shows
plex.tv's answer: its name, version and flags are no less true for it.

When plex.tv gives no listing for the server — it cannot be reached, or no
longer lists it — the stored list stands, the header says so after the owned
and library facts it still shows, and the probes run anyway, since they need
only the server.

Each probe opens a new TCP and TLS connection — nothing is kept for reuse, and
it speaks HTTP/1.1, since an HTTP/2 connection is shared by design — so
latencies are comparable across rows and across repeats: a warm connection
would make a second probe of an address look far faster than the first. TLS
session resumption can still trim a repeat slightly.

### Re-probe stays, smaller

Re-probe is still the race that can move `serverUri`, with everything 2026-08-14
says about `force = true` and reporting the outcome. It becomes a text button
in the list's section header, which shows the last re-probe's outcome, and
otherwise when the last round of probes finished. Its outcome also shows in
the list itself — the In use chip moves, or does not.

The per-row probe results and Re-probe are different things on purpose: a row
probe reports and changes nothing, Re-probe races and adopts.

### Use this address is a one-shot adopt

It moves `serverUri` to the chosen address exactly as a re-probe win would —
`adoptAddress`, under the mutex, `machineIdentifier` unchanged — and stores
nothing else. The next transport failure re-races as usual and may move it off
again.

A sticky pin was the alternative. It needs stored state, a way to clear it,
and an answer for what happens when the pinned address dies on the road, where
nobody is going to open a debug screen. One-shot answers the question this
screen asks — "is the LAN address actually faster?" — without leaving the car
in a state the automatic recovery cannot get out of.

It is enabled only when the address's last probe succeeded, and it refuses an
address that is not in the current server's list, which is what keeps it an
address switch and never a server switch.

### Strings

All new text is `debug_`-prefixed and `translatable="false"`, per 2026-08-14.

## Testing

- `ServerAddressBook`: the refresh stores the fresh list for the session's
  server when it contains the address in use, keeps the stored list (and
  still returns plex.tv's answer) when it does not, and stores nothing when
  plex.tv lists only another server; it leaves the stored list alone when
  plex.tv fails; a one-shot use can take the lock while a refresh is still
  waiting on plex.tv; a one-shot use moves `serverUri` and nothing else, and
  refuses an address outside the list.
- The probe result type: success carries a latency, failure carries the reason,
  each against MockWebServer, and two checks in a row each arrive on a new
  connection.
- Row building stays framework-free, as `buildAddressPanelBody` is today, so
  kind, subtitle and the in-use mark are asserted directly: flags present,
  flags absent (the *Direct*/*Relay* fallback), a current address missing
  from the list, plex.tv's own address preferred for the subtitle, and a
  cleartext address told apart from its `plex.direct` twin.
- Under Robolectric through `CarHostActivity`: rows render, a tap opens the
  detail, back returns to the list, a second back leaves for Settings, and a
  probe result rebinds the existing row view rather than replacing it.

Then by hand on the landscape AVD against the friend's server, which has every
kind of address this design names.

## What this does not buy

- **No history.** Each visit reports the present.
- **No verification that an answering address is this server.** `/identity`
  succeeding is all a probe checks, as the race does today.
- **Nothing for a moving car.** Still readable at a standstill only.
- **No portrait layout of its own.** It must not break at 800×1280, but it is
  designed for landscape.
