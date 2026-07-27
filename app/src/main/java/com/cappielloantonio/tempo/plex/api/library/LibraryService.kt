package com.cappielloantonio.tempo.plex.api.library

import com.cappielloantonio.tempo.plex.base.PlexResponse
import retrofit2.Call
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
 */
interface LibraryService {

    /**
     * The trailing slash is Plex's canonical form and is kept for that reason, not
     * because it is required -- verified against PMS 1.43.3, where
     * /library/sections answers 200 with no redirect either way.
     */
    @GET("library/sections/")
    fun getSections(): Call<PlexResponse>

    @GET("library/sections/{sectionId}/all")
    fun getSectionContent(
        @Path("sectionId") sectionId: String,
        @Query("type") type: Int,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int
    ): Call<PlexResponse>

    /** Album -> its tracks, artist -> its albums. */
    @GET("library/metadata/{id}/children")
    fun getChildren(
        @Path("id") ratingKey: String,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int
    ): Call<PlexResponse>

    /**
     * One item's own details. Plex names the parameter {ids} because it accepts a
     * comma-separated batch; a single ratingKey is the common case.
     */
    @GET("library/metadata/{ids}")
    fun getMetadata(@Path("ids") ratingKeys: String): Call<PlexResponse>

    @GET("hubs/sections/{sectionId}")
    fun getSectionHubs(@Path("sectionId") sectionId: String): Call<PlexResponse>
}
