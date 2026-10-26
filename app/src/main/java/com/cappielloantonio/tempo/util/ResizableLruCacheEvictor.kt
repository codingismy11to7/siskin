package com.cappielloantonio.tempo.util

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * media3's least-recently-used policy with a cap that can change after the
 * cache is built. Its own LeastRecentlyUsedCacheEvictor fixes the cap in a
 * final field, and SimpleCache fixes its evictor at construction, so without
 * this a new size could only apply after a restart. See
 * `docs/decisions/2026-10-25-cache-settings-design.md`.
 */
@UnstableApi
class ResizableLruCacheEvictor(
    maxBytes: Long,
) : CacheEvictor {
    @Volatile
    var maxBytes: Long = maxBytes
        private set

    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compare)
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /**
     * Deletes files when the cap shrinks, so call it off the main thread.
     *
     * Holds the cache's monitor: SimpleCache invokes every callback above
     * from inside its own synchronized methods, so that monitor is what keeps
     * this from interleaving with a span being added or touched.
     */
    fun resize(
        cache: Cache,
        maxBytes: Long,
    ) {
        synchronized(cache) {
            this.maxBytes = maxBytes
            evict(cache, 0)
        }
    }

    private fun evict(
        cache: Cache,
        requiredSpace: Long,
    ) {
        while (currentSize + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            cache.removeSpan(leastRecentlyUsed.first())
        }
    }

    private fun compare(
        left: CacheSpan,
        right: CacheSpan,
    ): Int {
        val byTouch = left.lastTouchTimestamp.compareTo(right.lastTouchTimestamp)
        return if (byTouch != 0) byTouch else left.compareTo(right)
    }
}
