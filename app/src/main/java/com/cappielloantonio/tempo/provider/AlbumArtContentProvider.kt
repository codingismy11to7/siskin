package com.cappielloantonio.tempo.provider

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.artwork.ArtworkImages
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder
import com.cappielloantonio.tempo.util.HubCoverPool
import com.cappielloantonio.tempo.util.Preferences
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AlbumArtContentProvider : ContentProvider() {
    private var executor: ExecutorService? = null

    @Throws(FileNotFoundException::class)
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        // uriMatcher was declared and never consulted while there was only one
        // path: openFile read getLastPathSegment() and assumed the album shape.
        // A second path is what makes it load-bearing -- reading the decade off
        // the last segment would silently pick up the bucket instead.
        return when (uriMatcher.match(uri)) {
            MATCH_ALBUM_ART -> openAlbumArt(uri)
            MATCH_DECADE_ART -> openDecadeArt(uri)
            MATCH_HUB_ART -> openHubArt(uri)
            else -> throw FileNotFoundException("Unrecognised artwork URI")
        }
    }

    private fun openAlbumArt(uri: Uri): ParcelFileDescriptor {
        val context = attachedContext()

        // The last path segment is a Plex thumb path such as
        // /library/metadata/12345/thumb/1699999999. contentUri() percent-encodes its
        // separators and getLastPathSegment() decodes them, so the multi-segment path
        // arrives whole rather than truncated to its final component.
        val thumbPath = uri.lastPathSegment

        // This provider is exported -- the car reads artwork through it -- so the
        // path segment is attacker-controlled: any app on the head unit can ask
        // for any URI under this authority. MediaUrlBuilder.artworkUrl embeds the
        // segment as `url=` on Plex's photo transcoder, which will fetch whatever
        // that names, absolute URLs on other hosts included. Unvalidated, that
        // makes the user's Plex server a proxy the caller can point at anything
        // reachable from it, authenticated with the user's own token.
        //
        // A genuine Plex thumb is always a server-relative path, so anything else
        // is refused the same way an absent one is: FileNotFoundException, which
        // the car renders as the placeholder icon.
        if (thumbPath == null || !MediaUrlBuilder.isServerRelativePath(thumbPath)) {
            throw FileNotFoundException("Not a Plex artwork path")
        }

        val size = if (Preferences.getImageSize() > 0) Preferences.getImageSize() else DEFAULT_ARTWORK_SIZE

        val api = PlexApi()
        val artworkUrl =
            MediaUrlBuilder.artworkUrl(api.serverUri, thumbPath, api.serverAuthToken, size, size)
                // Null means no server or no token yet -- there is no image to serve, and
                // reporting that is what makes the car fall back to the placeholder icon
                // instead of stalling on a pipe that never gets written.
                ?: throw FileNotFoundException("No Plex artwork URL for $thumbPath")

        return pipe(thumbPath) { out ->
            ArtworkImages.get(context).writeTo(artworkUrl, Preferences.isDataSavingMode(), out)
            true
        }
    }

    /**
     * The decade composite. Its validation differs from openAlbumArt's, and deliberately: no
     * caller-supplied path reaches MediaUrlBuilder on *this* route -- the covers come from our own
     * Plex response -- so the open-proxy hazard that path guards against cannot arise here. That is
     * a property of this method and not of this provider: openHubArt below takes its covers from
     * the URI, and guards them the way openAlbumArt guards its thumb. What this path needs instead
     * is a bound on how much work a caller can ask for, because every cache miss is a Plex request
     * made with the user's token, and an answer to "is this URI even about the library we are
     * pointed at now".
     */
    private fun openDecadeArt(uri: Uri): ParcelFileDescriptor {
        val context = attachedContext()
        val segments = uri.pathSegments
        val decade = segments[2]

        if (!DECADE.matches(decade)) {
            throw FileNotFoundException("Not a decade")
        }

        val bucket =
            try {
                segments[3].toLong()
            } catch (_: NumberFormatException) {
                throw FileNotFoundException("Not a bucket")
            }
        if (!CompositeArtBucket.isLive(bucket, System.currentTimeMillis())) {
            throw FileNotFoundException("Stale composite bucket")
        }

        // A URI minted for a server the user has since left. Serving it would
        // hand back the previous library's mosaic -- the bug this segment
        // exists to fix, arriving from the other direction -- and the tile it
        // actually asks for is one we can no longer build. Signed out is the
        // same answer for the same reason: no session, nothing to honour a
        // scope against and nothing to draw.
        //
        // Unlike the decade above, this segment never reaches a filename: it is
        // compared for equality against a locally computed string and used for
        // nothing else, and the composite below is still named from the session
        // rather than from anything the caller sent. That is what makes a
        // charset guard unnecessary here rather than merely absent.
        //
        // Known window, named rather than closed: currentScope() reads the
        // session here and DecadeCompositeArt.cached() reads it again below, so
        // a library switch landing between the two lets a URI validated against
        // the old scope be answered out of the new session's cache file. It is
        // microseconds wide and costs one wrong tile until the browse list
        // behind it is re-fetched, which a library switch provokes anyway.
        // Closing it means threading one session snapshot through both, which
        // is what build() already does for itself -- see the snapshot it pins
        // for its lock key -- and is worth doing when this file becomes Kotlin
        // (#86) rather than growing a second session-shaped Java parameter now.
        val scope = CompositeArt.currentScope()
        if (scope == null || scope != segments[1]) {
            throw FileNotFoundException("Not this library's composite")
        }

        // The hit path does no background work at all: no cover fetch, no Retrofit, no
        // executor thread. Every Decade Radio tile asks at once against a pool
        // sized max(2, cores / 2), and only the first browse in an hour should
        // pay for a build.
        val cached = DecadeCompositeArt.cached(context, decade, bucket)
        if (cached != null) {
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        return pipeFrom(decade) { DecadeCompositeArt.build(context, decade, bucket) }
    }

    /**
     * The hub composite. Its validation differs from both paths above.
     *
     * Cheaper to serve than a decade tile and cheaper to abuse: there is no Plex request on this
     * route at all, so a hostile open costs cover fetches and no metadata query -- the opposite of
     * the residual openDecadeArt concedes, where a well-formed but absent decade costs one query
     * per open forever.
     *
     * What it does need is a bound on amplification. One open here can trigger seven
     * authenticated fetches where openAlbumArt triggers one -- up to HubCoverPool.MAX candidate
     * loads, plus the full-edge re-request CompositeArt's degraded branch makes when a pool of four
     * or more lands fewer than four covers and the layout falls back to one cell -- which is what
     * the pool cap is for. The open-proxy hazard itself is openAlbumArt's, not a new one: any app
     * on the head unit has always been able to ask this authority for any server-relative Plex
     * path, behind this same guard.
     *
     * One residual is worth naming rather than leaving for someone to rediscover: a hostile
     * caller can mint many distinct valid pools out of real thumb paths -- reordering six real
     * thumbs alone is hundreds of pools -- and cause a small JPEG per pool to be written under this
     * bucket, since evictStale reaps only stale buckets, not a live bucket's volume. That is
     * app-private storage in a system-evictable cacheDir, no worse than the same app filling its
     * own cache directly, so it is not treated as a bug here -- just recorded so it reads as
     * considered rather than missed.
     */
    private fun openHubArt(uri: Uri): ParcelFileDescriptor {
        val context = attachedContext()
        val segments = uri.pathSegments

        val bucket =
            try {
                segments[2].toLong()
            } catch (_: NumberFormatException) {
                // `#` restricts the segment to digits but not to a digit run that
                // fits in a long, which is the case this catch is for.
                throw FileNotFoundException("Not a bucket")
            }
        if (!CompositeArtBucket.isLive(bucket, System.currentTimeMillis())) {
            throw FileNotFoundException("Stale composite bucket")
        }

        // The same guard, the same known window as openDecadeArt's: currentScope()
        // reads the session here and HubCompositeArt.cached() reads it again
        // below, so a library switch landing between the two lets a URI validated
        // against the old scope be answered out of the new session's cache file.
        // Microseconds wide, one wrong tile until the list behind it is
        // re-fetched, which a library switch provokes anyway.
        val scope = CompositeArt.currentScope()
        if (scope == null || scope != segments[1]) {
            throw FileNotFoundException("Not this library's composite")
        }

        val pool = HubCoverPool.decode(segments[3])
        // pool.isEmpty() is not reachable today -- HubCoverPool.decode is
        // split(",") and never returns an empty list, not even for "", which
        // HubCoverPoolTest pins as decode("") == listOf(""). It is kept as
        // defence against a future decode() that could return one, not as a
        // condition this guard is currently relied on to catch.
        if (pool.isEmpty() || pool.size > HubCoverPool.MAX) {
            throw FileNotFoundException("Not a cover pool")
        }
        for (thumb in pool) {
            // One bad entry refuses the whole URI rather than being filtered
            // out: filtering would let a caller pair one real thumb with five
            // probes and still be handed a tile, and refusing whole costs real
            // traffic nothing, because a pool this app minted is valid in every
            // position by construction.
            if (!MediaUrlBuilder.isServerRelativePath(thumb)) {
                throw FileNotFoundException("Not a Plex artwork path")
            }
        }

        val cached = HubCompositeArt.cached(context, pool, bucket)
        if (cached != null) {
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // The pool's digest, not the HUB_ART literal, so a build failure names
        // which composite failed in the log -- pipeFrom's label is shared with
        // openDecadeArt, which passes the decade for the same reason.
        return pipeFrom(HubCompositeArt.idFor(pool)) { HubCompositeArt.build(context, pool, bucket) }
    }

    private fun attachedContext(): Context = context ?: throw FileNotFoundException("Provider not attached")

    /** Writes one image into a pipe, or returns false when there is none to write. */
    internal fun interface ImageWriter {
        fun writeTo(out: OutputStream): Boolean
    }

    /** [pipe] for a composite, which is built to a file. */
    @VisibleForTesting
    internal fun pipeFrom(
        label: String,
        source: () -> File?,
    ): ParcelFileDescriptor =
        pipe(label) { out ->
            val file = source() ?: return@pipe false
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
            true
        }

    /**
     * The read end of a pipe that [writer] fills on the background executor.
     *
     * Shared by both artwork paths: one copies a disk-cached cover, the other a composite it
     * builds. The caller gets its descriptor immediately -- openFile runs on a binder thread, and
     * neither a Plex round trip nor a cover fetch may happen there.
     *
     * A writer returning false, or throwing, closes the write side with an error, which the car
     * renders as the placeholder icon.
     */
    @VisibleForTesting
    internal fun pipe(
        label: String,
        writer: ImageWriter,
    ): ParcelFileDescriptor {
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            executor!!.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                        if (!writer.writeTo(out)) {
                            writeSide.closeWithError("No artwork for $label")
                        }
                    }
                } catch (e: Exception) {
                    try {
                        writeSide.closeWithError("Failed to load image: ${e.message}")
                    } catch (_: IOException) {
                    }
                }
            }

            return readSide
        } catch (e: IOException) {
            throw FileNotFoundException("Could not create pipe: ${e.message}")
        }
    }

    override fun onCreate(): Boolean {
        executor = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
        return true
    }

    override fun shutdown() {
        val executor = executor ?: return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = ""

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".albumart.provider"
        const val ALBUM_ART = "albumArt"
        const val DECADE_ART = "decadeArt"
        const val HUB_ART = "hubArt"

        private const val MATCH_ALBUM_ART = 1
        private const val MATCH_DECADE_ART = 2
        private const val MATCH_HUB_ART = 3

        /**
         * A decade key must be four digits. It becomes part of a cache filename, and what the guard
         * buys is exactly two properties: the segment is digits only, so no decoded `/`, no `..` and no
         * separator of any kind reaches the filename CompositeArt.cacheFile interpolates; and it is a
         * fixed-width run, so there is no length to play with either. matches() anchors the whole
         * segment rather than a prefix, which is what makes both properties hold of the segment and not
         * merely of a prefix of it.
         *
         * Deliberately not narrowed to `(19|20)\d{2}`. The smaller filename space that buys does no
         * work: nothing is ever cached for a decade that yields no albums, so the bogus names are never
         * written, and a caller wanting to burn Plex queries can loop the 200 allowed-but-absent values
         * as happily as 10,000. Meanwhile it refuses a genuine pre-1900 key -- an 1890s classical or
         * historical album -- which a real library can produce.
         *
         * The residual is the same either way, and is named here rather than oversold: a well-formed
         * but absent decade still costs one Plex query per open, because DecadeCompositeArt.build()
         * returns null before writing anything and so leaves nothing cached to answer the next request.
         */
        private val DECADE = Regex("\\d{4}")

        // Plex's photo transcoder requires both dimensions. The image-size preference
        // this used to read is frozen at its "-1" sentinel -- the settings screen that
        // set it is gone -- so anything non-positive falls back to a size that fills a
        // car browse tile without fetching a full-resolution cover for every row.
        private const val DEFAULT_ARTWORK_SIZE = 512

        private val uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply {
                addURI(AUTHORITY, "$ALBUM_ART/*", MATCH_ALBUM_ART)
                // scope / decade / bucket. The arity is the guard: openDecadeArt reads
                // each of the three by index, so a URI of any other shape must not
                // reach it at all.
                addURI(AUTHORITY, "$DECADE_ART/*/*/#", MATCH_DECADE_ART)
                // scope / bucket / pool. Fixed arity again, and the pool is one segment
                // rather than one per thumb: UriMatcher has no repeating wildcard, so
                // the alternative was six near-identical rules with the cap on how many
                // covers a caller may demand emerging from the rule set instead of
                // being stated in openHubArt. See HubCoverPool.
                addURI(AUTHORITY, "$HUB_ART/*/#/*", MATCH_HUB_ART)
            }

        fun contentUri(artworkId: String): Uri =
            Uri
                .Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(ALBUM_ART)
                .appendPath(artworkId)
                .build()

        /**
         * The composite for one decade, in one library, for one hour.
         *
         * All three parts are in the URI for a single reason: the car caches artwork by URI, so
         * anything that has to invalidate a tile has to be visible here. The bucket covers time.
         * `scope` -- [CompositeArt.scopeOf] -- covers *which library*, and it was the missing one:
         * "1980s" on two servers minted byte-identical URIs, so after a switch under More -> Server
         * Select the car re-served the old server's mosaic out of its own cache and this provider was
         * never opened at all. Keying the cache file by server, which is where that bug was first
         * chased, cannot fix what never reaches the file.
         */
        fun decadeContentUri(
            scope: String,
            decade: String,
            bucket: Long,
        ): Uri =
            Uri
                .Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(DECADE_ART)
                .appendPath(scope)
                .appendPath(decade)
                .appendPath(bucket.toString())
                .build()

        /**
         * The composite for one Discover row, in one library, for one hour.
         *
         * Unlike [decadeContentUri], this URI names its own covers, so it is already
         * content-addressed: a re-rolled hub changes the pool and therefore the URI. The bucket is here
         * anyway, for a narrower reason than it has there. A tile that degraded because a cover failed
         * to load would otherwise sit in the car's own image cache under a URI nothing invalidates;
         * rolling the bucket is what lets it redraw.
         *
         * The pool costs no Plex request. It is the six items the hub listing already returned --
         * the ones the row's existence was decided from.
         */
        fun hubContentUri(
            scope: String,
            bucket: Long,
            pool: List<String>,
        ): Uri =
            Uri
                .Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(HUB_ART)
                .appendPath(scope)
                .appendPath(bucket.toString())
                .appendPath(HubCoverPool.encode(pool))
                .build()
    }
}
