# The Car tab shows every identity tier and picks the one reported

**Date:** 2026-10-25
**Status:** Approved

## Context

`VehicleIdentity.resolve` walks three tiers — what the vehicle reports, what
`Build.*` reports, and two constants — and the first tier with both a make and a
model wins. The Debug screen's Car tab shows only the winner and which tier it
came from. What the *other* tiers would have said is invisible.

That matters on real hardware. A 2026 Cadillac answers `INFO_MODEL` = `L233`,
GM's platform code rather than "Lyriq", and whether `Build.MODEL` would read any
better on that head unit is unknown without changing the code. See #183.

#183 asked for read-only inspection and explicitly not an override. That was
written while the device panel was a few lines at the bottom of a scrolling
column. With the Car tab its own fragment (`2026-10-25-debug-tabs-design.md`)
there is room to show every tier, and once every tier is on screen, choosing
which one is sent is a single tap away. This design takes that step.

## Decision

**The Car tab lists every tier as a card. Tapping a card makes it the tier the
ladder starts from, saved in preferences. The default is the vehicle tier, which
is today's behaviour exactly.**

### There is no Auto

The ladder is vehicle → Build → constants, and a picked tier with nothing to
offer falls down that same ladder. Picking Vehicle is therefore identical to
today's automatic resolution: Auto is not a fourth option, it is the first one.

A throwaway spike tried both. An Auto card on top duplicated whichever card it
landed on and pushed Constants below the fold at 1024×768. An Auto button above
the cards needed an on/off state that read poorly and a second control for what
is one choice. Three cards fit the landscape screen without scrolling.

Falling down the ladder, rather than sending nothing or a half-filled identity,
keeps the existing invariant: a tier is taken whole, so make and model never
come from different sources, and every request still carries a device. A pick
of Vehicle on a car whose read has not landed yet behaves as it does today.

### The tab

Three cards — Vehicle, Build, Constants — each showing what that tier would send:

- the `X-Plex-Device-Name` line (or that none is sent — Constants never sends one),
- `X-Plex-Device` / `X-Plex-Model`,
- a smaller line with the raw inputs behind it: `INFO_MAKE`, `INFO_MODEL` and
  `INFO_MODEL_YEAR` as the vehicle answered them, or `Build.MANUFACTURER` and
  `Build.MODEL`. This line is the point of #183 — it is the only place the
  unprocessed `L233` is visible.

The card actually being sent is outlined in `colorPrimary`. When the picked tier
has nothing, its title says so and the card that fell through to is outlined and
labelled as the fallback, so the screen never claims a tier that is not being
sent.

A tier with nothing to offer is greyed and cannot be tapped. Picking it would
only save a starting point that falls straight through, and the one case where
that helps — restoring the default during a session in which the car has not
answered — clears itself once the vehicle read lands, or never matters on a
head unit that reports nothing. Constants is always tappable.

An info icon beside the "Device" heading explains that the outlined card is what
the car reports to Plex. Material 3's answer for a tapped info icon is a rich
tooltip, which MDC-Android does not ship — its only tooltip is the plain one on
long-press, which nobody does in a car. It is a `PopupWindow` styled as a rich
tooltip: `colorSurfaceContainer` and `colorOnSurfaceVariant`, 12dp corners,
dismissed by any tap outside. The inverse-surface colours of a plain tooltip
were tried and rejected on looks.

Debug strings stay `translatable="false"`, as all of this screen's are.

### Where the pick lives

One preference, `vehicle_identity_tier`, holding `vehicle`, `build` or
`constants`; absent means `vehicle`. An unrecognised value also reads as
`vehicle`, so a future tier removed or renamed degrades to the default.

It survives sign-out. Sign-out clears the session's values and nothing else —
tab order, Keep the music playing and every other setting persist — and this
setting describes the car, not the account.

### Where it is applied

- `VehicleIdentity.resolve` takes the starting tier as a parameter and skips the
  tiers above it. It stays pure and is where the tests live.
- `VehicleInfoReader` keeps the raw vehicle answers alongside the resolved
  identity, so the Car tab can show every tier and a new pick resolves without
  asking the car again.
- `PlexApi.vehicle` reads the preference and passes it through. The `car/`
  package still knows nothing of Plex or preferences.

Every caller of `PlexIdentity.headers` already goes through `api.vehicle` and
builds headers per request, so a pick takes effect on the next request with no
client rebuilt.

## Alternatives considered

- **Read-only inspection, as #183 asked.** Cheaper, but a tier you can see and
  cannot select invites exactly the next issue. Selection costs one preference
  and one parameter.
- **A user-typed device name.** The issue's own suggestion for prettier names.
  It needs text entry in a car and is independent of tier choice; it can be
  added later without changing anything here.
- **A pick that lasts until restart.** It makes a forgotten pick self-healing,
  but fallback already makes a forgotten pick harmless, and a pick that silently
  reverts defeats choosing Build to tidy the Plex device list.

## Testing

- `resolve` from each starting tier, and falling from an empty picked tier to
  the next one down.
- `PlexApi.vehicle` honouring the preference, including absent and unrecognised
  values.
- The card text for each tier, built framework-free as `buildDevicePanelBody`
  is today.
- The fallback labels, which the emulator cannot show — `sdk_gcar_x86_64`
  answers every vehicle property — are covered by the card-text tests.

## Changelog

One `**Debug:**` bullet; drivers never see this screen, and the default changes
nothing that is sent.
