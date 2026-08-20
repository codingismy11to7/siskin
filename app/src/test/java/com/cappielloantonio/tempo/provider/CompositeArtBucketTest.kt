package com.cappielloantonio.tempo.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit, no Robolectric: this is arithmetic and touches no Android type,
 * which is the point of keeping it in its own object rather than inline in the
 * provider.
 */
class CompositeArtBucketTest {
    @Test
    fun theBucketIsStableWithinAnHourAndChangesAcrossOne() {
        val hour = CompositeArtBucket.BUCKET_MS

        assertEquals(
            CompositeArtBucket.current(3 * hour),
            CompositeArtBucket.current(3 * hour + hour - 1),
        )
        assertEquals(
            CompositeArtBucket.current(3 * hour) + 1,
            CompositeArtBucket.current(3 * hour + hour),
        )
    }

    @Test
    fun theCurrentAndPreviousBucketsAreLiveAndNothingElseIs() {
        val hour = CompositeArtBucket.BUCKET_MS
        val now = 100 * hour
        val current = CompositeArtBucket.current(now)

        assertTrue(CompositeArtBucket.isLive(current, now))
        // The previous bucket is accepted so the hour boundary is not brittle:
        // a URI minted at 10:59:59 and opened at 11:00:01 must still draw.
        assertTrue(CompositeArtBucket.isLive(current - 1, now))

        // Anything else is refused, and that is a security property rather than
        // tidiness. Every miss is a Plex request made with the user's token, and
        // this provider is exported -- without the window, a caller could walk
        // arbitrary bucket values to force unlimited misses.
        assertFalse(CompositeArtBucket.isLive(current - 2, now))
        assertFalse(CompositeArtBucket.isLive(current + 1, now))
        assertFalse(CompositeArtBucket.isLive(0, now))
    }
}
