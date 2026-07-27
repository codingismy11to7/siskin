package com.cappielloantonio.tempo.plex.base

import androidx.annotation.Keep
import com.cappielloantonio.tempo.plex.models.Directory
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
    var size: Int? = null
    var totalSize: Int? = null

    /** Items: tracks, albums, artists, playlists. */
    @SerializedName("Metadata")
    var metadata: List<Metadata>? = null

    /** Containers: library sections, and hub rows. */
    @SerializedName("Directory")
    var directory: List<Directory>? = null
}
