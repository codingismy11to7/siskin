# Ship Siskin on Google Play

**Date:** 2026-07-31
**Status:** Approved
**Issue:** #49

## Context

Siskin works. It browses a Plex music library in the car, signs in over a PIN,
recovers from a moved server, and has 272 tests behind it. What it does not have
is a way onto the head unit it was written for.

The car's head unit is **locked** — no developer mode, no ADB, over network or
otherwise. This was known before the fork began, established the hard way over
several hours on an earlier forked app. Siskin was never built on an assumption
that sideloading would work; Play was always going to be the destination.
`adb install` is how every build has reached the emulator, and it was never
going to be how anything reached the car.

What has changed is only that distribution moved from a deferred problem to the
immediate one. Two things follow. Google Play is the only channel. And the
GitHub release workflows inherited from upstream — `github_release.yml`,
`github_prerelease.yml`, never once triggered because nothing has ever been
tagged — were building artifacts for an audience that cannot install them.

## Decisions

### Android Auto projection is rejected on principle, not on capability

The car supports wireless Android Auto, and Siskin could reach it cheaply. The
manifest still carries the `com.google.android.gms.car.application` meta-data and
`auto_app_desc` resource from tempo, and `MediaLibraryService` serves projection
and native AAOS from the same code. The only real obstacle is the
`android.hardware.type.automotive` `required="true"` declaration, which a build
flavor could relax. A phone build sideloaded onto a phone would have put Plex
music in the car the same evening.

It is rejected anyway, because it inverts the reason the fork exists. Siskin is
a media source *in* the car, not a phone app projecting into one. Solving the
distribution problem by becoming the thing the project was defined against is not
solving it.

### A free, public production listing, reached through the test tracks

Play's internal testing track is a permanent private distribution channel: up to
100 testers added by email, no production application, no tester-count clock, no
public listing, and it persists indefinitely. For "run my own app in my own car
for as long as I own the car," that would have been sufficient, and it was the
plan for a while.

Production won because the incremental cost is smaller than it first appears. The
automotive form-factor review is not the obstacle it was initially treated as
(see below), which leaves the store listing and the tester requirement as the
real delta — both bounded, neither risky. Shipping publicly costs a few evenings
more than shipping privately, and the app is already built.

Internal testing is still the **first stop**, not a road not taken. Releases
promote upward between tracks, so starting there forecloses nothing: it puts
Siskin in the car sooner and gives it a shakedown before any stranger sees it.
What it does not buy is time — the twelve-tester requirement is specifically a
*closed* test, so days spent on the internal track do not advance that clock.
The route is internal → closed → production.

Only two things are permanent from the first upload to *any* track: the package
name (#42) and, once enrolled in Play App Signing, the app signing key (#43).
Everything else — listing copy, graphics, the content declarations — is
revisable later.

### Not monetized

The app was going to be sold. That is abandoned, and the reasoning is worth
recording because the arguments are not all equally good.

**The market gap closed during development.** Two other AAOS Plex clients exist.
`us.berkovitz.plexaaos` (MIT, free, on Play) is weak — playlists-only browsing,
no multi-server, no multi-user, all open TODOs in its README. **Momentum** is
not: announced on the Plex forums on 2026-07-11, native AAOS, Plex music
specifically, and its claimed feature set is a superset of Siskin's —
Playlists/Artists/Albums/Genres, QR sign-in, direct FLAC with fallback, LAN/WAN
discovery with WiFi-to-cellular handoff, and multi-server support added
2026-07-19, which is precisely the feature Siskin deliberately dropped. It is
already through the automotive review and live on Play. The original thesis was
"a real gap, and being first to fill it." Nineteen days took that.

**GPLv3 is not the reason, despite appearances.** The license permits charging
for distribution; "source is free, binary is paid on Play" is a well-worn model.
What GPL costs is the ability to *stop* redistribution, which is a different
thing from the ability to charge, and for a niche utility most buyers pay for
auto-updates rather than rebuild from source. This argument was raised, examined,
and does not carry weight.

**The arithmetic is the reason.** Low millions of Google-built-in vehicles,
times the fraction whose owners run Plex, times the fraction who want *music* in
the car specifically, times the fraction who will pay rather than take the free
competitor. Buyers plausibly in the hundreds; perhaps one to three thousand
dollars at a few dollars a copy. Against that sits the production tester gate, a
merchant account with tax registration across jurisdictions, and — the part that
is consistently underestimated — a permanent support obligation for an app whose
failure modes are mostly *the user's own network and Plex server*, arriving as
refund requests and one-star reviews. The goal was a little passive income; a
paid app with customers is close to the least passive income available.

**AAOS is a growing market, and that cuts both ways.** The install base is on a
real upward trajectory as most of the industry commits to Google built-in, so the
buyer pool is not fixed and revenue would accrue over years rather than landing
once. But the same growth raises the risk that actually matters, which is not
Momentum — it is Plex shipping native AAOS support in Plexamp. Plexamp already
has a `MediaBrowserService` serving Android Auto, so the port is small, and users
have been asking since 2020. What has held it back is an install base too small
to justify the work. A business whose downside case is triggered by its own bull
case is not a good business.

### Publishing free is permanent

Play does not allow converting a free app to paid. Paid to free is permitted and
irreversible; the reverse requires a new app under a new package name, and
package names are themselves permanent. Publishing free therefore forecloses
charging later by any route except in-app purchase.

This is recorded because it is a one-way door being walked through deliberately,
not one being stumbled through.

### The listing keeps "Plex" out of the title

The app title is `Siskin - Your Car. Your Music.` — exactly 30 characters, which
is Play's cap, with no headroom.

Momentum went the other way: `Momentum: Plex music in your car`. Descriptive use
of a trademark is common on Play and generally defensible, and Momentum's title
cleared review, so this is not a case of avoiding something prohibited. It is a
choice to trade search ranking for a smaller surface — the residual risk in that
pattern is not Google refusing it, it is the trademark holder objecting, and Plex
has been relaxed about third-party clients but has made no promises.

The cost is real. Play has no keyword field; it indexes title, short description,
then full description, in roughly that order, and the Console's tags are a fixed
Google taxonomy with nothing free-form. Keeping the term out of the title gives
up the strongest signal. The **short description** carries it instead, which is
the next-strongest and reads as description rather than as a claim on the name.
An explicit unofficial / not-affiliated disclaimer goes in the full description.

### The gate is the tester requirement, not the automotive review

The automotive form-factor review was initially treated as the risk in this plan.
It is not. For a media app the car's own media UI renders the browse tree, so
there is almost no reviewable surface — the app supplies data, not screens. The
one custom Activity, `CarSignInActivity`, is already built the way the guidelines
ask: no `distractionOptimized` meta-data, so the platform blocks it while the
vehicle is moving; PIN sign-in rather than typed credentials; and 401/403
surfacing as a resolution `PendingIntent` rather than a dead end. Momentum
cleared the same review with early-build emulator screenshots, which also settles
that emulator captures are acceptable for the listing — worth knowing, since a
locked head unit cannot produce any others.

What actually gates production is the **closed test required of new personal
developer accounts**: roughly a dozen testers opted in continuously for fourteen
days. The exact count needs confirming in the Console — it was twenty and Google
reduced it. Crucially it counts *opt-ins, not installs*, which is the only reason
this is feasible for an app that hard-requires `android.hardware.type.automotive`
and therefore will not install on any tester's phone.

## Alternatives considered

**Abandon the app.** Genuinely on the table once Momentum surfaced, since the
project existed to fill a gap that no longer exists. Rejected because the
expensive part is already paid for — the app is built — and because the reason to
keep it is not feature superiority but repair capability. Momentum is one person,
three weeks old, free, with a dead announcement thread. Over the ~8-year life of
the car, an app that can be fixed on a Saturday is worth more than a marginally
better one that cannot.

**Use Momentum and stop.** Not exclusive with shipping Siskin, and joining its
closed test remains worth doing. It is not a substitute, for the reason above.

**Sell anyway, on some differentiation wedge.** No candidate wedge was
identified, and the arithmetic above does not improve with one.

## What this does not buy

- **No fallback channel.** With sideloading impossible and the GitHub release
  path dead, a Play rejection leaves nowhere to go. The review is expected to be
  routine, but "expected" is doing real work in that sentence.
- **An ~8-year maintenance commitment.** Play forces a `targetSdk` bump roughly
  annually or the app stops being updatable; media3 will churn; Plex will change
  something. Not much work, not zero, and not optional.
- **No claim that Siskin is the better app.** Momentum's feature list exceeds it
  today. This is a decision about control and repairability, not quality.
