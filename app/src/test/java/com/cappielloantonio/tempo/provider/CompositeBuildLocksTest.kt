package com.cappielloantonio.tempo.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two properties CompositeArt.build depends on, and they pull in
 * opposite directions: one tile's builds must not overlap, and different tiles'
 * builds must.
 *
 * Plain JUnit -- nothing here touches an Android class, so Robolectric would
 * buy nothing. Concurrency is asserted rather than timed: the same-key test
 * fails if an overlap is ever *observed*, and the different-key test fails if
 * the threads cannot all reach a barrier, which they cannot if they are being
 * run one after another.
 */
class CompositeBuildLocksTest {
    private val threads = 4

    /** Exclusivity is guaranteed for threads that arrive before the first one
     * exits the key -- see CompositeBuildLocks.exclusively's KDoc: removal
     * runs on the first exit, not the last, so once that removal has
     * happened a fresh arrival can install a new lock for the same key and
     * run alongside a straggler still finishing under the old one. This
     * test's four threads all start together off one latch and each holds
     * its slot for 50ms, far longer than four threads need to queue through
     * a single `synchronized`, so it never reaches that window -- it is not
     * a fuzz test for it, and a worker whose wake-up slipped past that sleep
     * on a badly loaded machine could in principle observe an overlap this
     * assertion was never meant to rule out. */
    @Test
    fun arrivingTogetherForOneKeyRunsOneAtATime() {
        val inside = AtomicInteger(0)
        val overlapped = AtomicBoolean(false)

        runConcurrently(threads) {
            CompositeBuildLocks.exclusively("4-1980-487234") {
                if (inside.incrementAndGet() > 1) overlapped.set(true)
                // Long enough that four unsynchronised threads would certainly
                // be seen together; the assertion is on what was observed, so a
                // slow machine cannot make this pass spuriously.
                Thread.sleep(50)
                inside.decrementAndGet()
            }
        }

        assertFalse("two builds of one tile ran at once", overlapped.get())
    }

    @Test
    fun letsDifferentKeysRunAtOnce() {
        // The property a single global lock would break. Eight decade tiles
        // miss together on the first browse of an hour, and serialising those
        // would be a worse problem than the duplicate builds being fixed.
        //
        // A barrier is the assertion: every thread must arrive before any is
        // released, which is impossible unless they are genuinely concurrent.
        // Serialised, the first thread waits out the timeout alone.
        val barrier = CyclicBarrier(threads)
        val allArrived = AtomicBoolean(true)

        runConcurrently(threads) { index ->
            CompositeBuildLocks.exclusively("4-19${index}0-487234") {
                try {
                    barrier.await(10, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    allArrived.set(false)
                }
            }
        }

        assertTrue("different decades did not build concurrently", allArrived.get())
    }

    @Test
    fun leavesNoKeyBehindOnceTheBuildsAreDone() {
        // A key carries a bucket, so every hour mints eight fresh ones; without
        // removal the map grows for as long as the process lives.
        runConcurrently(threads) { index ->
            CompositeBuildLocks.exclusively("4-19${index}0-487234") { Thread.sleep(10) }
        }

        assertEquals(0, CompositeBuildLocks.heldKeys())
    }

    /** Starts [count] threads that all begin inside [body] at once, and waits
     * for them; a body that threw fails the test through the assertions above
     * rather than being swallowed silently. */
    private fun runConcurrently(
        count: Int,
        body: (Int) -> Unit,
    ) {
        val start = CountDownLatch(1)
        val workers =
            (0 until count).map { index ->
                Thread {
                    start.await()
                    body(index)
                }.also { it.start() }
            }

        start.countDown()
        workers.forEach { it.join(30_000) }
        workers.forEach { assertFalse("a worker did not finish", it.isAlive) }
    }
}
