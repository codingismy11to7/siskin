# Plex API client layer

**Date:** 2026-07-27
**Status:** Approved

## Context

Siskin is being converted from a Subsonic client to a **Plex** client. This
document covers only the first piece: the HTTP layer that talks to Plex. Browse
tree mapping, playback, the QR sign-in screen and Subsonic removal are each their
own spec.

The pivot is why the sign-in flow shipped in PR #4 is not wasted despite taking
five minutes to fill in on a head unit. Plex authenticates through a PIN flow — a
code appears on screen, the user approves it on a phone — so nothing long is ever
typed in a car.

## The library question, answered

The obvious first move is to take an existing Java Plex client off the shelf.
There isn't a usable one.

| Candidate | Outcome |
|---|---|
| `dev.plexapi:plexapi` ([LukasParke/plexjava](https://github.com/LukasParke/plexjava)) | MIT, Maven Central, Speakeasy-generated, covers the OAuth PIN flow *and* music endpoints — and **cannot run on Android** |
| [kekolab/javaplex](https://github.com/kekolab/javaplex) | **No license stated** (disqualifying for GPL v3), jitpack-only, Apache HttpClient, author states they will break compatibility between majors |
| [Derkades/Plex4J](https://github.com/Derkades/Plex4J) | MIT but **archived Feb 2021**, token-only auth, published nowhere |
| nitind / jarrettv `plexapp-client` | Google TV era; that platform is long dead |

`dev.plexapi:plexapi` is the one worth explaining, because it looks perfect until
you read the source. Its `HTTPClient` interface is:

```java
HttpResponse<InputStream> send(HttpRequest request)
CompletableFuture<HttpResponse<Blob>> sendAsync(HttpRequest request)
```

Those are `java.net.http` types. Android has no `java.net.http` package at **any**
API level, and core library desugaring does not supply it. The SDK's documented
"bring your own HTTP client" escape hatch does not help: the interface you would
implement is itself unresolvable. This is a hard block, not a workaround.

### What *is* reusable: the spec

[LukeHagar/plex-api-spec](https://github.com/LukeHagar/plex-api-spec) is an
**MIT-licensed** community OpenAPI specification for the Plex API — the source
from which the Java SDK above and seven other language SDKs are generated. MIT is
GPL v3-compatible, so it is clean for Siskin.

Measured rather than assumed: 2.5 MB, 63,028 lines, 343 paths, **404 operations**
across 34 tags. Responses are modelled as JSON (380 JSON vs 4 XML), `MediaContainer`
appears 579 times, and only 16 schemas use `additionalProperties: true`. It is
detailed, not a stub. It covers both halves this project needs — `createOAuthPin`,
`getOAuthPin`, `linkOAuthPin`, `getServerResources` for auth and discovery, and the
library operations for browsing.

## Decision: hand-roll a lean layer, treat the spec as documentation

Siskin is a car music player and will remain one. A music client needs **twelve of
those 404 operations** — about 3%.

Tag filtering does not rescue a generated approach: `Library` alone holds 126
operations and that is exactly where the nine server-side calls live, so generating
by tag still yields ~200.

The decisive argument runs opposite to the usual case for codegen. Gson ignores
unknown JSON fields, so hand-written models carry only the fields actually read —
roughly eight per type, against forty in a generated one. Hand-rolling produces a
*smaller* result here, not merely a cheaper-to-obtain one. It also matches the
existing `subsonic/` layer, which a reviewer already knows how to read.

Rejected alternatives:

- **Generate everything.** 404 operations and several hundred model classes for the
  twelve we call, on an app already shipping ABI splits — plus keep rules for
  reflective deserialization and generated-code quality against a 63k-line spec.
- **Pre-filter the spec, then generate.** Machine-accurate models at a workable
  size, but it needs a filter that prunes operations *and their transitively
  referenced schemas*, plus generator integration and re-running on spec updates.
  Its advantage is cheap growth, which "music only, indefinitely" removes.

## Module layout

Mirrors `subsonic/`, including the `*Client` / `*Service` split per area.

```
plex/
  PlexApi.kt              config holder: token, server URI, client identity
  PlexRetrofitFactory.kt  builds the two Retrofit instances
  base/MediaContainer.kt  the universal response wrapper
  models/                 Metadata, Media, Part, Directory, Pin, Resource, Connection
  api/auth/               AuthClient + AuthService      (plex.tv)
  api/library/            LibraryClient + LibraryService (server)
  api/search/             SearchClient + SearchService   (server)
  api/media/              MediaUrlBuilder — URL construction only, issues no calls
```

Eight model types, four service interfaces, **twelve Retrofit calls** plus two
URL builders.

**plex.tv (3):** `POST /pins`, `GET /pins/{id}`, `GET /api/v2/resources`

**Server (9):** `GET /library/sections`, `GET /library/sections/{key}/all`,
`GET /library/metadata/{ratingKey}`, `GET /library/metadata/{ratingKey}/children`,
`GET /hubs/sections/{key}`, `GET /library/sections/{key}/search`, `GET /playlists`,
`GET /playlists/{id}/items`, `GET /:/timeline`.

**Not Retrofit calls (2):** streaming and artwork are URL construction. ExoPlayer
and the artwork `ContentProvider` are handed a URL rather than a response body, so
`MediaUrlBuilder` assembles one from a `Part.key` or a `thumb` plus the current
token. These are the two pure functions worth unit-testing.

Section-scoped search is chosen over the global `GET /search` because the browse
tree already knows which music section it is in, and scoping avoids returning
video results the app cannot play.

## Two Retrofit instances

The one genuinely new structure versus Subsonic, which assumes a single configured
base URL.

`plex.tv` is fixed and known at startup. The server's base URL is **discovered
after authentication** and changes when the user switches servers. So: one
long-lived plex.tv instance, one rebuildable server instance, sharing an OkHttp
client.

A single OkHttp interceptor attaches identity to every request —
`X-Plex-Client-Identifier` (a stable UUID generated once and persisted),
`X-Plex-Product`, `X-Plex-Version`, `X-Plex-Platform`, `X-Plex-Device`,
`X-Plex-Model` — plus `X-Plex-Token` when one exists. Plex requires these; the spec
declares all of them.

The rejected alternative is one Retrofit instance with per-call `@Url`. Retrofit
supports it, but it pushes base-URL assembly into every call site and loses the
compile-time distinction between "this is a plex.tv call" and "this is a server
call" — a distinction that matters because only one of them works before sign-in.

## Auth and token lifecycle

`POST /pins?strong=true` returns an id and a short code; the code becomes the QR
payload. Poll `GET /pins/{id}` until `authToken` is present, then
`GET /api/v2/resources?includeHttps=1` to discover servers and select a connection
URI. Token and URI persist where the Subsonic credentials live today.

The PIN-flow states — created, pending, authorised, expired — are modelled as a
pure function over poll responses so they can be unit-tested without a network.

## What carries over from the sign-in flow

Unchanged by the pivot: `CarSignInResolution` still returns the browse error
carrying a resolution `PendingIntent`, `CarSignInActivity` still hosts the screen,
`LoginHost` is still the seam, and `BrowseTreeInvalidator` still refreshes the tree
once auth succeeds. Only the activity's *contents* change, from a form to a QR code
and a poll loop.

## Two things Plex makes simpler

**Error handling.** Plex uses real HTTP status codes; an expired or invalid token
is a 401. Subsonic's habit of reporting a bad password as HTTP 200 with an error
payload is what forced `SystemRepository.isRejection` and the lazy classification
design. Against Plex, "were we rejected?" is `response.code() == 401`.

**Paging.** The spec declares `X-Plex-Container-Start` and `X-Plex-Container-Size`,
so the server pages natively — which maps directly onto media3's
`onGetChildren(page, pageSize)`. The Subsonic layer has no equivalent.

## Testing

`unitTests.returnDefaultValues = true` (`app/build.gradle:34`) makes any
framework-touching unit test pass while asserting nothing, so only pure functions
are worth testing here:

- `MediaUrlBuilder` — artwork and stream URL construction, including token
  handling and escaping.
- The PIN-flow state machine — as a function over poll responses.

Model deserialization is exercised through those paths and by the layers built on
top of this one; it is not separately unit-tested, because a Gson round-trip test
under `returnDefaultValues` verifies little.

A committed trimmed spec with a path-existence test was considered and rejected as
overhead for twelve endpoints.

## Scope

**In:** the HTTP layer — Retrofit services, models, the two-instance split, the
identity interceptor, URL construction, the PIN state machine.

**Out, each its own spec:** browse-tree mapping onto Plex libraries; ExoPlayer
playback integration; the QR sign-in screen; removal of the Subsonic layer and the
features with no Plex analogue (podcasts, internet radio, sharing, bookmarks).

This layer can be built and unit-tested with nothing else in the app changed.

## Licensing note

Phoebe — the author's other project — is already a Plex/Jellyfin AAOS client, but
it is a fork of an **unlicensed** repository, while Siskin is GPL v3 via tempo.
Earlier sessions deliberately read only Phoebe's PR *descriptions* when carrying
findings across, never its source. With both projects now targeting Plex, the same
problems will keep appearing in both and the pull toward reuse gets stronger. That
discipline has to hold: transfer findings, never source.
