package com.cappielloantonio.tempo.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.StreamingCacheKeyFactory
import kotlinx.coroutines.runBlocking
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

    private const val TAG = "DecadeCompositeArt"
    private const val JPEG_QUALITY = 85

    private const val TYPE_ALBUM = "album"

    private const val CACHE_DIR = "decade-art"
    private const val CACHE_SUFFIX = ".jpg"
    private const val PARTIAL_SUFFIX = ".partial"

    /**
     * Stands in for [PlexSession.machineIdentifier] when it is absent, so that
     * every identifier-less session shares one cache key instead of being
     * refused -- [PlexSession.machineIdentifier]'s KDoc requires exactly that
     * tolerance. Also the fallback for an identifier that fails
     * [isSafeCacheIdentifier].
     *
     * Cannot collide with a real identifier: [cacheIdentifier] only ever
     * returns this sentinel or a string [isSafeCacheIdentifier] has already
     * restricted to letters and digits, and this is the only one of the two
     * that contains a hyphen -- no all-alphanumeric string can equal it. That
     * holds unconditionally, with no assumption about what a real identifier
     * looks like, and it is only [isSafeCacheIdentifier] being widened to
     * admit `-` itself that could ever put it at risk.
     *
     * Real machine identifiers are also hex (`ConstantsAA.PICK_LIBRARY_ID`
     * documents it), and this string contains 'n', 'o', 'm', 'h' and 'i' --
     * none of them hex digits -- which is a second, independent reason the
     * two can never match, and the one to fall back on if the structural
     * argument above ever stops holding.
     */
    private const val NO_MACHINE_ID = "no-machine-id"

    /**
     * The machine identifier becomes a path component, and it is a value that
     * arrived over the network -- from our own authenticated plex.tv session
     * rather than a caller, but still not a value to trust blind in a
     * filename. Restricted to letters and digits, which is everything a real
     * (hex) identifier ever needs and excludes every path separator and `..`
     * segment a hostile one could try.
     */
    private fun isSafeCacheIdentifier(id: String): Boolean =
        id.isNotEmpty() && id.all(Char::isLetterOrDigit)

    /**
     * [machineIdentifier], normalised for use in a filename: [NO_MACHINE_ID]
     * when it is null or fails [isSafeCacheIdentifier].
     *
     * Every identifier that fails the safety check collapses onto the same
     * sentinel as a null one, so two servers that both happened to hand out
     * an unsafe identifier would share composites -- the bug this file exists
     * to fix, recurring in a corner. Theoretical for real Plex: identifiers
     * are hex, as `ConstantsAA.PICK_LIBRARY_ID` documents.
     * [StreamingCacheKeyFactory] makes the opposite call for its own
     * machine-identifier-keyed cache, falling back to the request origin so a
     * legacy session does not collapse onto every other one -- that fallback
     * is not available here, because a `serverUri` is not filename-safe. The
     * section key still splits most of the space a collision here could
     * cross, and the cost of one is a wrong tile for at most an hour, not a
     * wrong stream, which is why the simpler, coarser fallback is the right
     * call in this file even though it was not in that one.
     */
    private fun cacheIdentifier(machineIdentifier: String?): String =
        machineIdentifier?.takeIf(::isSafeCacheIdentifier) ?: NO_MACHINE_ID

    /** Composites live under cacheDir, so the system may evict them; losing one
     * costs a single rebuild. */
    @JvmStatic
    fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR)

    /**
     * `{machineIdentifier}-{sectionKey}-{decade}-{bucket}.jpg`.
     *
     * The machine identifier is in the name because the section key alone does
     * not say which *server* a tile belongs to: a Plex section key is a small
     * integer -- "1", "4" -- so two different servers each having a music
     * section "4" would otherwise share one cache file. `machineIdentifier` is
     * what says *which* server; see [PlexSession]'s KDoc. The section key stays
     * in the name too, because it is what keeps two libraries *on the same
     * server* apart when More -> Server Select switches between them. The
     * bucket is in the name because that is what makes an hour roll a miss.
     */
    @JvmStatic
    fun cacheFile(
        context: Context,
        machineIdentifier: String?,
        sectionKey: String,
        decade: String,
        bucket: Long
    ): File =
        File(
            cacheDir(context),
            "${cacheIdentifier(machineIdentifier)}-$sectionKey-$decade-$bucket$CACHE_SUFFIX"
        )

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

    /** A composite already on disk for this decade and bucket, or null.
     *
     * Deliberately does no network work and touches neither Glide nor Retrofit:
     * the provider calls this on a binder thread and serves the file directly
     * when it hits, so the common case never occupies a worker at all. */
    @JvmStatic
    fun cached(context: Context, decade: String, bucket: Long): File? {
        val session = PlexApi().session ?: return null
        return cacheFile(
            context, session.machineIdentifier, session.musicSectionKey.value, decade, bucket
        ).takeIf { it.isFile }
    }

    /**
     * Draws the composite and caches it, returning the file, or null if it could
     * not be built.
     *
     * Every failure returns null, and the provider turns that into
     * FileNotFoundException, which the car renders as its own placeholder --
     * which is exactly what a decade row shows without this feature. No failure
     * here is worse than not having shipped it.
     *
     * A 401 deliberately does not raise the sign-in affordance: a ContentProvider
     * has no route to MediaLibraryServiceCallback's PendingIntent, and needs
     * none, because the browse call that produced the list being drawn would
     * have hit the same 401 first and raised it there.
     *
     * Blocking is correct here: the provider calls this on its own executor, off
     * the binder thread, with the result piped back.
     */
    @JvmStatic
    fun build(context: Context, decade: String, bucket: Long): File? {
        val api = PlexApi()
        val session = api.session ?: return null

        // Deduplicated per tile. The car can open one decade concurrently, and
        // until a build renames its file into place every concurrent open is a
        // fresh miss -- N metadata queries and 4N cover transcodes for one
        // image, all made with the user's token. Keyed on the cache file's
        // own name rather than re-interpolating the quadruple that produced
        // it, so the lock key and the filename cannot drift apart. The eight
        // tiles of a first browse still build in parallel, and so do two
        // different servers' builds of what happens to be the same section
        // key; see CompositeBuildLocks for why that matters and why its map
        // does not grow.
        val file = cacheFile(
            context, session.machineIdentifier, session.musicSectionKey.value, decade, bucket
        )
        return CompositeBuildLocks.exclusively(file.name) {
            // Re-checked after acquiring, against the same session snapshot
            // the lock key and buildLocked's write use -- not cached(), which
            // re-reads PlexApi().session fresh and would check a different
            // server's or section's filename than the winner wrote if More ->
            // Server Select switched libraries while this thread waited on
            // the lock. When the build succeeds, this is what turns N
            // concurrent opens into one build and N-1 hits: whoever waited
            // here was waiting for exactly the file the winner has now
            // written. A decade with no albums caches nothing, though, so a
            // miss here still costs each waiter its own metadata query, just
            // serialised behind the lock rather than run in parallel.
            file.takeIf { it.isFile } ?: buildLocked(context, api, session, decade, bucket)
        }
    }

    /**
     * [build]'s body, run holding that tile's lock.
     *
     * Split out only so `build` can express the lock and the re-check in a
     * couple of lines; every failure contract described on [build] is this
     * function's, and the bitmap is recycled on every exit from it.
     */
    private fun buildLocked(
        context: Context,
        api: PlexApi,
        session: PlexSession,
        decade: String,
        bucket: Long
    ): File? {
        val section = session.musicSectionKey

        val response = try {
            runBlocking {
                // Pinned to the session snapshot build() took, not the
                // one-argument LibraryClient(api) constructor: that reads
                // api.serverUri/serverToken fresh from preferences, which can
                // race a library switch on More -> Server Select and pair this
                // section key with a different server's address mid-build.
                LibraryClient(api, session.serverUri, session.serverToken).getSectionContent(
                    sectionKey = section,
                    type = PlexItemType.ALBUM,
                    start = 0,
                    size = CompositeGrid.OVER_FETCH,
                    sort = LibraryClient.SORT_RANDOM,
                    albumDecade = decade
                )
            }.getOrNull()
        } catch (e: Exception) {
            // plexCall catches only IOException/HttpException; a malformed
            // response body (Gson JsonSyntaxException) or a mapping bug is a
            // RuntimeException that would otherwise escape build() and break
            // its contract that every failure is a null, never a throw.
            // Outside any either { } block, so there is no Arrow raise here
            // to swallow.
            Log.w(TAG, "could not fetch section content for $decade", e)
            null
        } ?: return null

        val thumbs = coverThumbs(response, CompositeGrid.COVERS)
        val cells = CompositeGrid.cells(thumbs.size, CompositeGrid.SIZE)
        if (cells.isEmpty()) return null

        val token = PlexApi.serverTokenOrAccount(session.serverToken, session.accountToken)
        val cellEdge = CompositeGrid.SIZE / if (cells.size == 1) 1 else 2

        val covers = thumbs.take(cells.size).mapNotNull { thumb ->
            val url = MediaUrlBuilder.artworkUrl(
                session.serverUri, thumb, token, cellEdge, cellEdge
            ) ?: return@mapNotNull null
            loadCover(context, url, cellEdge)
        }
        if (covers.size != cells.size) return null

        val composite = Bitmap.createBitmap(
            CompositeGrid.SIZE, CompositeGrid.SIZE, Bitmap.Config.RGB_565
        )
        val file = cacheFile(context, session.machineIdentifier, section.value, decade, bucket)
        // Hoisted above the try so the catch below can clean it up too: with
        // the declaration inside the try, an exception thrown before a
        // successful rename -- including from createTempFile itself -- left
        // that attempt's partial on disk forever, since evictStale only
        // recognises names ending CACHE_SUFFIX and each attempt's partial is
        // uniquely named.
        var partial: File? = null
        return try {
            // Drawing sits inside the same try as the write so that a draw that
            // throws -- a cover Glide has since recycled is the way that happens
            // -- still returns null and still recycles, rather than throwing out
            // of a function whose contract is that failure is a null.
            val canvas = Canvas(composite)
            cells.forEachIndexed { index, cell ->
                canvas.drawBitmap(
                    covers[index],
                    null,
                    Rect(cell.left, cell.top, cell.right, cell.bottom),
                    null
                )
            }

            file.parentFile?.mkdirs()
            // Written to a sibling and renamed, so a reader can never open a
            // half-drawn composite: the provider's hit path only checks that the
            // file exists. The sibling is unique per attempt, not just per
            // destination: this is an exported ContentProvider served on a
            // thread pool, and the car can open the same decade tile
            // concurrently, so two builds for the same decade and bucket can
            // run at once. A shared partial name would let their writes
            // interleave, and whichever rename ran last would publish a
            // corrupt JPEG under the real cache name, where it would sit for
            // the rest of the bucket's hour -- evictStale only reaps files it
            // named, so a corrupt-but-correctly-named composite is invisible
            // to it. A unique partial turns that race into last-writer-wins
            // between two *complete* files instead of a race over one buffer.
            val target = File.createTempFile(file.name, PARTIAL_SUFFIX, file.parentFile)
            partial = target
            // Bitmap.compress reports a write failure -- a full disk mid-encode
            // is the way that happens -- by returning false rather than
            // throwing: the native encoder's Java-stream adaptor absorbs the
            // IOException, and FileOutputStream.close() does not throw on a
            // full disk either. Without checking it, a truncated or zero-byte
            // partial renames cleanly and is served as the composite for the
            // rest of the bucket's hour, since cached() only stats the file.
            val wrote = target.outputStream().use {
                composite.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            }
            if (!wrote || !target.renameTo(file)) {
                // A failed compress or a failed rename must not orphan the
                // partial: evictStale never sweeps it, correctly, since the
                // sweep only touches files it named itself. Delete it
                // explicitly and fail the build like every other failure
                // path here.
                target.delete()
                null
            } else {
                evictStale(context, System.currentTimeMillis())
                file.takeIf { it.isFile }
            }
        } catch (e: Exception) {
            // Outside any either { } block, so there is no Arrow raise for this
            // to swallow.
            Log.w(TAG, "could not cache the composite for $decade", e)
            partial?.delete()
            null
        } finally {
            composite.recycle()
        }
    }

    /** Data-saving mode is honoured exactly as the album path honours it. The
     * preference it reads is frozen at false -- the settings screen that set it
     * is gone -- so the branch is unreachable today and is kept only so the two
     * artwork paths cannot drift apart.
     *
     * centerCrop because canvas.drawBitmap passes a null source rect, which maps
     * whatever arrives onto the whole cell: submit(edge, edge) only downsamples
     * and preserves aspect ratio, so an oblong cover would be squashed square
     * rather than cropped. Plex covers are square in practice, so this removes
     * a case rather than fixing a visible defect. */
    private fun loadCover(context: Context, url: String, edge: Int): Bitmap? = try {
        var request = Glide.with(context)
            .asBitmap()
            .load(url)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.DATA)
        if (Preferences.isDataSavingMode()) {
            request = request.onlyRetrieveFromCache(true)
        }
        request.submit(edge, edge).get()
    } catch (e: Exception) {
        Log.w(TAG, "could not load a cover for the composite", e)
        null
    }
}
