package com.cappielloantonio.tempo.artwork

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The provider's path: bytes to a file, through the disk cache, against a real
 * HTTP server. Robolectric only for the Context Coil's loader is built from.
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkImagesTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var images: ArtworkImages

    private val cover = byteArrayOf(1, 2, 3, 4, 5)

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

    private fun url() = server.url("/photo/:/transcode?url=%2Flibrary%2Fmetadata%2F1%2Fthumb").toString()

    private fun ok() = MockResponse().setBody(okio.Buffer().write(cover))

    /** Coil refuses network work on the main thread, which is where a
     * Robolectric test runs; the provider never calls from there. */
    private fun fileOffMain(cacheOnly: Boolean): File {
        val worker = Executors.newSingleThreadExecutor()
        try {
            return worker.submit(Callable { images.file(url(), cacheOnly) }).get(10, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun aSecondFetchIsServedFromTheDiskCache() {
        server.enqueue(ok())

        val first = fileOffMain(cacheOnly = false)
        val second = fileOffMain(cacheOnly = false)

        assertArrayEquals(cover, first.readBytes())
        assertArrayEquals(cover, second.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cacheOnlyFailsWithoutAskingTheServerWhenNothingIsCached() {
        server.enqueue(ok())

        assertThrows(IOException::class.java) { fileOffMain(cacheOnly = true) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cacheOnlyServesWhatIsAlreadyCached() {
        server.enqueue(ok())
        fileOffMain(cacheOnly = false)

        assertArrayEquals(cover, fileOffMain(cacheOnly = true).readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aMissingCoverIsAskedForAgainRatherThanRememberedAsMissing() {
        // Coil's default strategy stores a 404, which would keep a cover added
        // on the server afterwards missing until it was evicted.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(ok())

        assertThrows(IOException::class.java) { fileOffMain(cacheOnly = false) }
        assertArrayEquals(cover, fileOffMain(cacheOnly = false).readBytes())
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
                    .invokeAll(List(4) { Callable { images.file(url(), cacheOnly = false) } })
                    .map { it.get(10, TimeUnit.SECONDS) }

            results.forEach { assertArrayEquals(cover, it.readBytes()) }
            assertEquals(1, server.requestCount)
        } finally {
            pool.shutdownNow()
        }
    }
}
