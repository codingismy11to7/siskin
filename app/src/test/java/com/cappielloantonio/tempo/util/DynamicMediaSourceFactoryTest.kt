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
 * Pins the placement ServerAddressResolver depends on: DynamicMediaSourceFactory
 * wraps every DataSource.Factory it builds in a ResolvingDataSource.Factory,
 * regardless of which branch (streaming cache on or off) produced it. See the
 * comment on buildDataSourceFactory -- a resolver added only inside
 * DownloadUtil.getCacheDataSourceFactory would silently not run for anyone who
 * set the streaming cache to zero, which is exactly the case
 * theResolverWrapsWhenTheStreamingCacheIsOff pins.
 *
 * Both tests open a real DataSpec, built against a dead local port, through
 * the exact DataSource.Factory buildDataSourceFactory() hands to
 * createMediaSource, and assert the request actually landed on a MockWebServer
 * standing in for the live server. The fixture that builds the session, the
 * live server and the dead port is ResolvedStreamFixture -- see its KDoc for
 * why it is shared rather than copied. MockWebServer is already a project
 * dependency, used exactly this way throughout ServerAddressBookTest and
 * ServerAddressResolverTest, so "reaching a resolved DataSpec would mean real
 * network I/O this test cannot do" (this file's previous justification for a
 * type check instead) was wrong.
 *
 * The type check it replaced was worse than merely weaker: on the
 * streaming-cache-on branch it was vacuous. DownloadUtil.getCacheDataSourceFactory
 * itself returns a ResolvingDataSource.Factory -- the flag-fixer at
 * DownloadUtil.java:62-71 that clears FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN -- so
 * `is ResolvingDataSource.Factory` on that branch was satisfied by the
 * unwrapped cache factory whether or not ServerAddressResolver was ever
 * wrapped around it. Deleting the wrap in buildDataSourceFactory entirely
 * (`val dataSourceFactory: DataSource.Factory = selected`) proves it: the
 * request stays aimed at the dead port and the mock never sees it, on both
 * branches, which is what these two tests now actually assert against.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DynamicMediaSourceFactoryTest {

    private lateinit var factory: DynamicMediaSourceFactory
    private val fixture = ResolvedStreamFixture()

    @Before
    fun setUp() {
        fixture.setUp()
        factory = DynamicMediaSourceFactory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        fixture.tearDown()
    }

    @Test
    fun theResolverWrapsWhenTheStreamingCacheIsOff() {
        fixture.setStreamingCacheSize(0L)

        fixture.openThrough(factory.buildDataSourceFactory())

        assertEquals(
            "request must have landed on the live server the resolver rewrote it onto, " +
                "not the dead port it was built with",
            1, fixture.requestCount
        )
    }

    @Test
    fun theResolverWrapsWhenTheStreamingCacheIsOn() {
        fixture.setStreamingCacheSize(256L)

        fixture.openThrough(factory.buildDataSourceFactory())

        assertEquals(
            "request must have landed on the live server the resolver rewrote it onto, " +
                "not the dead port it was built with",
            1, fixture.requestCount
        )
    }
}
