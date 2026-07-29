package com.cappielloantonio.tempo.util

import android.net.Uri
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric rather than plain JUnit: the whole function is Uri manipulation,
 * and under this module's unitTests.returnDefaultValues Uri.parse answers null,
 * so every key would be "null" and every assertion below would pass against an
 * implementation that did nothing.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingCacheKeyFactoryTest {

    private val factory = StreamingCacheKeyFactory()

    private fun keyFor(url: String) =
        factory.buildCacheKey(DataSpec.Builder().setUri(Uri.parse(url)).build())

    private val server = "https://192-168-1-5.abc123.plex.direct:32400"
    private val part = "/library/parts/7/1699999999/file.flac"

    /**
     * Catches a factory that keys on the full URI -- which is what this did
     * before, and what returning `dataSpec.uri.toString()` would restore.
     *
     * MediaUrlBuilder.streamUrl appends `?X-Plex-Token=<token>` to every stream
     * URL, so the token is part of the URI. Keyed on that, a token rotation
     * orphans every entry in the cache at once: the same file asks for a key it
     * has never stored under and re-downloads, and the old bytes are never
     * evicted by anything except the size bound. The same defect is what
     * `partKey` exists to prevent in the Room entities.
     */
    @Test
    fun twoUrlsForTheSameFileDifferingOnlyInTokenShareOneKey() {
        assertEquals(
            keyFor("$server$part?X-Plex-Token=old-token"),
            keyFor("$server$part?X-Plex-Token=rotated-token")
        )
    }

    /**
     * Catches over-stripping -- a factory that keyed on the host, or on nothing
     * at all, would make every track in the library collide on one cache entry
     * and play whichever audio happened to be cached first.
     */
    @Test
    fun differentPartsDoNotCollide() {
        assertNotEquals(
            keyFor("$server/library/parts/7/1/a.flac?X-Plex-Token=tok"),
            keyFor("$server/library/parts/8/1/b.flac?X-Plex-Token=tok")
        )
    }

    /** The token must not reach the on-disk cache index either. */
    @Test
    fun theKeyDoesNotContainTheToken() {
        val key = keyFor("$server$part?X-Plex-Token=secret-token")

        assertFalse(key, key.contains("secret-token"))
        assertFalse(key, key.contains("X-Plex-Token"))
        assertEquals("$server$part", key)
    }

    @Test
    fun anExplicitDataSpecKeyStillWins() {
        // Nothing sets one today, but it identifies the bytes directly when a
        // caller does, and it must not be overwritten by the URL fallback.
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("$server$part?X-Plex-Token=tok"))
            .setKey("explicit-key")
            .build()

        assertEquals("explicit-key", factory.buildCacheKey(spec))
    }
}
