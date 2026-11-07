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
 * Starting and extending artist and library stations against a fake server whose
 * responses are shaped like the live ones in
 * docs/decisions/2026-10-23-artist-radio-design.md.
 */
@RunWith(RobolectricTestRunner::class)
class PlexRadioRepositoryTest {
    private val artist = PlexMediaMapper.StationSeed.Artist("270597")
    private val fixture = PlexBrowseTestServer()
    private val requests = mutableListOf<RecordedRequest>()
    private var stationMints = 0
    private var playQueueStatus = 200
    private var windowAtEnd = false
    private var windowMidQueue = false

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
                        path == "/playQueues/32194" && windowAtEnd -> json(endOfQueue())
                        path == "/playQueues/32194" && windowMidQueue -> json(endOfQueue(offset = 10))
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
            val response = (repo.startStation(artist) as Either.Right).value

            val uri = posted().single().requestUrl!!.queryParameter("uri")
            assertEquals("server://abc123/com.plexapp.plugins.library/library/metadata/270597/station/uuid-1?type=10", uri)
            assertEquals(listOf("11", "12"), repo.stationTracks(response, artist).map { it.mediaId })
        }

    @Test
    fun everyTapFetchesAFreshStationKey() =
        runTest {
            val repo = repository()
            repo.startStation(artist)
            repo.startStation(artist)

            val keys = posted().map { it.requestUrl!!.queryParameter("uri") }
            assertEquals(2, keys.toSet().size)
        }

    @Test
    fun aSessionWithoutAMachineIdentifierAsksTheServer() =
        runTest {
            PlexApi().machineIdentifier = null

            repository().startStation(artist)

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
            val response = (repo.startStation(artist) as Either.Right).value

            val tags = repo.stationTracks(response, artist).map { PlexMediaMapper.stationTagOf(it) }

            assertEquals(
                listOf(
                    PlexMediaMapper.StationTag(32194, 101, artist),
                    PlexMediaMapper.StationTag(32194, 102, artist),
                ),
                tags,
            )
        }

    @Test
    fun anArtistWithoutAStationStartsNothing() =
        runTest {
            val repo = repository()
            val noStation = PlexMediaMapper.StationSeed.Artist("1")
            val response = (repo.startStation(noStation) as Either.Right).value

            assertTrue(repo.stationTracks(response, noStation).isEmpty())
            assertTrue(posted().isEmpty())
        }

    @Test
    fun extendingReadsTheQueueBack() =
        runTest {
            val tracks = (repository().extendStation(PlexMediaMapper.StationTag(32194, 102, artist), 102, false) as Either.Right).value

            assertEquals(listOf("11", "12", "13"), tracks.map { it.mediaId })
        }

    @Test
    fun aQueueTheServerDiscardedIsReplacedByAFreshStation() =
        runTest {
            playQueueStatus = 404

            val tracks = (repository().extendStation(PlexMediaMapper.StationTag(32194, 102, artist), 102, false) as Either.Right).value

            assertEquals(1, posted().size)
            assertEquals(listOf("11", "12"), tracks.map { it.mediaId })
        }

    @Test
    fun anyOtherFailureIsReturnedNotRedrawn() =
        runTest {
            playQueueStatus = 500

            val result = repository().extendStation(PlexMediaMapper.StationTag(32194, 102, artist), 102, false)

            assertEquals(PlexTransportFailure.Http(PlexHost.Server, 500), (result as Either.Left).value)
            assertTrue(posted().isEmpty())
        }

    @Test
    fun aLibraryStationStartsFromItsKeyWithoutFetchingOne() =
        runTest {
            val seed = PlexMediaMapper.StationSeed.Library("/library/sections/7/stations/2")
            val repo = repository()

            val response = (repo.startStation(seed) as Either.Right).value

            assertEquals(
                "server://abc123/com.plexapp.plugins.library/library/sections/7/stations/2",
                posted().single().requestUrl!!.queryParameter("uri"),
            )
            assertEquals(
                PlexMediaMapper.StationTag(32194, 101, seed),
                PlexMediaMapper.stationTagOf(repo.stationTracks(response, seed).first()),
            )
        }

    private fun endOfQueue(offset: Int = 77) =
        """{"MediaContainer":{"playQueueID":32194,"playQueueTotalCount":80,
        "playQueueSelectedItemID":120,"playQueueSelectedItemOffset":$offset,"Metadata":[
        {"ratingKey":"19","type":"track","playQueueItemID":119},
        {"ratingKey":"20","type":"track","playQueueItemID":120},
        {"ratingKey":"21","type":"track","playQueueItemID":121},
        {"ratingKey":"22","type":"track","playQueueItemID":122}]}}"""

    private val timeTravel = PlexMediaMapper.StationSeed.Library("/library/sections/7/stations/2")

    private val mix =
        PlexMediaMapper.StationSeed.Mix("/library/sections/7/all?artist.id=259987,270963&or=1&album.id=256469,261379")

    @Test
    fun aMixStartsFromItsKeyShuffled() =
        runTest {
            val repo = repository()

            val response = (repo.startStation(mix) as Either.Right).value

            val url = posted().single().requestUrl!!
            assertEquals("server://abc123/com.plexapp.plugins.library${mix.key}", url.queryParameter("uri"))
            assertEquals("1", url.queryParameter("shuffle"))
            assertEquals(
                PlexMediaMapper.StationTag(32194, 101, mix),
                PlexMediaMapper.stationTagOf(repo.stationTracks(response, mix).first()),
            )
        }

    @Test
    fun aLibraryStationIsNotShuffledByTheServer() =
        runTest {
            repository().startStation(timeTravel)

            assertEquals("0", posted().single().requestUrl!!.queryParameter("shuffle"))
        }

    @Test
    fun aMixAtItsEndIsDrawnAgainShuffled() =
        runTest {
            windowAtEnd = true

            repository().extendStation(PlexMediaMapper.StationTag(32194, 120, mix), lastQueuedItemId = 122, onLastTrack = false)

            assertEquals("1", posted().single().requestUrl!!.queryParameter("shuffle"))
        }

    @Test
    fun aDiscardedMixIsDrawnAgainShuffled() =
        runTest {
            playQueueStatus = 404

            repository().extendStation(PlexMediaMapper.StationTag(32194, 102, mix), 102, false)

            assertEquals("1", posted().single().requestUrl!!.queryParameter("shuffle"))
        }

    @Test
    fun aStationAtItsEndWithNothingNewIsDrawnAgain() =
        runTest {
            windowAtEnd = true

            val tracks =
                (
                    repository().extendStation(
                        PlexMediaMapper.StationTag(32194, 120, timeTravel),
                        lastQueuedItemId = 122,
                        onLastTrack = false,
                    ) as Either.Right
                ).value

            assertEquals(
                "server://abc123/com.plexapp.plugins.library/library/sections/7/stations/2",
                posted().single().requestUrl!!.queryParameter("uri"),
            )
            assertEquals(listOf("11", "12"), tracks.map { it.mediaId })
        }

    @Test
    fun theLastWindowIsAppendedBeforeAnyRedraw() =
        runTest {
            windowAtEnd = true

            val tracks =
                (
                    repository().extendStation(
                        PlexMediaMapper.StationTag(32194, 120, timeTravel),
                        lastQueuedItemId = 120,
                        onLastTrack = false,
                    ) as Either.Right
                ).value

            assertTrue(posted().isEmpty())
            assertEquals(listOf("19", "20", "21", "22"), tracks.map { it.mediaId })
        }

    @Test
    fun aQueueWithoutATotalIsNeverAtItsEnd() =
        runTest {
            repository().extendStation(PlexMediaMapper.StationTag(32194, 103, artist), 103, onLastTrack = true)

            assertTrue(posted().isEmpty())
        }

    @Test
    fun theLastTrackWithNothingNewIsRedrawnWhereverTheWindowSits() =
        runTest {
            windowMidQueue = true

            repository().extendStation(
                PlexMediaMapper.StationTag(32194, 120, timeTravel),
                lastQueuedItemId = 122,
                onLastTrack = true,
            )

            assertEquals(1, posted().size)
        }

    @Test
    fun theLastTrackIsNotRedrawnWhileTheWindowHoldsMore() =
        runTest {
            windowMidQueue = true

            repository().extendStation(
                PlexMediaMapper.StationTag(32194, 120, timeTravel),
                lastQueuedItemId = 120,
                onLastTrack = true,
            )

            assertTrue(posted().isEmpty())
        }

    @Test
    fun aWindowShortOfTheTotalIsNotRedrawnEvenWithNothingNew() =
        runTest {
            windowMidQueue = true

            val tracks =
                (
                    repository().extendStation(
                        PlexMediaMapper.StationTag(32194, 120, timeTravel),
                        lastQueuedItemId = 122,
                        onLastTrack = false,
                    ) as Either.Right
                ).value

            assertTrue(posted().isEmpty())
            assertEquals(listOf("19", "20", "21", "22"), tracks.map { it.mediaId })
        }

    @Test
    fun aDiscardedLibraryStationQueueIsReplacedFromItsOwnKey() =
        runTest {
            playQueueStatus = 404

            repository().extendStation(PlexMediaMapper.StationTag(32194, 120, timeTravel), 120, false)

            assertEquals(
                "server://abc123/com.plexapp.plugins.library/library/sections/7/stations/2",
                posted().single().requestUrl!!.queryParameter("uri"),
            )
        }
}
