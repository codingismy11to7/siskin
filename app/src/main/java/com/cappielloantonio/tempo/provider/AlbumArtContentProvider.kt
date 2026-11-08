package com.cappielloantonio.tempo.provider

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import arrow.core.getOrElse
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.artwork.ArtworkImages
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder
import com.cappielloantonio.tempo.util.HubCoverPool
import com.cappielloantonio.tempo.util.Preferences
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Serves the car its artwork: album covers, and the decade and hub composites.
 *
 * Exported, so what it will serve is decided by [ArtRequest.parse] before
 * anything else happens.
 */
class AlbumArtContentProvider : ContentProvider() {
    private lateinit var executor: ExecutorService

    /** Where an open reads its one session snapshot. A test swaps it to stand
     * in for a library switch landing after the read. */
    @VisibleForTesting
    internal var readSession: () -> PlexSession? = { PlexApi().session }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val context = attachedContext()
        val request =
            ArtRequest
                .parse(uri, readSession, System.currentTimeMillis())
                .getOrElse { throw FileNotFoundException(it) }

        return when (request) {
            is ArtRequest.Album -> {
                openAlbumArt(context, request)
            }

            // The hit path does no background work at all: no cover fetch, no Retrofit, no
            // executor thread. Every Decade Radio tile asks at once against a pool
            // sized max(2, cores / 2), and only the first browse in an hour should
            // pay for a build.
            is ArtRequest.Decade -> {
                with(request) {
                    DecadeCompositeArt.cached(context, session, decade, bucket)?.let(::openReadOnly)
                        ?: pipeFrom(decade) { DecadeCompositeArt.build(context, session, decade, bucket) }
                }
            }

            // The pool's digest, not the HUB_ART literal, so a build failure names
            // which composite failed in the log -- pipeFrom's label is shared with
            // the decade route, which passes the decade for the same reason.
            is ArtRequest.Hub -> {
                with(request) {
                    HubCompositeArt.cached(context, session, pool, bucket)?.let(::openReadOnly)
                        ?: pipeFrom(HubCompositeArt.idFor(pool)) {
                            HubCompositeArt.build(context, session, pool, bucket)
                        }
                }
            }
        }
    }

    private fun openAlbumArt(
        context: Context,
        request: ArtRequest.Album,
    ): ParcelFileDescriptor {
        val size = Preferences.getImageSize().takeIf { it > 0 } ?: DEFAULT_ARTWORK_SIZE
        val session = request.session
        val artworkUrl =
            MediaUrlBuilder.artworkUrl(session.serverUri, request.thumbPath, session.serverAuthToken, size, size)
                // Null means no usable address or token -- there is no image to serve, and reporting
                // that is what makes the car fall back to the placeholder icon
                // instead of stalling on a pipe that never gets written.
                ?: throw FileNotFoundException("No Plex artwork URL for ${request.thumbPath}")

        return pipe(request.thumbPath) { out ->
            ArtworkImages.get(context).writeTo(artworkUrl, Preferences.isDataSavingMode(), out)
            true
        }
    }

    private fun openReadOnly(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

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
            file.inputStream().use { it.copyTo(out) }
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
        val (readSide, writeSide) =
            try {
                ParcelFileDescriptor.createPipe()
            } catch (e: IOException) {
                throw FileNotFoundException("Could not create pipe: ${e.message}")
            }

        try {
            executor.execute {
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
        } catch (e: RejectedExecutionException) {
            // Shut down: nothing will ever write, so neither end may escape
            // open, and the car is owed the same refusal as any other failure
            // rather than an unchecked exception across the binder.
            readSide.close()
            writeSide.close()
            throw FileNotFoundException("Not accepting artwork work: ${e.message}")
        }

        return readSide
    }

    override fun onCreate(): Boolean {
        executor = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
        return true
    }

    override fun shutdown() {
        if (!::executor.isInitialized) return
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

        // Plex's photo transcoder requires both dimensions. The image-size preference
        // this used to read is frozen at its "-1" sentinel -- the settings screen that
        // set it is gone -- so anything non-positive falls back to a size that fills a
        // car browse tile without fetching a full-resolution cover for every row.
        private const val DEFAULT_ARTWORK_SIZE = 512

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
