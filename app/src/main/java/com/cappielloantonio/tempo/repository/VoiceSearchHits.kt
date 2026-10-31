package com.cappielloantonio.tempo.repository

import androidx.media3.common.MediaItem

/** A matched track, with what it takes to open its album around it. */
data class VoiceTrackHit(
    val item: MediaItem,
    val albumKey: String?,
)

/** One voice search's matches by tier, each in the order Plex ranked them. */
data class VoiceSearchHits(
    val artists: List<MediaItem>,
    val albums: List<MediaItem>,
    val tracks: List<VoiceTrackHit>,
)
