package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesCacheSettingsTest {
    @Before
    fun setUp() {
        App
            .getInstance()
            .preferences
            .edit()
            .remove("streaming_cache_size")
            .remove("precache_tracks_count")
            .commit()
    }

    @Test
    fun aSizeRoundTripsAndNullMeansAutomatic() {
        Preferences.setStreamingCacheSizeOverrideMegabytes(2048L)
        assertEquals(2048L, Preferences.getStreamingCacheSizeOverrideMegabytes())

        Preferences.setStreamingCacheSizeOverrideMegabytes(null)
        assertNull(Preferences.getStreamingCacheSizeOverrideMegabytes())
    }

    @Test
    fun zeroIsStoredAsOffRatherThanAbsence() {
        Preferences.setStreamingCacheSizeOverrideMegabytes(0L)
        assertEquals(0L, Preferences.getStreamingCacheSizeOverrideMegabytes())
    }

    @Test
    fun thePrecacheCountRoundTripsAndDefaultsToTwo() {
        assertEquals(Preferences.DEFAULT_PRECACHE_TRACKS_COUNT, Preferences.getPrecacheTracksCount())

        Preferences.setPrecacheTracksCount(5)
        assertEquals(5, Preferences.getPrecacheTracksCount())
    }
}
