package com.cappielloantonio.tempo.service

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousPlayTest {
    private val events = mutableListOf<String>()
    private var queue = listOf(track("seed"))
    private var playing: MediaItem? = track("seed")

    private fun track(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun tracks(vararg ids: String) = ids.map(::track)

    private fun continuousPlay(
        similar: suspend () -> List<MediaItem> = { tracks("1", "2") },
        random: suspend () -> List<MediaItem> = { tracks("8", "9") },
        fallbackToRandom: Boolean = true,
        usable: Boolean = true,
    ) = ContinuousPlay(
        similar = { seed ->
            events += "similar $seed"
            similar()
        },
        random = {
            events += "random"
            random()
        },
        fallbackToRandom = { fallbackToRandom },
        usable = { usable },
        markUsed = { events += "marked" },
    )

    private suspend fun ContinuousPlay.ids() = tracksAfter(track("seed"), { playing }) { queue }.map { it.mediaId }

    @Test
    fun similarTracksAreReturnedLessThoseAlreadyQueued() =
        runTest {
            queue = tracks("seed", "2")

            assertEquals(listOf("1"), continuousPlay().ids())
            assertEquals(listOf("marked", "similar seed"), events)
        }

    @Test
    fun similarTracksThatAreAllQueuedFallThroughToRandom() =
        runTest {
            queue = tracks("seed", "1", "2", "9")

            assertEquals(listOf("8"), continuousPlay().ids())
            assertEquals(listOf("marked", "similar seed", "random"), events)
        }

    @Test
    fun noSimilarTracksAndNoFallbackAddsNothing() =
        runTest {
            assertEquals(emptyList<String>(), continuousPlay(similar = { emptyList() }, fallbackToRandom = false).ids())
            assertEquals(listOf("marked", "similar seed"), events)
        }

    @Test
    fun aThrottledRunRequestsNothing() =
        runTest {
            assertEquals(emptyList<String>(), continuousPlay(usable = false).ids())
            assertEquals(emptyList<String>(), events)
        }

    /** The queue can change while a request is in flight; a track queued meanwhile is not added twice. */
    @Test
    fun theQueueIsReadAfterTheRequest() =
        runTest {
            val play =
                continuousPlay(similar = {
                    queue = queue + track("1")
                    tracks("1", "2")
                })

            assertEquals(listOf("2"), play.ids())
        }

    /** A new queue started while the request was in flight; tracks picked for the old seed must not land in it. */
    @Test
    fun aQueueReplacedDuringTheSimilarRequestGetsNothing() =
        runTest {
            val play =
                continuousPlay(similar = {
                    playing = track("album-1")
                    tracks("1", "2")
                })

            assertEquals(emptyList<String>(), play.ids())
            assertEquals(listOf("marked", "similar seed"), events)
        }

    @Test
    fun aQueueClearedDuringTheRandomRequestGetsNothing() =
        runTest {
            val play =
                continuousPlay(similar = { emptyList() }, random = {
                    playing = null
                    tracks("8", "9")
                })

            assertEquals(emptyList<String>(), play.ids())
        }

    @Test
    fun readyIsFalseWhileARunIsInFlightOrThrottled() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val play =
                continuousPlay(similar = {
                    gate.await()
                    tracks("1")
                })

            assertTrue(play.ready())
            val first = async { play.ids() }
            testScheduler.runCurrent()
            assertFalse(play.ready())
            gate.complete(Unit)
            first.await()

            assertTrue(play.ready())
            assertFalse(continuousPlay(usable = false).ready())
        }

    @Test
    fun aRunStartedWhileOneIsInFlightRequestsNothing() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val play =
                continuousPlay(similar = {
                    gate.await()
                    tracks("1")
                })

            val first = async { play.ids() }
            testScheduler.runCurrent()
            assertEquals(emptyList<String>(), play.ids())
            gate.complete(Unit)

            assertEquals(listOf("1"), first.await())
            assertEquals(listOf("marked", "similar seed"), events)
        }

    /**
     * A tier that throws must still let the next run through: the running flag
     * stuck true would leave continuous play dead for the rest of the process,
     * with no way back short of a restart.
     */
    @Test
    fun aTierThatThrowsDoesNotBlockTheNextRun() =
        runTest {
            var fail = true
            val play =
                continuousPlay(similar = {
                    if (fail) error("boom")
                    tracks("1")
                })

            val thrown = runCatching { play.ids() }
            fail = false

            assertTrue(thrown.isFailure)
            assertEquals(listOf("1"), play.ids())
        }

    @Test
    fun aCancelledRunDoesNotBlockTheNextRun() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var hold = true
            val play =
                continuousPlay(similar = {
                    if (hold) gate.await()
                    tracks("1")
                })

            val first = async { play.ids() }
            testScheduler.runCurrent()
            first.cancel()
            testScheduler.runCurrent()
            hold = false

            assertEquals(listOf("1"), play.ids())
        }
}
