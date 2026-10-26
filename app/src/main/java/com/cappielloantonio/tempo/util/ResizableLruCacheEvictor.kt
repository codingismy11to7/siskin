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
 *
 * Unlike the stock evictor it never removes a span whose key [isOpen] reports
 * as being read. LRU order alone almost never reaches the playing track, but a
 * trim to zero always would, and a transcoded track that loses its spans
 * mid-read resumes with a range request against a fresh transcode.
 */
@UnstableApi
class ResizableLruCacheEvictor(
    maxBytes: Long,
    private val isOpen: (String) -> Boolean = { false },
    private val resizeBatch: Int = 64,
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
     * [cache] must be the SimpleCache this evictor was built into.
     */
    fun resize(
        cache: Cache,
        maxBytes: Long,
    ) {
        this.maxBytes = maxBytes
        trim(cache)
    }

    /**
     * Evicts down to the current cap, which picks up spans whose key has
     * stopped being read since the last eviction.
     *
     * In batches, each under the cache's monitor: SimpleCache invokes every
     * callback above from inside its own synchronized methods, so that monitor
     * is what keeps this from interleaving with a span being added or touched.
     * Releasing it between batches lets the playing stream's reads and writes
     * through during a trim of thousands of files.
     */
    fun trim(cache: Cache) {
        do {
            val more = synchronized(cache) { evict(cache, 0, resizeBatch) }
        } while (more)
    }

    /** True when it stopped at [limit] with more still to remove. */
    private fun evict(
        cache: Cache,
        requiredSpace: Long,
        limit: Int = Int.MAX_VALUE,
    ): Boolean {
        var removed = 0
        while (currentSize + requiredSpace > maxBytes) {
            val victim = leastRecentlyUsed.firstOrNull { !isOpen(it.key) } ?: return false
            if (removed == limit) return true
            cache.removeSpan(victim)
            // removeSpan returns without calling back when SimpleCache no
            // longer holds the span; without this the loop would spin on it.
            if (victim in leastRecentlyUsed) return false
            removed++
        }
        return false
    }

    private fun compare(
        left: CacheSpan,
        right: CacheSpan,
    ): Int {
        val byTouch = left.lastTouchTimestamp.compareTo(right.lastTouchTimestamp)
        return if (byTouch != 0) byTouch else left.compareTo(right)
    }
}
