package com.cappielloantonio.tempo.service.crossfade

data class RampPoint(
    val db: Float,
    val seconds: Float,
)

/**
 * Plex's loudness ramps: `"dB seconds;dB seconds;..."`, levels rising as seconds
 * grow -- counted from the start for a start ramp, from the end for an end ramp.
 * Undocumented; see docs/decisions/2026-11-07-cross-fade-design.md.
 */
object LoudnessRamp {
    const val THRESHOLD_DB = -3f
    const val MIN_OVERLAP_MS = 2_000L
    const val MAX_OVERLAP_MS = 10_000L

    fun parse(ramp: String?): List<RampPoint> =
        ramp
            .orEmpty()
            .split(';')
            .mapNotNull { point ->
                val fields = point.trim().split(' ')
                if (fields.size != 2) return@mapNotNull null
                val db = fields[0].toFloatOrNull() ?: return@mapNotNull null
                val seconds = fields[1].toFloatOrNull() ?: return@mapNotNull null
                RampPoint(db, seconds)
            }.sortedBy { it.seconds }

    fun secondsAt(
        points: List<RampPoint>,
        thresholdDb: Float,
    ): Float? {
        var previous: RampPoint? = null
        for (point in points) {
            if (point.db >= thresholdDb) {
                val before = previous ?: return point.seconds
                val rise = point.db - before.db
                return before.seconds + (point.seconds - before.seconds) * (thresholdDb - before.db) / rise
            }
            previous = point
        }
        return null
    }

    fun overlapMs(endRamp: String?): Long? =
        secondsAt(parse(endRamp), THRESHOLD_DB)
            ?.let { (it * 1000).toLong().coerceIn(MIN_OVERLAP_MS, MAX_OVERLAP_MS) }
}
