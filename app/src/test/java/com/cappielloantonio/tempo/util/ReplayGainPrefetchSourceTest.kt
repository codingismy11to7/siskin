package com.cappielloantonio.tempo.util

import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The gain prefetch used to build its MetadataRetriever with the two-argument
 * MetadataRetriever.Builder, which constructs a DefaultMediaSourceFactory of its
 * own. Two things followed, and neither was observable while the feature was off:
 * the prefetch opened a second, uncached connection to a URL playback was about
 * to open anyway, and it never reached ServerAddressResolver -- so a server whose
 * address had changed since the queue was built failed every prefetch, silently,
 * behind the catch(Throwable) that logs one debug line.
 *
 * This pins the second one, which is the one with teeth, using the same fixture
 * and the same technique as DynamicMediaSourceFactoryTest: open a real DataSpec,
 * built against a dead local port, through the exact factory the prefetch reads
 * through, and assert the request landed on the live server the resolver rewrote
 * it onto. Both streaming-cache branches, because buildDataSourceFactory picks
 * between two and only one of them is wrapped by DownloadUtil.
 *
 * What it does not pin is the mp4 extractor flags, which are not observable from
 * outside a MediaSource.Factory. Those are a comment and a code review.
 *
 * Nor does it pin that submitPrefetch actually calls setMediaSourceFactory in
 * the first place -- a revert to the two-argument MetadataRetriever.Builder
 * would leave this suite green, because that builder still produces a working
 * MetadataRetriever, just one wired to its own DefaultMediaSourceFactory
 * instead of the one under test here. MetadataRetriever exposes no way to ask
 * which factory it ended up with, so there is no cheap assertion for this
 * either; it stays a comment and a code review too.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ReplayGainPrefetchSourceTest {

    private val fixture = ResolvedStreamFixture()

    @Before
    fun setUp() {
        fixture.setUp()
    }

    @After
    fun tearDown() {
        fixture.tearDown()
    }

    private fun openThroughThePrefetchFactory() {
        fixture.openThrough(
            ReplayGainUtil.prefetchDataSourceFactory(RuntimeEnvironment.getApplication())
        )
    }

    @Test
    fun theResolverIsInTheChainWhenTheStreamingCacheIsOff() {
        fixture.setStreamingCacheSize(0L)

        openThroughThePrefetchFactory()

        assertEquals(
            "the prefetch must reach the address the resolver rewrote it onto, " +
                "not the one the queue was built with",
            1, fixture.requestCount
        )
    }

    @Test
    fun theResolverIsInTheChainWhenTheStreamingCacheIsOn() {
        fixture.setStreamingCacheSize(256L)

        openThroughThePrefetchFactory()

        assertEquals(
            "the prefetch must reach the address the resolver rewrote it onto, " +
                "not the one the queue was built with",
            1, fixture.requestCount
        )
    }
}
