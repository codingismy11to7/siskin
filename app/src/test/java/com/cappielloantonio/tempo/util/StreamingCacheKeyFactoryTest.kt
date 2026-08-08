package com.cappielloantonio.tempo.util

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.cappielloantonio.tempo.plex.PlexApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric: reads PlexApi, and android.net.Uri needs a real implementation. */
@RunWith(RobolectricTestRunner::class)
class StreamingCacheKeyFactoryTest {

    private lateinit var api: PlexApi

    @Before
    fun reset() {
        api = PlexApi()
        api.machineIdentifier = null
    }

    private fun key(uri: String): String =
        StreamingCacheKeyFactory(api).buildCacheKey(DataSpec(Uri.parse(uri)))

    @Test
    fun theSameTrackKeysTheSameAcrossAddresses() {
        // The point of the change: re-probing onto the relay must not orphan
        // everything the car already downloaded over the LAN.
        api.machineIdentifier = "machine-a"

        assertEquals(
            key("https://lan.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t"),
            key("https://relay.example:8443/library/parts/1/2/file.mp3?X-Plex-Token=t")
        )
    }

    @Test
    fun differentServersStillKeyDifferently() {
        // The guard the origin used to provide: two servers can hand out the
        // same part path for different bytes.
        api.machineIdentifier = "machine-a"
        val a = key("https://lan.example:32400/library/parts/1/2/file.mp3")

        api.machineIdentifier = "machine-b"
        val b = key("https://lan.example:32400/library/parts/1/2/file.mp3")

        assertNotEquals(a, b)
    }

    @Test
    fun theTokenNeverReachesTheKey() {
        api.machineIdentifier = "machine-a"
        val key = key("https://lan.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=secret")

        assertEquals(false, key.contains("secret"))
    }

    @Test
    fun withoutAMachineIdentifierItFallsBackToTheOrigin() {
        // A session written before machineIdentifier existed. Falling back keeps
        // those working rather than collapsing every legacy session onto one key.
        val key = key("https://lan.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t")

        assertEquals("https://lan.example:32400/library/parts/1/2/file.mp3", key)
    }

    @Test
    fun aNonPartPathKeepsItsOrigin() {
        // Dropping the origin from anything but a part path would collide two
        // genuinely different resources that happen to share a path.
        api.machineIdentifier = "machine-a"

        assertNotEquals(
            key("https://one.example/some/thing.mp3"),
            key("https://two.example/some/thing.mp3")
        )
    }

    @Test
    fun anExplicitDataSpecKeyStillWins() {
        api.machineIdentifier = "machine-a"
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://lan.example:32400/library/parts/1/2/file.mp3"))
            .setKey("explicit")
            .build()

        assertEquals("explicit", StreamingCacheKeyFactory(api).buildCacheKey(spec))
    }
}
