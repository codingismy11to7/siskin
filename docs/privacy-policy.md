# Siskin Privacy Policy

**Last updated:** 16 August 2026

Siskin is a music player for Android Automotive OS that plays a Plex music
library on a car's head unit.

## What the developer receives

Nothing.

There is no analytics, no crash reporting, no telemetry, and no usage
measurement of any kind. The app contains no third-party analytics or
advertising libraries, and there is no Siskin server for anything to be sent
to.

That is a narrower claim than "Siskin collects nothing", and the difference
matters. Siskin cannot play your music without talking to Plex, so some data
does leave the head unit — it just never comes to the developer. What leaves,
and where it goes, is set out below.

## What Siskin stores on your device

To stay connected to your Plex account and server between drives, Siskin keeps
the following on the head unit:

- your Plex account token
- the address of the Plex server you chose
- a server access token, where the server issues one
- the identifier of the music library you selected
- an install identifier: a random value generated the first time the app runs

Your Plex account token and any server access token are held by Android's
account system rather than in a file of Siskin's own, which is why **Siskin**
appears under Accounts in the car's settings. Removing it there signs you out,
exactly as signing out inside the app does. The remaining items stay in
Siskin's private storage. Either way the data is on the head unit and no other
app can read it.

The install identifier is Siskin's own, created on the head unit and not read
from the vehicle or the operating system. It is not your advertising ID, and it
identifies this installation rather than you or the car. Plex requires one on
every request and ties your sign-in to it, which is why it exists and why it
has to stay the same — it is what makes the app show up as a single device in
your Plex account rather than a new one after every drive.

Siskin does read two things from the vehicle itself: its make and model, and
the model year where the car reports one. These go to Plex with every request,
so that your account's device list names this car — "Cadillac LYRIQ" rather
than a generic row identical to every other Siskin install. That is what lets
you tell two cars apart, and revoke the right one after you sell one. Where the
vehicle does not report them, Siskin falls back to the head unit's own
manufacturer and model as Android reports them, and only where those are
unavailable too does it send the generic values it used before. Nothing else is
read from the car: not its location, not its speed, not its identification
number.

These never leave the device except to reach Plex, as described below. Signing
in again replaces the tokens. Removing the Siskin account from the car's
settings, or signing out inside the app, removes the account token and any
server access token; clearing the app's data removes everything else listed
here, including the install identifier.

## What Siskin transmits, and to whom

Siskin contacts two kinds of destination, and no others:

- **plex.tv** — to sign in using Plex's PIN linking flow, and to find out which
  servers your account can reach. Every request carries the install identifier
  described above, the car's make and model, and your account token once you
  are signed in.
- **your Plex server** — to browse your library, stream audio, report playback
  progress so that your listening history and resume positions stay correct,
  and send a star rating when you set one. Search terms you enter go here too.
  This is the machine you or someone you know runs; it is not the developer's
  and it is not Plex's.

**Plex Inc. is a third party, and it is the only one.** Signing in means
sending your Plex account credentials to Plex, which is the point of signing
in, but it should be stated plainly rather than left implied. What Plex does
with what reaches plex.tv is governed by Plex's privacy policy, not this one.

Siskin talks to no other company. There is no advertising network, no analytics
vendor, no crash reporter, and no data broker in this app, and nothing is ever
sent to the developer.

## How this maps to the Play Store's Data safety label

Google Play defines "collected" as *transmitted off the device*, whoever
receives it, and "shared" as *transferred to another company*. Those are not
the everyday meanings of the words, so the label is easy to misread in both
directions. Siskin's declaration says:

| Data type | Collected | Shared | Why |
|---|---|---|---|
| Device or other IDs | Yes | Yes | The install identifier reaches plex.tv on every request. Plex is another company, so it counts as both. |

It is declared for app functionality and account management, and for nothing
else. It is not used for advertising, marketing, personalisation, fraud
scoring, or analytics, and it is not sold.

Nothing is listed against the developer, because nothing reaches the developer.

## Permissions

Siskin requests only what playback requires:

| Permission | Why |
|---|---|
| Internet | to reach plex.tv and your Plex server |
| Foreground service, media playback | to keep playing while another app is on screen |
| Wake lock | to keep playback running |
| Notifications | for the playback notification the system requires |
| Car information | to read the make, model and year, so your Plex device list names this car |

Siskin does not request access to location, contacts, microphone, camera, or
your files, and cannot read them.

## Children

Siskin is not directed at children, and nothing described above is gathered,
profiled, or treated differently by age. Using the app at all requires a Plex
account and a Plex server.

## Changes to this policy

If Siskin's behaviour ever changes — for example if diagnostic log upload is
added to help debug problems — this policy will be updated and the date at the
top revised **before** that feature ships, and the app's Data safety declaration
on Google Play will be updated to match.

## Contact

Questions or concerns: <https://github.com/codingismy11to7/siskin/issues>

## About this document

Siskin is not affiliated with, endorsed by, or sponsored by Plex Inc. "Plex" is
a trademark of its respective owner and is used here only to describe what the
app connects to.
