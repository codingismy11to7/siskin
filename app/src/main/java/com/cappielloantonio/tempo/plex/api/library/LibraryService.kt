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
 * getChildren. media3 calls onGetChildren(page, pageSize) for every browsable
 * node, so both need it. getSections, getMetadata and getSectionHubs return
 * bounded results and take no paging.
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

    @GET("library/sections/{sectionId}/all")
    suspend fun getSectionContent(
        @Path("sectionId") sectionId: String,
        @Query("type") type: Int,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int,
        @Query("sort") sort: String?
    ): PlexResponse

    /** Album -> its tracks, artist -> its albums. */
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
}
