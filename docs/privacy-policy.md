# Siskin Privacy Policy

**Last updated:** October 24, 2026

Siskin is a music player for Android Automotive OS. It plays a Plex music
library on a car's head unit.

## What the developer receives

Nothing.

There's no analytics, no crash reporting, no telemetry, and no usage tracking
of any kind. The app contains no third-party analytics or advertising
libraries, and there is no Siskin server to send anything to.

That isn't the same as saying Siskin collects nothing. Siskin can't play your
music without talking to Plex, so some data does leave the head unit. None of
it goes to the developer. The sections below list what leaves and where it
goes.

## What Siskin stores on your device

To stay connected to your Plex account and server between drives, Siskin keeps
the following on the head unit:

- your Plex account token
- the address of the Plex server you chose
- a server access token, if the server issues one
- the identifier of the music library you selected
- an install identifier: a random value generated the first time the app runs

Your Plex account token and any server access token are stored in Android's
account system instead of in a file of Siskin's own. That's why **Siskin**
appears under Accounts in the car's settings. Removing it there signs you out,
the same as signing out inside the app. Everything else stays in Siskin's
private storage. Either way, the data stays on the head unit and no other app
can read it.

The install identifier is created by Siskin on the head unit. It isn't read
from the vehicle or the operating system, and it isn't your advertising ID. It
identifies this installation, not you or the car. Plex requires one on every
request and ties your sign-in to it, so it has to stay the same. It's what
makes the app show up as a single device in your Plex account instead of a new
one after every drive.

Siskin does read two things from the vehicle itself: its make and model, and
its model year if the car reports one. These go to Plex with every request so
that your account's device list shows this car by name, like "Cadillac LYRIQ,"
instead of a generic entry that looks like every other Siskin install. That
way you can tell two cars apart and remove the right one if you sell a car. If
the vehicle doesn't report them, Siskin uses the head unit's manufacturer and
model as Android reports them. If those aren't available either, it sends the
generic name that every Siskin install shares. Siskin reads nothing else from
the car: not its location, its speed, or its VIN.

None of this leaves the device except to reach Plex, as described below.
Signing in again replaces the tokens. Removing the Siskin account from the
car's settings, or signing out inside the app, deletes the account token and
any server access token. Clearing the app's data deletes everything else listed
here, including the install identifier.

## What Siskin sends, and to whom

Siskin contacts two kinds of destination, and no others:

- **plex.tv**, to sign in with Plex's PIN linking flow and to find the servers
  your account can reach. Every request includes the install identifier
  described above, the car's make and model, and, once you're signed in, your
  account token.
- **Your Plex server**, to browse your library, stream audio, report playback
  progress so your listening history and resume positions stay correct, and
  save a star rating when you set one. Search terms you enter go here too.
  This server is run by you or someone you know. It doesn't belong to the
  developer or to Plex.

**Plex Inc. is the only third party involved.** Signing in sends your Plex
account credentials to Plex. What Plex does with the data that reaches plex.tv
is covered by Plex's privacy policy, not this one.

Siskin talks to no other company. There's no advertising network, analytics
vendor, crash reporter, or data broker in this app, and nothing is ever sent to
the developer.

## How this maps to the Play Store's Data safety label

Google Play defines "collected" as *sent off the device*, no matter who
receives it, and "shared" as *transferred to another company*. Those aren't the
everyday meanings of the words, so the label is easy to misread. Siskin's
declaration says:

| Data type | Collected | Shared | Why |
|---|---|---|---|
| Device or other IDs | Yes | Yes | The install identifier reaches plex.tv on every request. Plex is another company, so it counts as both. |

It's declared for app functionality and account management, and nothing else.
It isn't used for advertising, marketing, personalization, fraud scoring, or
analytics, and it isn't sold.

Nothing is listed against the developer, because nothing reaches the developer.

## Permissions

Siskin requests only what playback needs:

| Permission | Why |
|---|---|
| Internet | to reach plex.tv and your Plex server |
| Foreground service, media playback | to keep playing while another app is on screen |
| Wake lock | to keep playback running |
| Notifications | for the playback notification the system requires |
| Car information | to read the make, model and year, so your Plex device list names this car |

Siskin doesn't request access to your location, contacts, microphone, camera,
or files, and can't read them.

## Children

Siskin isn't directed at children, and nothing described above is collected,
profiled, or treated differently by age. Using the app at all requires a Plex
account and a Plex server.

## Changes to this policy

If Siskin's behavior ever changes (for example, if an option to upload
diagnostic logs is added to help debug problems), this policy will be updated
and the date at the top changed **before** that feature ships. The app's Data
safety declaration on Google Play will be updated to match.

## Contact

Questions or concerns: <https://github.com/codingismy11to7/siskin/issues>

## About this document

Siskin is not affiliated with, endorsed by, or sponsored by Plex Inc. "Plex" is
a trademark of its respective owner and is used here only to describe what the
app connects to.
