package com.cappielloantonio.tempo.plex.api.search

import com.cappielloantonio.tempo.plex.base.PlexResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Search, playlists and playback reporting on the media server.
 *
 * Search is section-scoped rather than global: the browse tree already knows
 * which music section it is in, and scoping stops video results the app cannot
 * play from coming back.
 */
interface SearchService {

    @GET("library/sections/{sectionId}/search")
    fun search(
        @Path("sectionId") sectionId: String,
        @Query("query") query: String,
        @Query("limit") limit: Int
    ): Call<PlexResponse>

    @GET("playlists")
    fun getPlaylists(@Query("playlistType") playlistType: String = "audio"): Call<PlexResponse>

    /**
     * The playlist's tracks. Plex also exposes GET playlists/{playlistId} for a
     * playlist's own metadata, deliberately not wrapped here: browsing needs the
     * list and then the items, never the metadata alone.
     */
    @GET("playlists/{playlistId}/items")
    fun getPlaylistItems(@Path("playlistId") playlistId: String): Call<PlexResponse>

    /** Plex expects a GET despite this being a write; it returns an empty body. */
    @GET(":/timeline")
    fun reportProgress(
        @Query("ratingKey") ratingKey: String,
        @Query("key") key: String,
        @Query("state") state: String,
        @Query("time") timeMs: Long
    ): Call<Void>
}
