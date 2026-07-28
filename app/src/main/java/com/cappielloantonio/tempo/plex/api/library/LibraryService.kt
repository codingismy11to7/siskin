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
 * A non-2xx throws `HttpException`. A 401 from a stale token is therefore not
 * something a caller can mistake for a server with no libraries.
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
     * Tracks Plex considers similar to this one. Depends on Plex Pass sonic
     * analysis: without it the server answers with an empty container rather
     * than an error, which is why the caller treats "no results" as an ordinary
     * outcome and falls back to random.
     */
    @GET("library/metadata/{id}/similar")
    suspend fun getSimilar(
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
