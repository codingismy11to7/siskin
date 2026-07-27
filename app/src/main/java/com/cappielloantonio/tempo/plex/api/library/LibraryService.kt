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
 * query parameters, which maps directly onto media3's onGetChildren(page, pageSize).
 */
interface LibraryService {

    /** Trailing slash is required; /library/sections without it redirects. */
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
    fun getChildren(@Path("id") ratingKey: String): Call<PlexResponse>

    /**
     * One item's own details. Plex names the parameter {ids} because it accepts a
     * comma-separated batch; a single ratingKey is the common case.
     */
    @GET("library/metadata/{ids}")
    fun getMetadata(@Path("ids") ratingKeys: String): Call<PlexResponse>

    @GET("hubs/sections/{sectionId}")
    fun getSectionHubs(@Path("sectionId") sectionId: String): Call<PlexResponse>
}
