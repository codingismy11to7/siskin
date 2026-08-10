package com.cappielloantonio.tempo.provider

import android.content.Context
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import java.io.File

/**
 * Builds and caches the 2x2 cover mosaic behind a decade row.
 *
 * Plex has no composite for a filter value -- measured against PMS 1.43.3, the
 * decade index returns Directory entries with no thumb, composite or art;
 * `/library/sections/{key}/decade/{decade}/composite` 404s; and
 * `/library/sections/{key}/composite/1` is a section-wide mosaic identical for
 * every decade. So the tiling is ours.
 *
 * Kotlin rather than a method on AlbumArtContentProvider because LibraryClient
 * exposes suspend functions and that provider is Java -- the same wall
 * PlexScrobbler exists to cross for MediaManager.java. It also puts the whole
 * feature behind one seam a test can drive without standing up a ContentProvider.
 */
object DecadeCompositeArt {

    private const val TYPE_ALBUM = "album"

    private const val CACHE_DIR = "decade-art"
    private const val CACHE_SUFFIX = ".jpg"

    /** Composites live under cacheDir, so the system may evict them; losing one
     * costs a single rebuild. */
    @JvmStatic
    fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR)

    /**
     * `{sectionKey}-{decade}-{bucket}.jpg`.
     *
     * The section key is in the name because More -> Server Select can switch
     * libraries underneath a cached tile, and the 1980s of one library is not
     * the 1980s of another. The bucket is in the name because that is what makes
     * an hour roll a miss.
     */
    @JvmStatic
    fun cacheFile(context: Context, sectionKey: String, decade: String, bucket: Long): File =
        File(cacheDir(context), "$sectionKey-$decade-$bucket$CACHE_SUFFIX")

    /**
     * Drops composites outside the two live buckets.
     *
     * Only files this class named are considered: a sweep that deleted whatever
     * it could not parse would be a sweep that eventually deletes something
     * else's cache. Steady state is on the order of sixteen small JPEGs.
     */
    @JvmStatic
    fun evictStale(context: Context, nowMs: Long) {
        val files = cacheDir(context).listFiles() ?: return
        files.forEach { file ->
            val bucket = file.name
                .takeIf { it.endsWith(CACHE_SUFFIX) }
                ?.removeSuffix(CACHE_SUFFIX)
                ?.substringAfterLast('-', missingDelimiterValue = "")
                ?.toLongOrNull()
                ?: return@forEach
            if (!CompositeArtBucket.isLive(bucket, nowMs)) file.delete()
        }
    }

    /**
     * The thumb paths to tile, in server order.
     *
     * Filters thumb-less albums rather than leaving a hole, which is what the
     * [CompositeGrid.OVER_FETCH] on the request is for. Reuses
     * [PlexMediaMapper.artworkThumb] rather than reading `thumb` directly, so an
     * album that carries only a parent thumb still contributes a cover.
     */
    @JvmStatic
    fun coverThumbs(response: PlexResponse?, want: Int): List<String> =
        PlexBrowseRepository.itemsOf(response, TYPE_ALBUM)
            .mapNotNull { PlexMediaMapper.artworkThumb(it) }
            .take(want)
}
