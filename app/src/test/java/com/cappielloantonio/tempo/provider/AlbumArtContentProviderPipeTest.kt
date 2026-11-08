package com.cappielloantonio.tempo.provider

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.random.Random

/**
 * What reaches the read end of the pipe the provider hands back.
 *
 * Every other provider test stops at a refusal or a cache hit, neither of which
 * pipes. This is the streaming half: what the writer produced arrives whole,
 * and a writer with nothing to give, or one that throws, leaves nothing.
 *
 * **Not covered: that the write side is closed.** Robolectric's createPipe()
 * is a temp file opened twice, not a pipe, so a reader sees EOF wherever the
 * writer has got to rather than waiting for a close. Each test therefore waits
 * for the executor to drain before reading, and a writer that never closed
 * would pass. A pipe left open stalls the car's tile instead of falling back
 * to its placeholder, so that half is checked on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
class AlbumArtContentProviderPipeTest {
    private lateinit var provider: AlbumArtContentProvider

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(AlbumArtContentProvider::class.java).create().get()
    }

    @Test
    fun deliversWhatTheWriterWrote() {
        val image = Random(1).nextBytes(100)

        val read =
            written(
                provider.pipe("label") { out ->
                    out.write(image)
                    true
                },
            )

        assertArrayEquals(image, read)
    }

    @Test
    fun writesNothingWhenThereIsNoImage() {
        assertArrayEquals(ByteArray(0), written(provider.pipe("label") { false }))
    }

    @Test
    fun writesNothingWhenTheWriterThrows() {
        assertArrayEquals(
            ByteArray(0),
            written(provider.pipe("label") { throw IOException("no cover") }),
        )
    }

    @Test
    fun copiesACompositeLargerThanOneBuffer() {
        // Past 8 KiB, so the copy has to loop rather than land in one write.
        val image = Random(2).nextBytes(20_000)
        val file = File.createTempFile("composite", ".jpg").apply { writeBytes(image) }

        val read = written(provider.pipeFrom("label") { file })

        assertArrayEquals(image, read)
        file.delete()
    }

    @Test
    fun refusesOnceShutDownRatherThanThrowingAcrossTheBinder() {
        provider.shutdown()

        // FileNotFoundException is the one failure the car turns into its
        // placeholder; a RejectedExecutionException would reach it unchecked.
        assertThrows(FileNotFoundException::class.java) { provider.pipe("label") { true } }
    }

    @Test
    fun writesNothingWhenNoCompositeWasBuilt() {
        assertArrayEquals(ByteArray(0), written(provider.pipeFrom("label") { null }))
    }

    /** Everything the writer put in [readSide]'s pipe, read once the writer has
     * finished -- shutdown() waits for the executor, which is what stands in
     * for the close Robolectric's pipe cannot signal. */
    private fun written(readSide: ParcelFileDescriptor): ByteArray {
        provider.shutdown()
        return ParcelFileDescriptor.AutoCloseInputStream(readSide).use { it.readBytes() }
    }
}
