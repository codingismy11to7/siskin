package com.cappielloantonio.tempo.plex.api.library

import com.cappielloantonio.tempo.plex.base.PlexResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Plex Media Server library endpoints. Every response is MediaContainer-wrapped.
 *
 * Paging is expressed through X-Plex-Container-Start / -Size headers rather than
 * query parameters, on the endpoints that return a list: getSectionContent and
 * getChildren. Not for media3's benefit -- measured on the AAOS API 33 emulator,
 * onGetChildren always arrives as page=0, pageSize=Integer.MAX_VALUE, even
 * scrolled to a list's true end, so the car never actually pages a node (see
 * docs/decisions/2026-08-09-browse-list-windowing-design.md). These headers
 * exist for the windowed browse tree instead: PlexBrowseRepository's windowed
 * browse nodes fetch one WINDOW_SIZE-item slice per window and one single-item
 * slice per boundary label, and both ride Start/Size. getSections, getMetadata
 * and getSectionHubs return bounded results and take no paging.
 *
 * The OpenAPI spec does not document these headers on these operations -- container
 * paging is a general Plex mechanism rather than a per-endpoint one -- so they were
 * verified directly against PMS 1.43.3: both endpoints honour Start/Size and return
 * size, totalSize and offset.
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
        @Query("decade") albumDecade: String?
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
}
