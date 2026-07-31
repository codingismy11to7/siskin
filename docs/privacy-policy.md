# Siskin Privacy Policy

**Last updated:** 31 July 2026

Siskin is a music player for Android Automotive OS that plays a Plex music
library on a car's head unit.

## What Siskin collects

Nothing.

The developer receives no data from this app. There is no analytics, no crash
reporting, no telemetry, and no usage measurement of any kind. The app contains
no third-party analytics or advertising libraries.

## What Siskin stores on your device

To stay connected to your Plex account and server between drives, Siskin keeps
the following in its own private storage on the head unit:

- your Plex account token
- the address of the Plex server you chose
- a server access token, where the server issues one
- the identifier of the music library you selected

These never leave the device except to reach Plex, as described below. Signing
in again replaces them; clearing the app's data removes them.

## What Siskin transmits, and to whom

Siskin contacts two kinds of destination, and no others:

- **plex.tv** — to sign in using Plex's PIN linking flow, and to find out which
  servers your account can reach.
- **your Plex server** — to browse your library, stream audio, and report
  playback progress so that your listening history and resume positions stay
  correct.

Both are Plex's own service or a machine you control. What Plex does with data
you send to Plex is governed by Plex's privacy policy, not this one.

Siskin transmits nothing to the developer, and nothing to any third party.

## Permissions

Siskin requests only what playback requires:

| Permission | Why |
|---|---|
| Internet | to reach plex.tv and your Plex server |
| Foreground service, media playback | to keep playing while another app is on screen |
| Wake lock | to keep playback running |
| Notifications | for the playback notification the system requires |

Siskin does not request access to location, contacts, microphone, camera, or
your files, and cannot read them.

## Children

Siskin is not directed at children. It collects no data from anyone, of any age.

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
