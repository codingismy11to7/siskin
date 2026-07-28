package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 * One library item. Plex uses a single shape for tracks, albums and artists,
 * distinguished by [type] ("track", "album", "artist").
 *
 * The parent/grandparent fields are how Plex expresses hierarchy: for a track,
 * parent is its album and grandparent is its artist.
 *
 * Fields are deliberately partial -- Gson ignores unknown keys, so this carries
 * only what the app reads.
 */
@Keep
class Metadata {
    var ratingKey: String? = null
    var key: String? = null
    var type: String? = null
    var title: String? = null

    var parentRatingKey: String? = null
    var parentTitle: String? = null
    var parentThumb: String? = null

    var grandparentRatingKey: String? = null
    var grandparentTitle: String? = null
    var grandparentThumb: String? = null

    var thumb: String? = null

    /**
     * Playlists carry no [thumb]; Plex instead generates this mosaic of the
     * playlist's own contents. Only meaningful on a playlist -- tracks,
     * albums and artists don't populate it.
     */
    var composite: String? = null

    /** Milliseconds. */
    var duration: Long? = null
    /** Track number within its album. */
    var index: Int? = null
    var year: Int? = null
    var addedAt: Long? = null
    var leafCount: Int? = null

    /**
     * Plex rates 0-10; 10 renders as five stars. This app only ever writes 10
     * (hearted) or 0 (cleared), so [PlexMediaMapper.isHearted] treats >= 10 as
     * hearted rather than requiring an exact match.
     */
    var userRating: Double? = null

    @SerializedName("Media")
    var media: List<Media>? = null
}
