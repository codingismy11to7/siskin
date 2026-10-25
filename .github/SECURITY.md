# Security policy

## Reporting a vulnerability

Report it privately through GitHub at
<https://github.com/codingismy11to7/siskin/security/advisories/new>, which is
**Report a vulnerability** on this repository's **Security** tab. Please don't
open a public issue.

Reports are handled by one maintainer in their spare time, so expect a reply
within a couple of weeks rather than days.

## Supported versions

Only the latest release on Google Play gets fixes. There are no backports.

## Scope

Siskin talks to plex.tv and to your own Plex Media Server. Problems in either of
those belong with Plex, not here. In scope is anything Siskin itself does that
exposes your Plex credentials or your server: how it stores tokens, which hosts
it sends them to, and what it accepts from a server it connects to.
