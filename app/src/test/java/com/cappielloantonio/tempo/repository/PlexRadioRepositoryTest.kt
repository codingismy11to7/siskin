package com.cappielloantonio.tempo.repository

import arrow.core.Either
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Starting and extending an artist station against a fake server whose
 * responses are shaped like the live ones in
 * docs/decisions/2026-10-23-artist-radio-design.md.
 */
@RunWith(RobolectricTestRunner::class)
class PlexRadioRepositoryTest {
    private val fixture = PlexBrowseTestServer()
    private val requests = mutableListOf<RecordedRequest>()
    private var stationMints = 0
    private var playQueueStatus = 200

    @Before
    fun setUp() {
        fixture.start()
        PlexApi().machineIdentifier = "abc123"
        fixture.server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests.add(request) }
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/identity" -> json("""{"MediaContainer":{"machineIdentifier":"from-identity"}}""")
                        path == "/library/metadata/270597" -> json(artistWithStation(++stationMints))
                        path == "/library/metadata/1" -> json("""{"MediaContainer":{"Metadata":[{"ratingKey":"1","type":"artist"}]}}""")
                        path == "/playQueues" && request.method == "POST" -> json(queue(32194, "11" to 101, "12" to 102))
                        path == "/playQueues/32194" && playQueueStatus != 200 -> MockResponse().setResponseCode(playQueueStatus)
                        path == "/playQueues/32194" -> json(queue(32194, "11" to 101, "12" to 102, "13" to 103))
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
    }

    @After
    fun tearDown() {
        fixture.stop()
        PlexApi().machineIdentifier = null
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun artistWithStation(n: Int) =
        """
        {"MediaContainer":{"Metadata":[{"ratingKey":"270597","type":"artist","Stations":{"Metadata":[
        {"key":"/library/metadata/270597/station/uuid-$n?type=10","type":"playlist","radio":"1"}]}}]}}
        """.trimIndent()

    private fun queue(
        id: Long,
        vararg items: Pair<String, Long>,
    ) = """{"MediaContainer":{"playQueueID":$id,"Metadata":[${
        items.joinToString(",") { (rk, pqi) -> """{"ratingKey":"$rk","type":"track","playQueueItemID":$pqi}""" }
    }]}}"""

    private fun repository() = PlexRadioRepository()

    private fun posted() = synchronized(requests) { requests.filter { it.method == "POST" } }

    @Test
    fun startingAStationPostsItsUriAgainstTheSessionsServer() =
        runTest {
            val repo = repository()
            val response = (repo.startStation("270597") as Either.Right).value

            val uri = posted().single().requestUrl!!.queryParameter("uri")
            assertEquals("server://abc123/com.plexapp.plugins.library/library/metadata/270597/station/uuid-1?type=10", uri)
            assertEquals(listOf("11", "12"), repo.stationTracks(response, "270597").map { it.mediaId })
        }

    @Test
    fun everyTapFetchesAFreshStationKey() =
        runTest {
            val repo = repository()
            repo.startStation("270597")
            repo.startStation("270597")

            val keys = posted().map { it.requestUrl!!.queryParameter("uri") }
            assertEquals(2, keys.toSet().size)
        }

    @Test
    fun aSessionWithoutAMachineIdentifierAsksTheServer() =
        runTest {
            PlexApi().machineIdentifier = null

            repository().startStation("270597")

            assertTrue(
                posted()
                    .single()
                    .requestUrl!!
                    .queryParameter("uri")!!
                    .startsWith("server://from-identity/"),
            )
        }

    @Test
    fun stationTracksCarryTheirPlaceInTheQueue() =
        runTest {
            val repo = repository()
            val response = (repo.startStation("270597") as Either.Right).value

            val tags = repo.stationTracks(response, "270597").map { PlexMediaMapper.stationTagOf(it) }

            assertEquals(
                listOf(
                    PlexMediaMapper.StationTag(32194, 101, "270597"),
                    PlexMediaMapper.StationTag(32194, 102, "270597"),
                ),
                tags,
            )
        }

    @Test
    fun anArtistWithoutAStationStartsNothing() =
        runTest {
            val repo = repository()
            val response = (repo.startStation("1") as Either.Right).value

            assertTrue(repo.stationTracks(response, "1").isEmpty())
            assertTrue(posted().isEmpty())
        }

    @Test
    fun extendingReadsTheQueueBack() =
        runTest {
            val tracks = (repository().extendStation(PlexMediaMapper.StationTag(32194, 102, "270597")) as Either.Right).value

            assertEquals(listOf("11", "12", "13"), tracks.map { it.mediaId })
        }

    @Test
    fun aQueueTheServerDiscardedIsReplacedByAFreshStation() =
        runTest {
            playQueueStatus = 404

            val tracks = (repository().extendStation(PlexMediaMapper.StationTag(32194, 102, "270597")) as Either.Right).value

            assertEquals(1, posted().size)
            assertEquals(listOf("11", "12"), tracks.map { it.mediaId })
        }

    @Test
    fun anyOtherFailureIsReturnedNotRedrawn() =
        runTest {
            playQueueStatus = 500

            val result = repository().extendStation(PlexMediaMapper.StationTag(32194, 102, "270597"))

            assertEquals(PlexTransportFailure.Http(PlexHost.Server, 500), (result as Either.Left).value)
            assertTrue(posted().isEmpty())
        }
}
