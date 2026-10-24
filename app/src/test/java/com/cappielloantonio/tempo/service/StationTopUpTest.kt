package com.cappielloantonio.tempo.service

import androidx.media3.common.MediaItem
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexMediaMapper.StationTag
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because station tags live in a real Bundle. */
@RunWith(RobolectricTestRunner::class)
class StationTopUpTest {
    private val events = mutableListOf<String>()

    private fun stationTrack(
        ratingKey: String,
        itemId: Long = 100,
    ) = PlexMediaMapper.buildTrackMediaItem(
        ratingKey = ratingKey,
        title = null,
        albumTitle = null,
        artist = null,
        thumb = null,
        partKey = "/library/parts/$ratingKey",
        durationMs = null,
        trackIndex = null,
        year = null,
        grandparentRatingKey = null,
        isHearted = false,
        parentId = null,
        serverUri = "https://plex.example",
        token = "t",
        station = StationTag(32194, itemId, "270597"),
    )

    private fun topUp(result: Either<PlexTransportFailure, List<MediaItem>> = listOf(stationTrack("3"), stationTrack("4")).right()) =
        StationTopUp(
            extend = {
                events += "extend ${it.playQueueId}"
                result
            },
            report = { item, _ -> events += "report ${item.mediaId}" },
        )

    @Test
    fun aStationTrackIsAlwaysReported() =
        runTest {
            topUp().onTrackStarted(stationTrack("1"), 0, remainingAfterCurrent = 10, queuedMediaIds = setOf("1"))

            assertEquals(listOf("report 1"), events)
        }

    @Test
    fun anOrdinaryTrackIsLeftToTheUsualScrobble() =
        runTest {
            val plain = MediaItem.Builder().setMediaId("9").build()

            val more = topUp().onTrackStarted(plain, 0, remainingAfterCurrent = 0, queuedMediaIds = setOf("9"))

            assertTrue(more.isEmpty())
            assertTrue(events.isEmpty())
        }

    @Test
    fun runningLowReportsFirstThenReadsTheQueue() =
        runTest {
            topUp().onTrackStarted(stationTrack("1"), 0, remainingAfterCurrent = 2, queuedMediaIds = setOf("1", "2"))

            assertEquals(listOf("report 1", "extend 32194"), events)
        }

    @Test
    fun plentyLeftDoesNotReadTheQueue() =
        runTest {
            topUp().onTrackStarted(stationTrack("1"), 0, remainingAfterCurrent = 3, queuedMediaIds = setOf("1"))

            assertEquals(listOf("report 1"), events)
        }

    @Test
    fun tracksAlreadyQueuedAreNotAppendedAgain() =
        runTest {
            val more = topUp().onTrackStarted(stationTrack("1"), 0, 1, queuedMediaIds = setOf("1", "3"))

            assertEquals(listOf("4"), more.map { it.mediaId })
        }

    @Test
    fun aFailedReadAppendsNothing() =
        runTest {
            val more =
                topUp(PlexTransportFailure.Unreachable(PlexHost.Server).left())
                    .onTrackStarted(stationTrack("1"), 0, 0, setOf("1"))

            assertTrue(more.isEmpty())
        }

    @Test
    fun aSecondTopUpWhileOneIsRunningDoesNothing() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val slow =
                StationTopUp(
                    extend = {
                        gate.await()
                        listOf(stationTrack("3")).right()
                    },
                    report = { _, _ -> },
                )

            val first = async { slow.onTrackStarted(stationTrack("1"), 0, 0, setOf("1")) }
            testScheduler.runCurrent()
            val second = slow.onTrackStarted(stationTrack("2"), 0, 0, setOf("1", "2"))
            gate.complete(Unit)

            assertTrue(second.isEmpty())
            assertEquals(listOf("3"), first.await().map { it.mediaId })
        }

    @Test
    fun trimmingKeepsTheConfiguredNumberBehindTheCurrentTrack() {
        assertEquals(0, StationTopUp.firstToKeep(currentIndex = 5, keep = 29))
        assertEquals(11, StationTopUp.firstToKeep(currentIndex = 40, keep = 29))
    }
}
