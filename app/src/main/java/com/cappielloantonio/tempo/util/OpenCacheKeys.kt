package com.cappielloantonio.tempo.util

/**
 * Cache keys a [StreamingCacheDataSource] currently has open, which
 * [ResizableLruCacheEvictor] will not evict.
 *
 * Counted, because the player can hold two sources on one key: a seek, or a
 * track queued twice.
 */
object OpenCacheKeys {
    private val counts = HashMap<String, Int>()

    @JvmStatic
    fun open(key: String) {
        synchronized(counts) { counts[key] = (counts[key] ?: 0) + 1 }
    }

    /** True when this was the last reader of [key]. */
    @JvmStatic
    fun close(key: String): Boolean =
        synchronized(counts) {
            val remaining = (counts[key] ?: return false) - 1
            if (remaining == 0) counts.remove(key) else counts[key] = remaining
            remaining == 0
        }

    @JvmStatic
    fun isOpen(key: String): Boolean = synchronized(counts) { key in counts }
}
