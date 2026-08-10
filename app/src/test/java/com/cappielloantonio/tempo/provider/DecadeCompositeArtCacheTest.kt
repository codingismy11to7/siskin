package com.cappielloantonio.tempo.provider

import com.cappielloantonio.tempo.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric for a real cacheDir. The filenames are the whole contract here:
 * they are what keeps one library's composites from being served for
 * another -- on the same server (the section key) and across servers (the
 * machine identifier) -- and what eviction reads back to decide staleness.
 */
@RunWith(RobolectricTestRunner::class)
class DecadeCompositeArtCacheTest {

    private val context get() = App.getContext()

    @Before
    fun setUp() {
        DecadeCompositeArt.cacheDir(context).deleteRecursively()
    }

    @Test
    fun theSectionKeyIsInTheNameSoLibrariesOnOneServerDoNotShareComposites() {
        // More -> Server Select can switch libraries underneath a cached tile.
        val four = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1980", 100)
        val nine = DecadeCompositeArt.cacheFile(context, "machinea", "9", "1980", 100)

        assertFalse(four.name == nine.name)
    }

    @Test
    fun theMachineIdentifierIsInTheNameSoDifferentServersDoNotShareComposites() {
        // The section key alone is not enough: it is a small integer, and two
        // different servers can each have a section "4". This is the case the
        // bug report was about -- switching servers under More -> Server
        // Select must not serve the previous server's mosaic for a decade
        // that happens to share a section key.
        val serverA = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1980", 100)
        val serverB = DecadeCompositeArt.cacheFile(context, "machineb", "4", "1980", 100)

        assertFalse(serverA.name == serverB.name)
    }

    @Test
    fun aNullMachineIdentifierAndAnUnsafeOneFallBackToTheSameSentinel() {
        // PlexSession.machineIdentifier's KDoc requires every reader to
        // tolerate its absence rather than treat it as a different server --
        // two identifier-less sessions must therefore share one cache key,
        // which is exactly today's (pre-fix) behaviour and no worse. An
        // identifier that fails the safety predicate (here, one containing a
        // path separator) is documented to collapse onto that same sentinel
        // rather than being passed through raw, so it must land on the exact
        // same filename as a null identifier -- not merely a different one.
        val noIdentifier = DecadeCompositeArt.cacheFile(context, null, "4", "1980", 100)
        val unsafeIdentifier =
            DecadeCompositeArt.cacheFile(context, "../evil", "4", "1980", 100)

        assertTrue(noIdentifier.name == unsafeIdentifier.name)
    }

    @Test
    fun aNullMachineIdentifierDoesNotCollideWithARealOne() {
        // The sentinel a null identifier maps to must not be reachable by a
        // real (hex) machine identifier.
        val noIdentifier = DecadeCompositeArt.cacheFile(context, null, "4", "1980", 100)
        val realIdentifier = DecadeCompositeArt.cacheFile(context, "abc123def456", "4", "1980", 100)

        assertFalse(noIdentifier.name == realIdentifier.name)
    }

    @Test
    fun theBucketIsInTheNameSoAnHourRollIsAMiss() {
        val now = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1980", 100)
        val next = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1980", 101)

        assertFalse(now.name == next.name)
    }

    @Test
    fun evictionKeepsTheTwoLiveBucketsAndDeletesTheRest() {
        val hour = CompositeArtBucket.BUCKET_MS
        val nowMs = 100 * hour
        val current = CompositeArtBucket.current(nowMs)

        val live = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1980", current)
        val previous = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1980", current - 1)
        val stale = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1980", current - 2)
        val ancient = DecadeCompositeArt.cacheFile(context, "machinea", "4", "1970", 0)
        listOf(live, previous, stale, ancient).forEach {
            it.parentFile!!.mkdirs()
            it.writeBytes(byteArrayOf(1))
        }

        DecadeCompositeArt.evictStale(context, nowMs)

        assertTrue(live.exists())
        // The previous bucket survives for the same reason it is served: a URI
        // minted just before the boundary is still being drawn just after it.
        assertTrue(previous.exists())
        assertFalse(stale.exists())
        assertFalse(ancient.exists())
    }

    @Test
    fun evictionLeavesUnrecognisedFilesAloneRatherThanEmptyingTheDirectory() {
        // cacheDir is shared with nothing today, but a sweep that deletes what
        // it cannot parse is a sweep that will one day delete someone else's
        // cache.
        val stray = DecadeCompositeArt.cacheDir(context).resolve("notours.txt")
        stray.parentFile!!.mkdirs()
        stray.writeText("keep me")

        DecadeCompositeArt.evictStale(context, 100 * CompositeArtBucket.BUCKET_MS)

        assertTrue(stray.exists())
        assertEquals("keep me", stray.readText())
    }
}
