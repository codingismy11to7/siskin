package com.cappielloantonio.tempo.plex.base

import androidx.annotation.Keep
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Hub
import com.cappielloantonio.tempo.plex.models.Metadata
import com.google.gson.annotations.SerializedName

/**
 * Every Plex Media Server response is wrapped in a MediaContainer. plex.tv v2
 * responses (/pins, /resources) are NOT -- those deserialize to their models
 * directly.
 */
@Keep
class PlexResponse {
    @SerializedName("MediaContainer")
    var mediaContainer: MediaContainer? = null
}

@Keep
class MediaContainer {
    /** How many items this response carries -- the page, not the library. */
    var size: Int? = null

    /** How many exist in total; present only when the request was paged. */
    var totalSize: Int? = null

    /**
     * Index of the first item in this page. media3 needs it to place a page
     * within the whole list; without it a client can only guess from the
     * request it sent.
     */
    var offset: Int? = null

    /** Items: tracks, albums, artists, playlists. */
    @SerializedName("Metadata")
    var metadata: List<Metadata>? = null

    /** Containers: library sections. Hub rows arrive in [hub], not here. */
    @SerializedName("Directory")
    var directory: List<Directory>? = null

    /** Hub rows, e.g. from GET /hubs/sections/{id} -- "Recently Added", "On Deck". */
    @SerializedName("Hub")
    var hub: List<Hub>? = null
}
