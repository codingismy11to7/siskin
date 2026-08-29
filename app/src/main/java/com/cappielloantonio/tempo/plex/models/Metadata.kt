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

    var parentTitle: String? = null
    var parentThumb: String? = null

    var grandparentRatingKey: String? = null
    var grandparentTitle: String? = null
    var grandparentThumb: String? = null

    /**
     * The *track's* artist, present only when it differs from the album artist --
     * which in practice means compilations. On "Girls Like Status" from an Aqua
     * Teen Hunger Force soundtrack, [grandparentTitle] is "Various Artists" and
     * this is "The Hold Steady".
     *
     * Free text with no rating key of its own, so it can name a track's artist
     * but never navigate to one; [grandparentRatingKey] remains the only artist
     * this app can browse to. See PlexMediaMapper.trackArtist.
     */
    var originalTitle: String? = null

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
     * Whether a playlist is defined by a query rather than by a list of items.
     * Only meaningful on a playlist; absent everywhere else.
     */
    var smart: Boolean? = null

    /**
     * A smart playlist's defining query, doubly encoded -- see
     * [com.cappielloantonio.tempo.util.SmartPlaylistQuery], which is the only
     * thing that reads it. Present only on a playlist, and only via
     * `GET playlists/{id}`: the playlists *listing* omits it.
     */
    var content: String? = null

    /**
     * Plex rates 0-10; 10 renders as five stars. This app writes 10 to heart a
     * track and -1 to clear it (`SearchClient.RATING_HEARTED` /
     * `RATING_CLEARED`) -- **not** 0, which a live server was measured to store
     * as a real zero-star rating rather than as no rating at all. Any other
     * client can still have written anything in 0..10, which is why
     * [PlexMediaMapper.isHearted] treats >= 10 as hearted rather than requiring
     * an exact match.
     */
    var userRating: Double? = null

    @SerializedName("Media")
    var media: List<Media>? = null
}
