package com.cappielloantonio.tempo.model

import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper

/**
 * A track a browse node returned, remembered so tapping one entry can rebuild
 * the whole list as the play queue.
 *
 * Stores the part key rather than a stream URL: a Plex stream URL carries
 * X-Plex-Token, so a persisted one breaks when the token rotates. [toMediaItem]
 * rebuilds the URL against the current credentials.
 */
@UnstableApi
@Keep
@Entity(tableName = "session_media_item")
class SessionMediaItem {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "index")
    var index: Int = 0

    @ColumnInfo(name = "id")
    var id: String? = null

    @ColumnInfo
    var title: String? = null

    @ColumnInfo
    var album: String? = null

    @ColumnInfo
    var artist: String? = null

    @ColumnInfo
    var thumb: String? = null

    @ColumnInfo(name = "part_key")
    var partKey: String? = null

    @ColumnInfo
    var duration: Long? = null

    @ColumnInfo(name = "track_index")
    var trackIndex: Int? = null

    @ColumnInfo
    var year: Int? = null

    @ColumnInfo(name = "parent_rating_key")
    var parentRatingKey: String? = null

    @ColumnInfo(name = "grandparent_rating_key")
    var grandparentRatingKey: String? = null

    @ColumnInfo
    var hearted: Boolean = false

    @ColumnInfo(name = "timestamp")
    var timestamp: Long? = null

    fun toMediaItem(): MediaItem {
        val api = PlexApi()
        return PlexMediaMapper.buildTrackMediaItem(
            ratingKey = id!!,
            title = title,
            albumTitle = album,
            artist = artist,
            thumb = thumb,
            partKey = partKey,
            durationMs = duration,
            trackIndex = trackIndex,
            year = year,
            parentRatingKey = parentRatingKey,
            grandparentRatingKey = grandparentRatingKey,
            isHearted = hearted,
            parentId = null,
            serverUri = api.serverUri,
            token = PlexApi.serverTokenOrAccount(api.serverToken, api.accountToken)
        )
    }

    companion object {
        @JvmStatic
        fun fromMediaItem(item: MediaItem?): SessionMediaItem? {
            val fields = PlexMediaMapper.readTrackFields(item) ?: return null

            return SessionMediaItem().apply {
                id = fields.ratingKey
                title = fields.title
                album = fields.albumTitle
                artist = fields.artist
                thumb = fields.thumb
                partKey = fields.partKey
                duration = fields.durationMs
                trackIndex = fields.trackIndex
                year = fields.year
                parentRatingKey = fields.parentRatingKey
                grandparentRatingKey = fields.grandparentRatingKey
                hearted = fields.isHearted
            }
        }
    }
}
