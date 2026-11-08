package com.cappielloantonio.tempo.service

import androidx.media3.common.MediaItem
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.api.search.TimelineState
import com.cappielloantonio.tempo.repository.QueueRepository

object MediaManager {
    fun setLastPlayedTimestamp(mediaItem: MediaItem?) {
        if (mediaItem != null) QueueRepository().setLastPlayedTimestamp(mediaItem.mediaId)
    }

    fun setPlayingPausedTimestamp(
        mediaItem: MediaItem?,
        ms: Long,
    ) {
        if (mediaItem != null) QueueRepository().setPlayingPausedTimestamp(mediaItem.mediaId, ms)
    }

    /**
     * Reports playback to Plex's timeline endpoint.
     *
     * Plex wants the part being played and a transport state where Subsonic's scrobble was a
     * fire-and-forget "I played this": a track with no part key has nothing to report against, so
     * it is skipped rather than sent half-formed.
     *
     * [positionMs] matters because Plex, not this client, decides whether a play counts:
     * the `/:/timeline` handler on the server compares the reported position against the
     * track duration and its own watched-percentage threshold before it will mark the item played.
     * A "stopped" report sent with position 0 reads to Plex as "left at the very start" regardless
     * of how much actually played, so it silently never registers a play. Callers must pass the
     * real position at the moment of the event, not a placeholder.
     */
    fun scrobble(
        mediaItem: MediaItem?,
        state: TimelineState,
        positionMs: Long,
    ) {
        val extras = mediaItem?.mediaMetadata?.extras ?: return
        val ratingKey = extras.getString(PlexMediaMapper.EXTRA_ID) ?: return
        val partKey = extras.getString(PlexMediaMapper.EXTRA_PART_KEY) ?: return
        PlexScrobbler.report(ratingKey, partKey, state, positionMs, PlexMediaMapper.stationTagOf(mediaItem))
    }
}
