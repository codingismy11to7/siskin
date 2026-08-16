package com.cappielloantonio.tempo.provider

/**
 * The hour window a composite belongs to.
 *
 * This exists because a `content://` URI that named only the decade would be
 * constant forever, and the car's own image cache could then pin a tile for the
 * life of the process -- the one-hour life would exist in our code and never be
 * observable in the car. Rolling the bucket changes the URI, which invalidates
 * every cache in the chain at once without any of them needing to know a TTL
 * exists. A `hubArt` URI names its own covers and so is already content-addressed;
 * it carries a bucket anyway, for a narrower reason. A tile that degraded
 * because a cover failed to load would otherwise be pinned in the car's own
 * image cache under a URI nothing invalidates. Rolling the bucket is what
 * lets it redraw.
 *
 * Takes `nowMs` rather than reading a clock, so it stays a pure function and the
 * test does not have to wait an hour.
 */
object CompositeArtBucket {

    /** One hour. Long enough that a drive rarely crosses two, short enough that
     * a morning commute and an evening one differ -- artwork that never changes
     * is the thing this feature exists to avoid. */
    @JvmField
    val BUCKET_MS: Long = 60L * 60L * 1000L

    @JvmStatic
    fun current(nowMs: Long): Long = nowMs / BUCKET_MS

    /**
     * Whether a bucket off an incoming URI may be served.
     *
     * The previous bucket is accepted as well as the current one, so a URI
     * minted a second before the boundary and opened a second after it still
     * draws. Anything outside that window is refused, and that is load-bearing
     * on an exported provider: every cache miss is a Plex request made with the
     * user's token, so an unbounded bucket space is an unbounded request space.
     */
    @JvmStatic
    fun isLive(bucket: Long, nowMs: Long): Boolean {
        val now = current(nowMs)
        return bucket == now || bucket == now - 1
    }
}
