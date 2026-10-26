package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class StreamingCacheChoicesTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun theLadderStopsAtHalfThePartition() {
        val choices = StreamingCacheChoices.cacheSizeChoices(automaticMegabytes = 1024, partitionMegabytes = 5_939)

        assertEquals(
            listOf(
                CacheSizeChoice.Automatic(1024),
                CacheSizeChoice.Fixed(512),
                CacheSizeChoice.Fixed(1024),
                CacheSizeChoice.Fixed(2048),
                CacheSizeChoice.Off,
            ),
            choices,
        )
    }

    @Test
    fun aLargePartitionReachesTheTopOfTheLadder() {
        val choices = StreamingCacheChoices.cacheSizeChoices(automaticMegabytes = 8192, partitionMegabytes = 131_072)

        assertEquals(CacheSizeChoice.Fixed(65_536), choices[choices.size - 2])
    }

    @Test
    fun anUnmeasuredPartitionShowsTheLadderUpToTheCeiling() {
        val choices = StreamingCacheChoices.cacheSizeChoices(automaticMegabytes = 1024, partitionMegabytes = 0)

        assertEquals(CacheSizeChoice.Fixed(StreamingCacheSize.CEILING_MEGABYTES), choices[choices.size - 2])
    }

    @Test
    fun theCurrentChoiceFollowsTheStoredValue() {
        assertEquals(CacheSizeChoice.Automatic(3277), StreamingCacheChoices.current(null, 3277))
        assertEquals(CacheSizeChoice.Off, StreamingCacheChoices.current(0, 3277))
        assertEquals(CacheSizeChoice.Fixed(4096), StreamingCacheChoices.current(4096, 3277))
    }

    @Test
    fun sizesReadAsRoundNumbers() {
        assertEquals("512 MB", StreamingCacheChoices.formatSize(resources, 512))
        assertEquals("2 GB", StreamingCacheChoices.formatSize(resources, 2048))
        assertEquals("6.4 GB", StreamingCacheChoices.formatSize(resources, 6553))
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchSizesUseFrenchUnitsAndDecimals() {
        assertEquals("6,4 Go", StreamingCacheChoices.formatSize(resources, 6553))
    }

    @Test
    fun theSummaryCarriesUsageButNotWhenOff() {
        assertEquals(
            "Automatic (1 GB) · 40 MB used",
            StreamingCacheChoices.cacheSizeSummary(resources, CacheSizeChoice.Automatic(1024), 40L * 1024 * 1024),
        )
        assertEquals("Automatic (1 GB)", StreamingCacheChoices.cacheSizeSummary(resources, CacheSizeChoice.Automatic(1024), null))
        assertEquals("Off", StreamingCacheChoices.cacheSizeSummary(resources, CacheSizeChoice.Off, 0L))
    }

    @Test
    fun precacheLabelsMarkTheDefaultAndSayWhenPaused() {
        assertEquals("Off", StreamingCacheChoices.precacheLabel(resources, 0))
        assertEquals("1 track", StreamingCacheChoices.precacheLabel(resources, 1))
        assertEquals("2 tracks (default)", StreamingCacheChoices.precacheLabel(resources, 2))
        assertEquals("2 tracks", StreamingCacheChoices.precacheSummary(resources, 2, cacheOff = false))
        assertEquals(
            "2 tracks · paused while the cache is off",
            StreamingCacheChoices.precacheSummary(resources, 2, cacheOff = true),
        )
        assertEquals("Off", StreamingCacheChoices.precacheSummary(resources, 0, cacheOff = true))
    }
}
