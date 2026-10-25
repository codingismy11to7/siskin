package com.cappielloantonio.tempo.artwork

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.network.CacheStrategy
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.HttpException
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.serviceLoaderEnabled
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.util.Preferences
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dispatcher
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Fetches and decodes every cover the app serves, blocking, for callers that
 * are already on a background thread.
 *
 * What it returns belongs to the caller outright: there is no target to clear
 * and no pool a bitmap goes back to, so a bitmap can be cached, drawn or handed
 * to media3 for as long as anyone likes and is freed by the garbage collector.
 * See docs/decisions/2026-10-25-image-library-design.md.
 */
class ArtworkImages
    @VisibleForTesting
    internal constructor(
        context: Context,
        callFactory: Call.Factory,
        diskCacheDirectory: File,
        diskCacheBytes: Long,
    ) {
        private val context = context.applicationContext

        @OptIn(ExperimentalCoilApi::class)
        val imageLoader: ImageLoader =
            ImageLoader
                .Builder(this.context)
                // Only the fetcher below. The service-loaded one would bring a
                // second OkHttpClient and none of the settings that follow.
                .serviceLoaderEnabled(false)
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = { callFactory },
                            cacheStrategy = { SuccessOnlyCacheStrategy },
                            // CompositeBuildLocks leaves the album-cover path to
                            // this: the car opens one cover from several provider
                            // threads at once, and they should cost one fetch.
                            concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                        ),
                    )
                }.diskCache {
                    DiskCache
                        .Builder()
                        .directory(diskCacheDirectory.toOkioPath())
                        .maxSizeBytes(diskCacheBytes)
                        .build()
                }.bitmapConfig(Bitmap.Config.RGB_565)
                .build()

        /**
         * Copies [url]'s bytes to [out], fetching them into the disk cache first
         * if they are not there and [cacheOnly] allows it. Never decodes.
         *
         * Copies rather than returning the cache file: the snapshot is what
         * keeps the entry from being evicted or replaced, so the bytes are read
         * while it is still open.
         */
        @Throws(IOException::class)
        fun writeTo(
            url: String,
            cacheOnly: Boolean,
            out: OutputStream,
        ) {
            runBlocking {
                val options = Options(context, networkCachePolicy = networkPolicy(cacheOnly))
                val data = imageLoader.components.map(url, options)
                val (fetcher, _) =
                    imageLoader.components.newFetcher(data, options, imageLoader)
                        ?: throw IOException("No fetcher for $url")
                val result =
                    try {
                        fetcher.fetch()
                    } catch (e: HttpException) {
                        throw IOException("Could not fetch $url", e)
                    } as? SourceFetchResult ?: throw IOException("Not a byte source for $url")
                result.source.use { source -> source.source().inputStream().copyTo(out) }
            }
        }

        /**
         * [data] decoded to fit within [edge] square, or, with [fill], scaled so
         * its shorter side is [edge]. Not cropped either way.
         *
         * Software, because CompositeArt draws these into a software canvas,
         * which a hardware bitmap cannot be drawn into. Kept out of the memory
         * cache because every caller already keeps its own -- SyncBitmapLoader's
         * LruCache, the composite files -- so it would only hold a second copy.
         */
        @Throws(IOException::class)
        fun bitmap(
            data: Any,
            edge: Int,
            cacheOnly: Boolean = false,
            fill: Boolean = false,
        ): Bitmap =
            when (val result = runBlocking { imageLoader.execute(bitmapRequest(data, edge, cacheOnly, fill)) }) {
                is SuccessResult -> result.image.toBitmap()
                is ErrorResult -> throw IOException("Could not load $data", result.throwable)
            }

        @VisibleForTesting
        internal fun bitmapRequest(
            data: Any,
            edge: Int,
            cacheOnly: Boolean,
            fill: Boolean,
        ): ImageRequest =
            ImageRequest
                .Builder(context)
                .data(data)
                .size(Size(edge, edge))
                .scale(if (fill) Scale.FILL else Scale.FIT)
                .precision(if (fill) Precision.EXACT else Precision.INEXACT)
                .networkCachePolicy(networkPolicy(cacheOnly))
                .memoryCachePolicy(CachePolicy.DISABLED)
                .allowHardware(false)
                .build()

        private fun networkPolicy(cacheOnly: Boolean) = if (cacheOnly) CachePolicy.DISABLED else CachePolicy.ENABLED

        companion object {
            private const val CACHE_DIRECTORY = "artwork"

            // Where Glide kept its disk cache, up to the 500 MB default, removed
            // rather than left at its full size.
            private const val GLIDE_CACHE_DIRECTORY = "cache"

            // Short, because a cover is optional and a provider thread waiting on
            // one is a thread every other cover queues behind. Failing gives the
            // car its placeholder. The call timeout bounds a server that trickles
            // a cover with every read inside the read timeout.
            private const val TIMEOUT_MS = 2500L
            private const val CALL_TIMEOUT_MS = 10_000L

            // Holds only the application context, which lives as long as this does.
            //noinspection StaticFieldLeak
            @Volatile private var instance: ArtworkImages? = null

            @JvmStatic
            fun get(context: Context): ArtworkImages =
                instance ?: synchronized(this) {
                    instance ?: create(context.applicationContext).also { instance = it }
                }

            private fun create(context: Context): ArtworkImages {
                // Off this thread, which can be the main one (the sign-in QR
                // code), and outside get()'s lock, which every cover thread waits on.
                val glideCache = File(context.cacheDir, GLIDE_CACHE_DIRECTORY)
                if (glideCache.exists()) {
                    Thread({ glideCache.deleteRecursively() }, "glide-cache-cleanup").start()
                }
                val client =
                    PlexRetrofitFactory.sharedClient
                        .newBuilder()
                        // Shares the connection pool but not the dispatcher, whose
                        // five-per-host limit would otherwise put a grid of covers
                        // ahead of browse, timeline and scrobble calls to the same
                        // server.
                        .dispatcher(Dispatcher())
                        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build()
                return ArtworkImages(
                    context,
                    client,
                    File(context.cacheDir, CACHE_DIRECTORY),
                    Preferences.getImageCacheSize().toLong() * 1024 * 1024,
                )
            }
        }
    }

/**
 * Stores 2xx responses only. Coil's default also stores a 404, which would keep
 * a cover added on the server afterwards missing until it was evicted.
 */
@OptIn(ExperimentalCoilApi::class)
internal object SuccessOnlyCacheStrategy : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ) = CacheStrategy.ReadResult(cacheResponse)

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ) = if (networkResponse.code in 200 until 300) {
        CacheStrategy.WriteResult(networkResponse)
    } else {
        CacheStrategy.WriteResult.DISABLED
    }
}
