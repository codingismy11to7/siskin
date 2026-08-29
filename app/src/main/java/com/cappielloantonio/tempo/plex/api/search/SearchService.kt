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
        @Query("limit") limit: Int,
    ): PlexResponse

    /**
     * Playlists, scoped to one music library section via [sectionId].
     *
     * Verified against a live PMS 1.43.3 server: `sectionID` is the query
     * parameter that actually filters the listing -- `librarySectionID` is
     * accepted (HTTP 200) but silently ignored; both were tried against the
     * same server and only `sectionID` changed the result set. A playlist
     * object itself carries no section field of its own -- in Plex a
     * playlist is a server-level collection that can span libraries -- which
     * is why this has to be a request parameter rather than something
     * filterable from the response.
     */
    @GET("playlists")
    suspend fun getPlaylists(
        @Query("sectionID") sectionId: String,
        @Query("playlistType") playlistType: String = "audio",
    ): PlexResponse

    /**
     * One playlist's own metadata: `leafCount`, `smart`, and a smart
     * playlist's `content` query.
     *
     * The probe a Mix issues before deciding what to fetch. 561 bytes against a
     * real server, versus 4MB for the 2,500-track fetch it might otherwise
     * commit to blind. `content` is the reason it is this endpoint rather than
     * a zero-sized container probe on `{id}/items`, which answers the count and
     * `smart` but omits the query.
     */
    @GET("playlists/{playlistId}")
    suspend fun getPlaylist(
        @Path("playlistId") playlistId: String,
    ): PlexResponse

    /**
     * The playlist's tracks.
     */
    @GET("playlists/{playlistId}/items")
    suspend fun getPlaylistItems(
        @Path("playlistId") playlistId: String,
        @Header("X-Plex-Container-Start") start: Int,
        @Header("X-Plex-Container-Size") size: Int,
    ): PlexResponse

    /** Plex expects a GET despite this being a write; it returns an empty body. */
    @GET(":/timeline")
    suspend fun reportProgress(
        @Query("ratingKey") ratingKey: String,
        @Query("key") key: String,
        @Query("state") state: String,
        @Query("time") timeMs: Long,
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
        @Query("rating") rating: Int,
    )
}
