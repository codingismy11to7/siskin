package com.cappielloantonio.tempo.plex.api.search

import com.cappielloantonio.tempo.plex.base.PlexResponse
import retrofit2.http.GET
import retrofit2.http.Header
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

    /**
     * [type] is REQUIRED -- Plex answers HTTP 400 without it, verified against
     * PMS 1.43.3. It takes a single PlexItemType value; a comma-separated list is
     * also rejected with 400, so searching artists, albums and tracks together means
     * three calls.
     */
    @GET("library/sections/{sectionId}/search")
    suspend fun search(
        @Path("sectionId") sectionId: String,
        @Query("query") query: String,
        @Query("type") type: Int,
        @Query("limit") limit: Int
    ): PlexResponse

    @GET("playlists")
    suspend fun getPlaylists(@Query("playlistType") playlistType: String = "audio"): PlexResponse

    /**
     * The playlist's tracks. Plex also exposes GET playlists/{playlistId} for a
     * playlist's own metadata, deliberately not wrapped here: browsing needs the
     * list and then the items, never the metadata alone.
     */
    @GET("playlists/{playlistId}/items")
    suspend fun getPlaylistItems(
        @Path("playlistId") playlistId: String,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int
    ): PlexResponse

    /** Plex expects a GET despite this being a write; it returns an empty body. */
    @GET(":/timeline")
    suspend fun reportProgress(
        @Query("ratingKey") ratingKey: String,
        @Query("key") key: String,
        @Query("state") state: String,
        @Query("time") timeMs: Long
    )

    /**
     * Sets or clears a track's rating. Like :/timeline, Plex serves this write
     * over GET and returns an empty body. [rating] is 0-10, where 10 is the five
     * stars Plex collects into its heart-named playlist.
     */
    @GET(":/rate")
    suspend fun rate(
        @Query("key") key: String,
        @Query("identifier") identifier: String,
        @Query("rating") rating: Int
    )
}
