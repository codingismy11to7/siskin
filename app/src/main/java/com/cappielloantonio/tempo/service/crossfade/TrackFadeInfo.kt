package com.cappielloantonio.tempo.service.crossfade

import com.cappielloantonio.tempo.plex.models.Metadata

private const val AUDIO_STREAM = 2

/**
 * What a cross-fade needs to know about one track. [albumKey] comes from the
 * same request as the ramp because a queued MediaItem carries no album id, and
 * the persisted queue drops its extras.
 */
data class TrackFadeInfo(
    val overlapMs: Long?,
    val albumKey: String?,
) {
    companion object {
        fun from(metadata: Metadata?): TrackFadeInfo {
            val endRamp =
                metadata
                    ?.media
                    ?.firstOrNull()
                    ?.part
                    ?.firstOrNull()
                    ?.stream
                    ?.firstOrNull { it.streamType == AUDIO_STREAM }
                    ?.endRamp
            return TrackFadeInfo(LoudnessRamp.overlapMs(endRamp), metadata?.parentRatingKey)
        }
    }
}
