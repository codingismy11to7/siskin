package com.cappielloantonio.tempo.service.crossfade

import androidx.media3.common.C
import androidx.media3.common.Player

object CrossfadePolicy {
    /**
     * How long before the overlap the tail player starts lining up. Lining it up took 3.5-6 s on the
     * emulator, and with less lead the fade is given up. A track must outlast `2 * overlap + LEAD_MS`
     * because the tail is armed this long before an overlap that may itself be that long, and the
     * incoming fade must not start inside the previous track's own overlap.
     */
    const val LEAD_MS = 5_000L

    fun overlapFor(
        enabled: Boolean,
        hasNext: Boolean,
        repeatMode: Int,
        durationMs: Long,
        outgoing: TrackFadeInfo?,
        incoming: TrackFadeInfo?,
    ): Long? {
        if (!enabled || !hasNext || repeatMode == Player.REPEAT_MODE_ONE) return null
        if (outgoing == null || incoming == null) return null
        val overlap = outgoing.overlapMs ?: return null
        if (outgoing.albumKey == null || incoming.albumKey == null || outgoing.albumKey == incoming.albumKey) return null
        if (durationMs == C.TIME_UNSET || durationMs <= 2 * overlap + LEAD_MS) return null
        return overlap
    }
}
