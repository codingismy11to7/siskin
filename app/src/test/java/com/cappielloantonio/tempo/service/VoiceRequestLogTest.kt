package com.cappielloantonio.tempo.service

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class VoiceRequestLogTest {
    @Before
    fun setUp() {
        VoiceRequestLog.clear()
    }

    private fun entry(at: Long) = VoiceLogEntry("query $at", "focus", null, at, null, null, null)

    @Test
    fun `an added entry reads back whole`() {
        VoiceRequestLog.add(entry(1L))
        assertEquals(listOf(entry(1L)), VoiceRequestLog.all())
    }

    @Test
    fun `newest comes first and the oldest falls off`() {
        (1L..(VoiceRequestLog.MAX + 1L)).forEach { VoiceRequestLog.add(entry(it)) }

        assertEquals(VoiceRequestLog.MAX, VoiceRequestLog.all().size)
        assertEquals(VoiceRequestLog.MAX + 1L, VoiceRequestLog.all().first().atMillis)
    }

    @Test
    fun `finishing fills in only the matching entry`() {
        VoiceRequestLog.add(entry(1L))
        VoiceRequestLog.add(entry(2L))

        VoiceRequestLog.finish(1L, "played")

        assertEquals(listOf(null, "played"), VoiceRequestLog.all().map { it.outcome })
    }

    @Test
    fun `concurrent adds from several threads lose no entry`() {
        val threads = 4
        val each = VoiceRequestLog.MAX / threads
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val done =
                (0 until threads).map { t ->
                    pool.submit {
                        start.await()
                        repeat(each) { VoiceRequestLog.add(entry((t * each + it).toLong())) }
                    }
                }
            start.countDown()
            done.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(threads * each, VoiceRequestLog.all().size)
    }
}
