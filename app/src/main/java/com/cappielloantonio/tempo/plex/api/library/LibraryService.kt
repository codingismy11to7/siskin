package com.cappielloantonio.tempo.plex.api.library

import com.cappielloantonio.tempo.plex.base.PlexResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Plex Media Server library endpoints. Every response is MediaContainer-wrapped.
 *
 * Paging is expressed through X-Plex-Container-Start / -Size headers rather than
 * query parameters, on the endpoints that return a list: getSectionContent,
 * getChildren, getFirstCharacterContent and getByPath. Not for media3's benefit
 * -- measured on the AAOS API 33 emulator, onGetChildren always arrives as
 * page=0, pageSize=Integer.MAX_VALUE, even scrolled to a list's true end, so
 * the car never actually pages a node (see
 * docs/decisions/2026-08-09-browse-list-windowing-design.md). These headers exist
 * for the browse tree's grouped nodes instead: PlexBrowseRepository's windowed
 * nodes fetch one WINDOW_SIZE-item slice per window and one single-item slice
 * per boundary label, and getFirstCharacterContent fetches one bucket, all on
 * Start/Size. getByPath is not windowed the same way -- a hub's followed key is
 * fetched once, in full, capped at MAX_ITEMS rather than sliced -- but it rides
 * the same mechanism; see its own KDoc for why that bound is load-bearing
 * rather than decorative. getSections, getMetadata, getSectionHubs, getDecades
 * and getFirstCharacters return bounded results and take no paging.
 *
 * The OpenAPI spec does not document these headers on these operations -- container
 * paging is a general Plex mechanism rather than a per-endpoint one -- so they were
 * verified directly against PMS 1.43.3: all four endpoints honour Start/Size and
 * return size, totalSize and offset.
 *
 * A non-2xx still throws `HttpException` here -- Retrofit's behaviour, unchanged
 * -- but no call site sees that any more. `LibraryClient` wraps every call in
 * `plexCall`, which catches it and returns `Either<PlexTransportFailure, T>`; a
 * 401 from a stale token arrives as `PlexTransportFailure.Http(Server, 401)`,
 * and that is what now keeps "the server said no" apart from "I could not
 * reach it" at the call site.
 */
interface LibraryService {

    /**
     * The trailing slash is Plex's canonical form and is kept for that reason, not
     * because it is required -- verified against PMS 1.43.3, where
     * /library/sections answers 200 with no redirect either way.
     */
    @GET("library/sections/")
    suspend fun getSections(): PlexResponse

    /**
     * `artistId` narrows an album listing to one artist, and is the only way this
     * app asks for an artist's albums -- see [getChildren] for why the obvious
     * endpoint is not used.
     *
     * There are **two** decade parameters and they are not interchangeable.
     *
     * [trackDecade] narrows a *track* listing and is spelled `album.decade`,
     * for the same reason `artistId` is spelled `artist.id`: it filters on a
     * related item's field, not the track's own. [albumDecade] narrows an
     * *album* listing and is spelled plain `decade`, because an album's decade
     * is its own field. The server states that spelling itself, in the
     * `fastKey` it returns on every entry of the decade index:
     * `/library/sections/4/all?decade=1980&type=9`.
     *
     * Getting either wrong is silent. Measured against PMS 1.43.3,
     * `type=10&decade=1980` answers 200 with an empty container, as does
     * `type=10&year=1985`; only `type=10&album.decade=1980` returns tracks.
     * What a caller sees is an empty list, which reads as an empty library
     * rather than as a malformed request -- and for the composite artwork that
     * feeds off the album form, as artwork that quietly never appears.
     *
     * [albumId] and [artistId] both accept a comma-separated list, which is
     * how a Discover Mix turns the containers on screen into tracks in one
     * request. Measured against PMS 1.43.3: 500 album ids in a 3,499-character
     * URL answered 200 with 4,801 tracks.
     */
    @GET("library/sections/{sectionId}/all")
    suspend fun getSectionContent(
        @Path("sectionId") sectionId: String,
        @Query("type") type: Int,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int,
        @Query("sort") sort: String?,
        @Query("artist.id") artistId: String?,
        @Query("album.decade") trackDecade: String?,
        @Query("decade") albumDecade: String?,
        @Query("album.id") albumId: String?
    ): PlexResponse

    /**
     * Album -> its tracks.
     *
     * **Not artist -> its albums**, although the endpoint nominally serves that
     * too. Measured against PMS 1.43.3, an artist's children listing silently
     * omits albums: five of the first twelve artists in a real library reported
     * zero children while each owning one album, and another returned 14 of its
     * 17. Album -> tracks is sound on the same server -- every album's child
     * count matched its own leafCount -- so only the artist direction moved to
     * [getSectionContent] with an `artist.id` filter.
     */
    @GET("library/metadata/{id}/children")
    suspend fun getChildren(
        @Path("id") ratingKey: String,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int
    ): PlexResponse

    /**
     * Tracks Plex considers similar to this one.
     *
     * Verified against PMS 1.43.3: `library/metadata/{id}/similar` -- the name
     * used by Plex's own web UI networking calls -- 404s. This path,
     * `.../nearest`, is the one that actually answers: 200 with real sonic
     * matches on a library that has been analyzed, and 200 with an *empty*
     * container (never an error) on one that has not. That is why the caller
     * treats "no results" as an ordinary outcome and falls back to random,
     * rather than treating it as a failure.
     *
     * Whether a library has been analyzed is not exposed as a section-level
     * flag; the detectable signal is per-track: a `Metadata` carries a
     * `musicAnalysisVersion` field once its library has run sonic analysis,
     * and omits it otherwise. That took live probing to find and is recorded
     * here rather than assumed.
     */
    @GET("library/metadata/{id}/nearest")
    suspend fun getNearest(
        @Path("id") ratingKey: String,
        @Query("limit") limit: Int
    ): PlexResponse

    /**
     * One item's own details. Plex names the parameter {ids} because it accepts a
     * comma-separated batch; a single ratingKey is the common case.
     */
    @GET("library/metadata/{ids}")
    suspend fun getMetadata(@Path("ids") ratingKeys: String): PlexResponse

    @GET("hubs/sections/{sectionId}")
    suspend fun getSectionHubs(@Path("sectionId") sectionId: String): PlexResponse

    /**
     * The decades this section's albums fall into, newest first.
     *
     * `type` must be 9 (album). A music section exposes a decade filter for
     * albums only -- measured against PMS 1.43.3, where `filters?type=9` lists
     * genre, mood, style, year, decade, studio, format, subformat, collection
     * and unmatched, while `filters?type=10` lists only mood, genre, userRating
     * and audioCodec. Asking for the decades of *tracks* is not an error, it is
     * an empty answer.
     *
     * Returns `Directory` entries, not `Metadata`, and each carries only
     * `fastKey`, `key` and `title` -- no `type`, and no artwork of any kind.
     * `key` is the decade's first year ("1980"); `title` is already formatted
     * for display ("1980s"), so nothing here needs localising or deriving.
     * Decades with no albums are simply absent, so there are no gaps to filter.
     *
     * Takes no paging: a library spans a handful of decades and the response is
     * bounded by that.
     */
    @GET("library/sections/{sectionId}/decade")
    suspend fun getDecades(
        @Path("sectionId") sectionId: String,
        @Query("type") type: Int
    ): PlexResponse

    /**
     * The first-character buckets this section's artists fall into -- Plex's own
     * "By First Letter" index.
     *
     * Returns `Directory` entries carrying only `size`, `key` and `title`: no
     * `type`, no artwork, and -- unlike the decade index -- **no `fastKey`**, so
     * there is no server-supplied query to reproduce and the bucket has to be
     * addressed by [getFirstCharacterContent]'s path instead.
     *
     * `title` is already display-ready ("A", "#", "∆") and needs no localising.
     * `key` is what addresses the bucket and is *not* the same string: it is
     * "%23" where the title is "#".
     *
     * Measured against PMS 1.43.3 on a 1204-artist library: 27 buckets in 1303
     * bytes, and the counts sum to exactly the section's totalSize. **Empty
     * letters are omitted** -- that library has no `X` bucket -- so nothing may
     * assume 27, index by letter, or expect a fixed set. `∆` gets a bucket of
     * its own, so the set is not "`#` plus the alphabet" either.
     *
     * Takes no paging, for the reason [getDecades] takes none: the response is
     * bounded by the number of distinct initials a library can have.
     */
    @GET("library/sections/{sectionId}/firstCharacter")
    suspend fun getFirstCharacters(
        @Path("sectionId") sectionId: String,
        @Query("type") type: Int
    ): PlexResponse

    /**
     * One first-character bucket's items.
     *
     * **The bucket is a path segment, not a query, and getting that wrong is
     * silent.** Measured against PMS 1.43.3:
     * `/library/sections/4/all?type=8&firstCharacter=D` answers **200 with
     * totalSize=1204** -- the entire library, 5.19 MB -- while
     * `/library/sections/4/firstCharacter/D` answers 200 with totalSize=76,
     * matching the index's count for D. A caller reaching for the query form
     * sees a full, correctly-formed artist list under a heading reading "D" and
     * no error anywhere.
     *
     * [key] is spliced in with `encoded = true` because it arrives already
     * percent-encoded: the symbol bucket's key is "%23", and letting Retrofit
     * encode it again sends "%2523" -- a bucket that does not exist, answered as
     * an empty list. Non-ASCII keys like "∆" arrive raw and OkHttp encodes them.
     *
     * **No `sort` parameter, deliberately.** Membership is decided by
     * `titleSort`, so ordering the contents by anything else scrambles them:
     * measured on bucket D, `sort=title` opens on "Arne Domnérus, Bob Dylan,
     * Brigitte DeMeyer, Carl Craig" -- a list labelled D running A, B, B, C.
     * This is the opposite call from the windowed browse nodes, which must force
     * `sort=title` because a window is named after the item at its edge; see
     * docs/decisions/2026-08-10-artists-by-initial-design.md.
     *
     * `type` is honoured here and changes the answer -- on key "Q", `type=9`
     * returns 3 albums where no type returns 4 artists -- so it is always passed
     * rather than left to a music section's default.
     */
    @GET("library/sections/{sectionId}/firstCharacter/{key}")
    suspend fun getFirstCharacterContent(
        @Path("sectionId") sectionId: String,
        @Path("key", encoded = true) key: String,
        @Query("type") type: Int,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int
    ): PlexResponse

    /**
     * Follows a server-supplied path verbatim -- a hub's `key`, which is the
     * only way to open a hub whose parameters were rolled server-side.
     *
     * **Callers must pass [LibraryClient.isSafeHubKey] first.** `@Url` accepts
     * an absolute URL and would happily send this client's `X-Plex-Token` to
     * another host, because PlexRetrofitFactory's interceptor attaches it to
     * every request without inspecting the target.
     *
     * Keys carry comparison operators -- `viewCount>=50`,
     * `lastViewedAt<=-5mon` -- so the string is handed over whole rather than
     * decomposed into @Query parameters, which would re-encode them. This is
     * the same hazard [getFirstCharacterContent] documents, where "%23"
     * re-encoded to "%2523" and answered 200 with an empty list.
     *
     * **This was the one node in the whole browse tree with no cap, and a
     * followed key can return everything a library holds.** Measured against
     * PMS 1.43.3: the "Recently Added in Music" hub's key,
     * `/library/sections/7/all?type=9&sort=addedAt:desc`, answers with
     * **1,322 albums** on a library that size -- roughly a megabyte of JSON
     * parsed and Binder-transferred on a head unit, of which
     * `Constants.WINDOW_SIZE`'s KDoc records the car keeps only the first
     * ~227KB and silently drops the rest. `LibraryClient.getByHubKey` sends
     * `X-Plex-Container-Size: 500` for exactly this reason, and the same
     * server honours it on both key shapes a hub can carry: the followed-key
     * form above comes back `size=500, totalSize=1322`, and the other shape --
     * the hub endpoint itself, `/hubs/sections/7/popular?monthsAgo=4` -- comes
     * back `size=35, totalSize=35`, the header honoured even though nothing
     * needed trimming. `PlexBrowseRepository.containersOf` caps at
     * `Constants.MAX_ITEMS` independently of this header, the same
     * belt-and-suspenders every other bounded node in this file gets, in case
     * a server ever answers without honouring it.
     */
    @GET
    suspend fun getByPath(
        @Url path: String,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int
    ): PlexResponse
}
