package com.cappielloantonio.tempo.util

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import com.cappielloantonio.tempo.R
import java.text.NumberFormat

/** What the Cache size picker offers. See `docs/decisions/2026-10-25-cache-settings-design.md`. */
sealed interface CacheSizeChoice {
    /** Clears the key; [megabytes] is what the derivation gives this partition. */
    data class Automatic(
        val megabytes: Long,
    ) : CacheSizeChoice

    data class Fixed(
        val megabytes: Long,
    ) : CacheSizeChoice

    data object Off : CacheSizeChoice
}

object StreamingCacheChoices {
    val LADDER_MEGABYTES = listOf(512L, 1024L, 2048L, 4096L, 8192L, 16_384L, 32_768L, 65_536L)
    val PRECACHE_COUNTS = listOf(0, 1, 2, 3, 5, 10)

    /**
     * Ladder entries up to half the partition. An unmeasured partition (zero)
     * stops at the derivation's ceiling instead of showing no ladder at all.
     */
    fun cacheSizeChoices(
        automaticMegabytes: Long,
        partitionMegabytes: Long,
    ): List<CacheSizeChoice> {
        val limit = if (partitionMegabytes > 0) partitionMegabytes / 2 else StreamingCacheSize.CEILING_MEGABYTES
        return buildList {
            add(CacheSizeChoice.Automatic(automaticMegabytes))
            LADDER_MEGABYTES.filter { it <= limit }.forEach { add(CacheSizeChoice.Fixed(it)) }
            add(CacheSizeChoice.Off)
        }
    }

    fun current(
        override: Long?,
        automaticMegabytes: Long,
    ): CacheSizeChoice =
        when (override) {
            null -> CacheSizeChoice.Automatic(automaticMegabytes)
            0L -> CacheSizeChoice.Off
            else -> CacheSizeChoice.Fixed(override)
        }

    fun formatSize(
        resources: Resources,
        megabytes: Long,
    ): String {
        val locale = ConfigurationCompat.getLocales(resources.configuration)[0]
        val number = if (locale != null) NumberFormat.getNumberInstance(locale) else NumberFormat.getNumberInstance()
        number.maximumFractionDigits = 1
        return if (megabytes < 1024) {
            resources.getString(R.string.cache_size_megabytes, number.format(megabytes))
        } else {
            resources.getString(R.string.cache_size_gigabytes, number.format(megabytes / 1024.0))
        }
    }

    fun cacheSizeLabel(
        resources: Resources,
        choice: CacheSizeChoice,
    ): String =
        when (choice) {
            is CacheSizeChoice.Automatic -> resources.getString(R.string.cache_size_automatic, formatSize(resources, choice.megabytes))
            is CacheSizeChoice.Fixed -> formatSize(resources, choice.megabytes)
            CacheSizeChoice.Off -> resources.getString(R.string.cache_size_off_option)
        }

    /** [usedBytes] is null until it has been read, which happens off the main thread. */
    fun cacheSizeSummary(
        resources: Resources,
        choice: CacheSizeChoice,
        usedBytes: Long?,
    ): String {
        val size =
            when (choice) {
                CacheSizeChoice.Off -> return resources.getString(R.string.cache_size_off)
                is CacheSizeChoice.Automatic -> resources.getString(R.string.cache_size_automatic, formatSize(resources, choice.megabytes))
                is CacheSizeChoice.Fixed -> formatSize(resources, choice.megabytes)
            }
        if (usedBytes == null) return size
        return resources.getString(R.string.cache_size_used, size, formatSize(resources, usedBytes / (1024 * 1024)))
    }

    private fun trackCount(
        resources: Resources,
        count: Int,
    ): String =
        if (count == 0) {
            resources.getString(R.string.precache_off)
        } else {
            resources.getQuantityString(R.plurals.precache_tracks, count, count)
        }

    fun precacheLabel(
        resources: Resources,
        count: Int,
    ): String {
        val label = trackCount(resources, count)
        return if (count == Preferences.DEFAULT_PRECACHE_TRACKS_COUNT) resources.getString(R.string.precache_default, label) else label
    }

    fun precacheSummary(
        resources: Resources,
        count: Int,
        cacheOff: Boolean,
    ): String {
        val label = trackCount(resources, count)
        return if (cacheOff && count > 0) resources.getString(R.string.precache_paused, label) else label
    }
}
