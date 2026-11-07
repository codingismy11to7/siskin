package com.cappielloantonio.tempo.service.crossfade

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeRampsTest {
    private val scope = TestScope(StandardTestDispatcher())
    private var session: Any? = "server-a"
    private val fetched = mutableListOf<String>()
    private var answer: (String) -> TrackFadeInfo? = { TrackFadeInfo(4_000L, "album-$it") }

    private val ramps =
        CrossfadeRamps(scope, { session }) { id ->
            fetched += id
            answer(id)
        }

    @Test
    fun unknownUntilFetched() =
        scope.runTest {
            ramps.request("1")
            assertNull(ramps.infoFor("1"))
            advanceUntilIdle()
            assertEquals(TrackFadeInfo(4_000L, "album-1"), ramps.infoFor("1"))
        }

    @Test
    fun fetchesEachTrackOnce() =
        scope.runTest {
            ramps.request("1")
            ramps.request("1")
            advanceUntilIdle()
            ramps.request("1")
            advanceUntilIdle()
            assertEquals(listOf("1"), fetched)
        }

    @Test
    fun aFailedFetchIsRememberedAsNoFade() =
        scope.runTest {
            answer = { error("offline") }
            ramps.request("1")
            advanceUntilIdle()
            assertEquals(TrackFadeInfo(null, null), ramps.infoFor("1"))
            ramps.request("1")
            advanceUntilIdle()
            assertEquals(listOf("1"), fetched)
        }

    @Test
    fun aCancelledFetchRecordsNothing() =
        scope.runTest {
            answer = { throw CancellationException("service destroyed") }
            ramps.request("1")
            advanceUntilIdle()
            assertNull(ramps.infoFor("1"))
        }

    @Test
    fun aNullAnswerIsNoFade() =
        scope.runTest {
            answer = { null }
            ramps.request("1")
            advanceUntilIdle()
            assertEquals(TrackFadeInfo(null, null), ramps.infoFor("1"))
        }

    @Test
    fun aNewSessionForgetsEverything() =
        scope.runTest {
            ramps.request("1")
            advanceUntilIdle()
            session = "server-b"
            assertNull(ramps.infoFor("1"))
            ramps.request("1")
            advanceUntilIdle()
            assertEquals(listOf("1", "1"), fetched)
        }

    @Test
    fun anAnswerForTheOldSessionIsDropped() =
        scope.runTest {
            ramps.request("1")
            session = "server-b"
            advanceUntilIdle()
            assertNull(ramps.infoFor("1"))
        }
}
