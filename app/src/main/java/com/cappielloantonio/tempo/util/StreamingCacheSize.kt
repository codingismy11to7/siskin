package com.cappielloantonio.tempo.util

import android.os.StatFs
import java.io.File

/**
 * How large the streaming cache may grow, in megabytes, when the user has set
 * no size of their own.
 *
 * Derived from the partition's **total** size rather than its free space, and
 * that is the whole design. A cache sized as a share of what is free shrinks
 * every time it is measured: the bytes it already holds are not free, so each
 * process start reads a smaller figure, computes a smaller cap, and the evictor
 * trims to it -- a ratchet that reaches zero within days on a head unit whose
 * service restarts constantly. Total is a fixed input, so it cannot feed back.
 * See `docs/decisions/2026-08-29-streaming-cache-sizing-design.md`.
 */
object StreamingCacheSize {
    /** Share of the partition the cache may occupy. */
    private const val SHARE_DIVISOR = 10L

    /**
     * The floor is a floor, not an override: on a partition small enough that
     * [FLOOR_MEGABYTES] would be a large fraction of it, this wins instead.
     */
    private const val SMALL_PARTITION_DIVISOR = 4L

    private const val BYTES_PER_MEGABYTE = 1024L * 1024L

    const val FLOOR_MEGABYTES = 1024L
    const val CEILING_MEGABYTES = 8192L

    /**
     * The size an unset preference resolves to for [directory]'s partition.
     *
     * An unmeasurable partition yields [FLOOR_MEGABYTES] rather than zero: zero
     * is how the preference spells "cache nothing", so returning it here would
     * turn a failed `statfs` into a silent loss of caching altogether.
     */
    @JvmStatic
    fun forDirectory(directory: File): Long = fromTotalMegabytes(totalMegabytes(directory))

    /** Split out from [forDirectory] so the policy is testable without a filesystem. */
    @JvmStatic
    fun fromTotalMegabytes(totalMegabytes: Long): Long {
        if (totalMegabytes <= 0L) return FLOOR_MEGABYTES

        val share = (totalMegabytes / SHARE_DIVISOR).coerceIn(FLOOR_MEGABYTES, CEILING_MEGABYTES)
        return minOf(share, totalMegabytes / SMALL_PARTITION_DIVISOR)
    }

    /**
     * `StatFs` throws on a path that does not exist, and the cache directory is
     * created lazily by `SimpleCache` -- so measure the nearest ancestor that is
     * already there. It is the same partition either way.
     */
    private fun totalMegabytes(directory: File): Long {
        var candidate: File? = directory
        while (candidate != null && !candidate.exists()) {
            candidate = candidate.parentFile
        }

        val path = candidate?.path ?: return 0L

        return try {
            StatFs(path).totalBytes / BYTES_PER_MEGABYTE
        } catch (exception: IllegalArgumentException) {
            0L
        }
    }
}
