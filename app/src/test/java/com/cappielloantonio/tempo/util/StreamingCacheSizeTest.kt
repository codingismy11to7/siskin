package com.cappielloantonio.tempo.util

import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowStatFs
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class StreamingCacheSizeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        ShadowStatFs.reset()
    }

    @Test
    fun takesATenthOfAPartitionBigEnoughToSpareIt() {
        assertEquals(6553L, StreamingCacheSize.fromTotalMegabytes(65_536L))
    }

    @Test
    fun stopsAtTheCeilingRatherThanScalingWithTheDisk() {
        assertEquals(StreamingCacheSize.CEILING_MEGABYTES, StreamingCacheSize.fromTotalMegabytes(131_072L))
        assertEquals(StreamingCacheSize.CEILING_MEGABYTES, StreamingCacheSize.fromTotalMegabytes(1_048_576L))
    }

    @Test
    fun liftsASmallPartitionUpToTheFloor() {
        // A tenth of 32 GB is 3.2 GB, so the floor is not what decides this one --
        // it is here to bracket the case below, which the floor alone would get wrong.
        assertEquals(3276L, StreamingCacheSize.fromTotalMegabytes(32_768L))
        assertEquals(StreamingCacheSize.FLOOR_MEGABYTES, StreamingCacheSize.fromTotalMegabytes(8_192L))
    }

    /**
     * The floor is a floor, not an override. On a partition small enough that
     * 1 GB would be a quarter of everything the device has, taking 1 GB is worse
     * than caching less -- so the quarter-share clamp overrides the floor
     * downward, and this is the only case where the result is under it.
     */
    @Test
    fun givesUpTheFloorOnAPartitionTooSmallToAffordIt() {
        assertEquals(512L, StreamingCacheSize.fromTotalMegabytes(2_048L))
        assertEquals(128L, StreamingCacheSize.fromTotalMegabytes(512L))
    }

    /**
     * Zero is how the preference spells "cache nothing", so an unmeasurable
     * partition must not resolve to it: that would turn a failed statfs into a
     * silent loss of caching rather than a conservative default.
     */
    @Test
    fun fallsBackToTheFloorRatherThanZeroWhenThePartitionCannotBeMeasured() {
        assertEquals(StreamingCacheSize.FLOOR_MEGABYTES, StreamingCacheSize.fromTotalMegabytes(0L))
        assertEquals(StreamingCacheSize.FLOOR_MEGABYTES, StreamingCacheSize.fromTotalMegabytes(-1L))
    }

    @Test
    fun measuresThePartitionTheDirectoryIsOn() {
        val directory = temporaryFolder.newFolder("files")
        registerPartitionOf(directory, gigabytes = 64)

        assertEquals(6553L, StreamingCacheSize.forDirectory(directory))
    }

    /**
     * SimpleCache creates its content directory lazily, so the first call of a
     * fresh install measures a path that does not exist yet. statfs throws on
     * one, and the ancestor walk is what keeps that from collapsing to the
     * floor on exactly the installs the sizing exists to serve.
     */
    @Test
    fun measuresTheNearestExistingAncestorWhenTheCacheDirectoryIsNotThereYet() {
        val existing = temporaryFolder.newFolder("files")
        registerPartitionOf(existing, gigabytes = 64)

        val notYetCreated = File(existing, "streaming_cache")
        assertEquals(false, notYetCreated.exists())

        assertEquals(6553L, StreamingCacheSize.forDirectory(notYetCreated))
    }

    private fun registerPartitionOf(
        directory: File,
        gigabytes: Int,
    ) {
        val blocks = gigabytes * (1024L * 1024L * 1024L) / ShadowStatFs.BLOCK_SIZE
        ShadowStatFs.registerStats(directory, blocks.toInt(), blocks.toInt(), blocks.toInt())
    }
}
