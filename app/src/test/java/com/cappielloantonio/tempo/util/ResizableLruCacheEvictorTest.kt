package com.cappielloantonio.tempo.util

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Against a real SimpleCache, because the evictor's whole contract is how it
 * drives one: which spans removeSpan is called on, and when.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ResizableLruCacheEvictorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var evictor: ResizableLruCacheEvictor
    private lateinit var cache: SimpleCache

    @Before
    fun setUp() {
        evictor = ResizableLruCacheEvictor(maxBytes = 300)
        cache =
            SimpleCache(
                folder.newFolder("cache"),
                evictor,
                StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()),
            )
    }

    @After
    fun tearDown() {
        cache.release()
    }

    /** Sleeps so each write gets a later touch timestamp than the last. */
    private fun write(
        key: String,
        bytes: Int,
    ) {
        Thread.sleep(5)
        val source = CacheDataSource(cache, ByteArrayDataSource(ByteArray(bytes)))
        val spec =
            DataSpec
                .Builder()
                .setUri(Uri.parse("https://example.invalid/$key"))
                .setKey(key)
                .build()
        CacheWriter(source, spec, null, null).cache()
    }

    @Test
    fun aWritePastTheCapEvictsTheOldest() {
        write("a", 100)
        write("b", 100)
        write("c", 100)
        write("d", 100)

        assertEquals(setOf("b", "c", "d"), cache.keys)
    }

    @Test
    fun loweringTheCapTrimsOldestFirst() {
        write("a", 100)
        write("b", 100)
        write("c", 100)

        evictor.resize(cache, 150)

        assertEquals(setOf("c"), cache.keys)
        assertEquals(150L, evictor.maxBytes)
    }

    @Test
    fun raisingTheCapAdmitsMoreWithoutEvicting() {
        write("a", 100)
        write("b", 100)
        write("c", 100)

        evictor.resize(cache, 500)
        write("d", 100)
        write("e", 100)

        assertEquals(setOf("a", "b", "c", "d", "e"), cache.keys)
    }

    @Test
    fun zeroEmptiesTheCache() {
        write("a", 100)
        write("b", 100)

        evictor.resize(cache, 0)

        assertEquals(0L, cache.cacheSpace)
        assertEquals(emptySet<String>(), cache.keys)
    }
}
