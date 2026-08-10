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
     * Index of the first item in this page. Nothing in this app reads it --
     * media3 never actually pages a browse node, see LibraryService's KDoc --
     * but it is part of the MediaContainer shape Plex returns whenever
     * Start/Size are sent, so it is kept for fidelity with the real response
     * rather than trimmed.
     */
    var offset: Int? = null

    /** Items: tracks, albums, artists, playlists. */
    @SerializedName("Metadata")
    var metadata: List<Metadata>? = null

    /**
     * Containers: library sections, and decade entries from the decade index
     * (see `PlexBrowseRepository.directoriesOf`). Hub rows arrive in [hub], not
     * here.
     */
    @SerializedName("Directory")
    var directory: List<Directory>? = null

    /** Hub rows, e.g. from GET /hubs/sections/{id} -- "Recently Added", "On Deck". */
    @SerializedName("Hub")
    var hub: List<Hub>? = null
}
