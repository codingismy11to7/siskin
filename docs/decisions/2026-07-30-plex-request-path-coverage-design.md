# Assert every Plex request path

**Date:** 2026-07-30
**Status:** Approved
**Issue:** #16

## Context

The Plex API layer carries its request paths as string literals in Retrofit
annotations. Nothing asserts them, so a typo compiles, passes the whole suite,
and **fails open** against a real server.

Failing open is what makes this worth fixing rather than noting. Plex answers
HTTP 200 for a request whose query parameters it does not recognise; the app's
own narrowing then yields an empty list; and the browse layer correctly treats an
empty list as "an empty library" rather than an error. The symptom is a silently
empty tab. No test fails, no log line looks wrong.

Both known instances were found by probing a live server, never by a test:

- `library/metadata/{id}/similar` — the path Plex's own web client uses — 404s.
  `.../nearest` is the one that answers.
- `librarySectionID` is accepted with 200 and **silently ignored** when filtering
  playlists. `sectionID` is the parameter that actually filters.

### What is covered today

Less than #16 assumed when it was filed, and in different places.

`AuthClientTest` and `LibraryClientTest` exist but are **not HTTP tests** — they
cover static helpers (`musicSections`, `mediaServers`, `expiresAtEpochSeconds`,
`CreatedPin.from`) and never issue a request. There is no client-level HTTP
harness to extend.

Exactly two path assertions exist in the whole suite, both added incidentally by
the car UI design sweep, and both for the same endpoint:

```kotlin
assertEquals("/library/sections/1/all", request.requestUrl?.encodedPath)
```

Query parameters fare better — `rating`, `key`, `time`, `ratingKey`, `state` and
`type` are asserted across four test classes. **13 of 14 endpoint paths have no
assertion at all**, and neither base URL is pinned either.

## Decisions

### One test class per service interface

Three new classes, beside the interfaces they cover:

| Class | Service | Endpoints |
|---|---|---|
| `plex/api/auth/AuthServiceTest` | `AuthService` (plex.tv) | `createPin`, `getPin`, `getResources` |
| `plex/api/library/LibraryServiceTest` | `LibraryService` | `getSections`, `getSectionContent`, `getChildren`, `getNearest`, `getMetadata`, `getSectionHubs` |
| `plex/api/search/SearchServiceTest` | `SearchService` | `search`, `getPlaylists`, `getPlaylistItems`, `reportProgress`, `rate` |

Not spread across the existing repository and service tests, which is what #16
originally suggested. Those tests answer "did the repository ask for the right
thing?" — a different question from "does the annotation say what we think?", and
one that happens to travel through the same request. Keeping the contract in one
place per interface means a reader checking a path looks in one file, and the
completeness guard below has something to be complete *about*.

### What each endpoint asserts

The resolved request, not the literal:

- **`encodedPath`** — covers `{sectionId}` substitution and the base-URL join.
- **Every `@Query` name**, including the defaults callers never pass:
  `includeHttps=1`, `includeRelay=1`, `playlistType=audio`.
- **`X-Plex-Container-Start` / `-Size`** on the three paged endpoints
  (`getSectionContent`, `getChildren`, `getPlaylistItems`). These are headers
  rather than query parameters, which is itself the sort of thing a typo would
  quietly undo.
- **The HTTP method.** Load-bearing on `:/timeline` and `:/rate`, which Plex
  serves as writes over GET, and on `createPin`, the one POST.

The single most valuable assertion is that `:/timeline` resolves to
`/:/timeline`. A relative URL beginning with a colon is not something a reader
would predict from the annotation, and nothing currently states the result.

`getSections`'s trailing slash is asserted for the same reason: its KDoc says the
slash is Plex's canonical form and deliberate, so the test is what stops a later
tidy-up from dropping it.

### A reflection guard against new endpoints

Each class carries one test that reads the interface's `@GET`/`@POST`
annotations and asserts the set of covered method names:

```kotlin
@Test
fun everyEndpointIsCovered() {
    assertEquals(
        setOf("getSections", "getSectionContent", "getChildren",
              "getNearest", "getMetadata", "getSectionHubs"),
        annotatedMethods(LibraryService::class.java)
    )
}
```

Roughly ten lines each, and the reason the work is worth doing once rather than
twice. Without it, a fifteenth endpoint arrives with no test and nothing notices
— which is precisely how the current gap formed. With it, adding an endpoint
fails the suite until it has a row.

### Bare Retrofit, not the factory

Every class builds its own Retrofit against MockWebServer:

```kotlin
Retrofit.Builder()
    .baseUrl(server.url("/"))
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(LibraryService::class.java)
```

This is forced for `AuthService`: `PlexRetrofitFactory.plexTv()` hardcodes
`https://plex.tv/api/v2/`, so an AuthService built through the factory cannot be
pointed at a mock server at all. Using bare Retrofit for the other two as well
keeps the three classes identical in shape and keeps Robolectric out — none of
them needs a `Context`, so they run as plain JVM tests.

The cost is that the factory's own base-URL handling goes uncovered by these
classes, which the next decision addresses directly rather than leaving implied.

### Two base-URL assertions

The base URL is the other half of every request and is currently as unpinned as
the paths were. `PlexRetrofitFactoryTest` covers connection pooling, the
per-client identity interceptors and the shared timeouts — not URLs.

Two assertions, added to `PlexRetrofitFactoryTest` where the rest of the
factory's behaviour already lives:

- `plexTv(...)` yields `https://plex.tv/api/v2/`. A fixed literal with the same
  failure mode as a path.
- `server(...)` normalizes `https://10.0.0.5:32400` to a trailing slash. Retrofit
  throws without one, so this is load-bearing for every server that advertises a
  connection without it.

## What this does and does not buy

These tests cannot tell anyone what Plex's real path is. Only a live server can,
which is how both known bugs were actually found and why the interfaces carry
KDoc recording what was probed against PMS 1.43.3.

What they buy is that a path becomes a **two-place change**. Today a single
character in an annotation ships a silently empty tab; afterwards it fails a test
by name. That is protection against typos and careless edits, not against a wrong
belief about the API — and it is worth being clear that the second risk remains.

Test names carry the findings that cost real time, so the reason sits next to the
assertion: `nearestNotSimilar`, `sectionIdNotLibrarySectionId`.

## Scope

**In:** the three service test classes, their reflection guards, and the two
base-URL assertions in `PlexRetrofitFactoryTest`.

**Out:**

- **Any production change.** This is a test-only branch. If a path turns out to
  be wrong, that is a separate fix with its own reasoning.
- **The two existing path assertions in `PlexBrowseRepositoryTest`.** They stay.
  They assert the repository narrowed by `artist.id` correctly, which is a claim
  about the repository, not about the annotation.
- **Response-body parsing.** Covered by `PlexModelsTest` and the mapper tests.
  These classes enqueue the smallest body each call will accept and assert
  nothing about what comes back.
- **Generating the layer from the community OpenAPI spec.** Considered and
  rejected in `2026-07-27-plex-api-layer-design.md`; nothing here reopens it.

## Verification

`nix develop --command ./gradlew testDebugUnitTest` — 14 endpoint tests, 3
reflection guards and 2 base-URL assertions on top of the existing suite, all
green.

The guards are verifiable in the direction that matters: deleting a row from the
covered set must fail, and so must adding a method to an interface without a
test. Both are worth performing once by hand during implementation rather than
assumed, since a guard that cannot fail is worse than no guard.

Every endpoint assertion should also be checked against the real annotation by
reading, not by copying: a test written by pasting the path out of the interface
asserts only that copy-paste works. The paths in this spec's tables were read off
the interfaces at authoring time and are the values to use.

No production code changes, so `assembleDebug` and the lint baseline are
unaffected.
