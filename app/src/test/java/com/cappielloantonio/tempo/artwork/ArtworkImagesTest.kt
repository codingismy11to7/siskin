package com.cappielloantonio.tempo.artwork

import android.graphics.Bitmap
import coil3.request.CachePolicy
import coil3.request.allowHardware
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Covers fetched through the disk cache against a real HTTP server, and
 * decoded for real under Robolectric's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkImagesTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var images: ArtworkImages

    private val cover = byteArrayOf(1, 2, 3, 4, 5)

    // As MediaUrlBuilder writes it: the thumb path percent-encoded into the query.
    private val path = "/photo/:/transcode?width=512&height=512&minSize=1&url=%2Flibrary%2Fmetadata%2F1%26x%2Fthumb"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        images =
            ArtworkImages(
                RuntimeEnvironment.getApplication(),
                OkHttpClient(),
                folder.newFolder("artwork"),
                10L * 1024 * 1024,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url(path).toString()

    private fun ok() = MockResponse().setBody(okio.Buffer().write(cover))

    private fun bytesOf(cacheOnly: Boolean): ByteArray = ByteArrayOutputStream().also { images.writeTo(url(), cacheOnly, it) }.toByteArray()

    /** Coil refuses network work on the main thread, which is where a
     * Robolectric test runs; the provider never calls from there. */
    private fun bytesOffMain(cacheOnly: Boolean): ByteArray {
        val worker = Executors.newSingleThreadExecutor()
        try {
            return worker.submit(Callable { bytesOf(cacheOnly) }).get(10, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun theServerIsAskedForTheUrlExactlyAsBuilt() {
        // A re-encoded or decoded `url=` value is a different transcode request.
        server.enqueue(ok())

        bytesOffMain(cacheOnly = false)

        assertEquals(path, server.takeRequest().path)
    }

    @Test
    fun aSecondFetchIsServedFromTheDiskCache() {
        server.enqueue(ok())

        assertArrayEquals(cover, bytesOffMain(cacheOnly = false))
        assertArrayEquals(cover, bytesOffMain(cacheOnly = false))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cacheOnlyFailsWithoutAskingTheServerWhenNothingIsCached() {
        server.enqueue(ok())

        assertThrows(IOException::class.java) { bytesOffMain(cacheOnly = true) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cacheOnlyServesWhatIsAlreadyCached() {
        server.enqueue(ok())
        bytesOffMain(cacheOnly = false)

        assertArrayEquals(cover, bytesOffMain(cacheOnly = true))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aMissingCoverIsAskedForAgainRatherThanRememberedAsMissing() {
        // Coil's default strategy stores a 404, which would keep a cover added
        // on the server afterwards missing until it was evicted.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(ok())

        assertThrows(IOException::class.java) { bytesOffMain(cacheOnly = false) }
        assertArrayEquals(cover, bytesOffMain(cacheOnly = false))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun concurrentFetchesOfOneCoverCostOneRequest() {
        // What CompositeBuildLocks relies on for the album path: the car opens
        // one cover from several provider threads at once.
        server.enqueue(ok().setBodyDelay(500, TimeUnit.MILLISECONDS))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results =
                pool
                    .invokeAll(List(4) { Callable { bytesOf(cacheOnly = false) } })
                    .map { it.get(10, TimeUnit.SECONDS) }

            results.forEach { assertArrayEquals(cover, it) }
            assertEquals(1, server.requestCount)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aBitmapRequestIsSoftwareAndSkipsTheMemoryCache() {
        // A hardware bitmap fails when CompositeArt draws it into its software
        // canvas, which Robolectric cannot show by decoding.
        val request = images.bitmapRequest(File("cover.png"), 256, cacheOnly = false, fill = true)

        assertFalse(request.allowHardware)
        assertEquals(CachePolicy.DISABLED, request.memoryCachePolicy)
    }

    @Test
    fun fillScalesTheShorterSideToTheEdge() {
        val bitmap = images.bitmap(wideJpeg(), 150, fill = true)

        assertEquals(300, bitmap.width)
        assertEquals(150, bitmap.height)
        assertEquals(Bitmap.Config.RGB_565, bitmap.config)
    }

    @Test
    fun fitKeepsTheShorterSideBelowTheEdge() {
        val bitmap = images.bitmap(wideJpeg(), 150)

        assertTrue("height ${bitmap.height}", bitmap.height < 150)
        assertEquals(2 * bitmap.height, bitmap.width)
    }

    /** A 400 by 200 JPEG, as the transcoder serves, on disk so decoding needs no network. */
    private fun wideJpeg(): File =
        folder.newFile("wide.jpg").also { file ->
            file.outputStream().use {
                Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        }
}
