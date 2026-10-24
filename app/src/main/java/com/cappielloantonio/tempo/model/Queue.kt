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
 * One entry of the persisted play queue, restored on service start.
 *
 * Like SessionMediaItem this stores the part key, not a stream URL, so a
 * restored queue survives a token rotation.
 */
@UnstableApi
@Keep
@Entity(tableName = "queue")
class Queue {
    @ColumnInfo(name = "id")
    var id: String? = null

    @PrimaryKey
    @ColumnInfo(name = "track_order")
    var trackOrder: Int = 0

    @ColumnInfo(name = "last_play")
    var lastPlay: Long = 0

    @ColumnInfo(name = "playing_changed")
    var playingChanged: Long = 0

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

    @ColumnInfo(name = "grandparent_rating_key")
    var grandparentRatingKey: String? = null

    @ColumnInfo
    var hearted: Boolean = false

    @ColumnInfo(name = "play_queue_id")
    var playQueueId: Long? = null

    @ColumnInfo(name = "play_queue_item_id")
    var playQueueItemId: Long? = null

    @ColumnInfo(name = "station_artist_id")
    var stationArtistId: String? = null

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
            grandparentRatingKey = grandparentRatingKey,
            isHearted = hearted,
            parentId = null,
            serverUri = api.serverUri,
            token = api.serverAuthToken,
            station = stationTag(),
        )
    }

    private fun stationTag(): PlexMediaMapper.StationTag? {
        val queueId = playQueueId ?: return null
        val itemId = playQueueItemId ?: return null
        val artist = stationArtistId ?: return null
        return PlexMediaMapper.StationTag(queueId, itemId, artist)
    }

    companion object {
        @JvmStatic
        fun fromMediaItem(item: MediaItem?): Queue? {
            val fields = PlexMediaMapper.readTrackFields(item) ?: return null

            return Queue().apply {
                id = fields.ratingKey
                title = fields.title
                album = fields.albumTitle
                artist = fields.artist
                thumb = fields.thumb
                partKey = fields.partKey
                duration = fields.durationMs
                trackIndex = fields.trackIndex
                year = fields.year
                grandparentRatingKey = fields.grandparentRatingKey
                hearted = fields.isHearted
                playQueueId = fields.station?.playQueueId
                playQueueItemId = fields.station?.playQueueItemId
                stationArtistId = fields.station?.artistRatingKey
            }
        }
    }
}
