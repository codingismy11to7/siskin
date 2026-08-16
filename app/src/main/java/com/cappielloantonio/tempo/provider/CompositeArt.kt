package com.cappielloantonio.tempo.provider

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.StreamingCacheKeyFactory
import java.io.File

private const val TAG = "CompositeArt"

object CompositeArt {

    /** Not private: [DecadeCompositeArt.buildLocked]'s JPEG encode reads this
     * directly, and that build loop has not moved here (yet). */
    const val JPEG_QUALITY = 85

    /**
     * One directory for every kind of composite, so one [evictStale] sweep
     * covers them all.
     *
     * Renamed from `decade-art` when hub composites arrived. That orphans
     * whatever a previous install left under the old name -- at most ~16 small
     * JPEGs, in a system-evictable cacheDir, never swept again. Deleting it
     * once was considered and judged not worth code that would run on every
     * successful build forever.
     */
    private const val CACHE_DIR = "composite-art"
    private const val CACHE_SUFFIX = ".jpg"

    /** Not private, for the same reason as [JPEG_QUALITY]:
     * [DecadeCompositeArt.buildLocked]'s write-then-rename reads this
     * directly. */
    const val PARTIAL_SUFFIX = ".partial"

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
     * Real machine identifiers are also hex (`Constants.PICK_LIBRARY_ID`
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
     * are hex, as `Constants.PICK_LIBRARY_ID` documents.
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

    /**
     * Which server and which library a composite belongs to, as the one string
     * both sides of the `decadeArt` URI use.
     *
     * Built from the same two values as the first two fields of [cacheFile]'s
     * name -- the normalised machine identifier and the section key -- so the
     * URI a row is minted with and the file it resolves to cannot disagree
     * about what "this library" means. `AlbumArtContentProvider` mints with it
     * and checks against it; one definition is what stops those two drifting.
     *
     * **It is never a filename input, and must not become one.** The provider
     * compares an incoming segment against this string for equality and does
     * nothing else with it; the cache file goes on being named from the
     * session directly. That is the whole reason the segment needs no charset
     * guard of its own -- unlike the machine identifier, which
     * [isSafeCacheIdentifier] restricts precisely because it *does* reach a
     * filename. Interpolating an incoming scope into a path would hand an
     * exported provider the traversal surface both that guard and the
     * provider's `\d{4}` decade rule exist to close.
     */
    @JvmStatic
    fun scopeOf(session: PlexSession): String =
        "${cacheIdentifier(session.machineIdentifier)}-${session.musicSectionKey.value}"

    /**
     * [scopeOf] the session in force right now, or null when there is none.
     *
     * A null means no URI can be honoured rather than every URI can: with no
     * session there is nothing to compare a scope against and nothing to
     * build either.
     */
    @JvmStatic
    fun currentScope(): String? = PlexApi().session?.let(::scopeOf)

    /** Composites live under cacheDir, so the system may evict them; losing one
     * costs a single rebuild. */
    @JvmStatic
    fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR)

    /**
     * `{machineIdentifier}-{sectionKey}-{id}-{bucket}.jpg`.
     *
     * [id] is what distinguishes one composite from another within a library:
     * a decade string for `decadeArt`, a digest of the cover pool for
     * `hubArt`. Both are constrained to filename-safe characters before they
     * arrive -- the decade by the provider's `\d{4}` rule, the digest by being
     * hex -- so this function does no charset work of its own.
     *
     * The machine identifier is in the name because the section key alone does
     * not say which *server* a tile belongs to: a Plex section key is a small
     * integer -- "1", "4" -- so two different servers each having a music
     * section "4" would otherwise share one cache file. `machineIdentifier` is
     * what says *which* server; see [PlexSession]'s KDoc. The section key stays
     * in the name too, because it is what keeps two libraries *on the same
     * server* apart when More -> Server Select switches between them. The
     * bucket is in the name because that is what makes an hour roll a miss.
     *
     * Those first two fields are exactly what [scopeOf] names, and that is not
     * a coincidence to be tidied away: the URI has to change on the same axes
     * this filename does, or the car serves a tile the file no longer stands
     * for. Naming the file is this function's job alone, though -- see
     * [scopeOf] on why the URI's copy must never be interpolated into a path.
     */
    @JvmStatic
    fun cacheFile(
        context: Context,
        machineIdentifier: String?,
        sectionKey: String,
        id: String,
        bucket: Long
    ): File =
        File(
            cacheDir(context),
            "${cacheIdentifier(machineIdentifier)}-$sectionKey-$id-$bucket$CACHE_SUFFIX"
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

    /** A composite already on disk for this decade and bucket, or null.
     *
     * Deliberately does no network work and touches neither Glide nor Retrofit:
     * the provider calls this on a binder thread and serves the file directly
     * when it hits, so the common case never occupies a worker at all. */
    @JvmStatic
    fun cached(context: Context, id: String, bucket: Long): File? {
        val session = PlexApi().session ?: return null
        return cacheFile(
            context, session.machineIdentifier, session.musicSectionKey.value, id, bucket
        ).takeIf { it.isFile }
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
    fun loadCover(context: Context, url: String, edge: Int): Bitmap? = try {
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
