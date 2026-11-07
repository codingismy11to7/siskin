package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.model.SessionMediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.HubKey
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.ExecutionException

/**
 * That a browse tap never touches shuffle mode, and that a Mix row is served
 * pre-shuffled instead.
 *
 * **The invariant is a negative one**, which is why it is asserted on every
 * path rather than once: no tap, of any row, on either the set or the add
 * path, may write `shuffleModeEnabled`. The mode belongs to the driver and the
 * car's own control. A Mix is a queue handed over already shuffled, which is a
 * different thing from a mode -- see
 * `docs/decisions/2026-08-13-mix-rows-design.md`.
 *
 * Two writers used to live here and both are gone: one turned shuffle off on
 * every track tap, and one set it from the retired `car_shuffle` preference.
 * The tests for those were deleted with them. What is left pins the absence,
 * because the absence is the behaviour -- a future edit that "helpfully" clears
 * shuffle when starting a Mix would stomp a toggle the driver set by hand, and
 * nothing else in the suite would notice.
 *
 * Robolectric for the same reasons as the start-index tests: the fixtures are
 * real MediaItems built with a Uri and a Bundle, and the callback's constructor
 * reads real strings for its CommandButtons. QueueRepository is stubbed with
 * mockConstruction because onSetMediaItems writes the resolved queue to Room,
 * which is not what these tests are about.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaLibrarySessionCallbackShuffleTest {
    private val browseRepository = mock<PlexBrowseRepository>()
    private val sessionMediaItemRepository = mock<SessionMediaItemRepository>()
    private val session = mock<MediaSession>()
    private val controller = mock<MediaSession.ControllerInfo>()

    /** Held rather than built inline, because the assertions verify against it. */
    private val player = mock<Player>()

    private lateinit var callback: MediaLibrarySessionCallback

    @Before
    fun setUp() {
        // Robolectric keeps these preferences in a static field between methods,
        // so every field the gate reads is written here rather than assumed
        // absent.
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = SERVER
            musicSectionKey = "1"
        }
        whenever(session.player).thenReturn(player)

        callback =
            MediaLibrarySessionCallback(
                RuntimeEnvironment.getApplication(),
                mock<BaseMediaService>(),
                browseRepository,
                sessionMediaItemRepository,
            )
    }

    /**
     * A plain track tap. This used to write `false`, to clear a shuffle that a
     * row had turned on; with no row turning it on, that write could only ever
     * cancel a shuffle the driver had switched on with the car's own control,
     * mid-drive, for tapping a song.
     */
    @Test
    fun tappingATrackLeavesShuffleAlone() {
        val tracks = albumTracks("1", "2", "3", "4")
        rememberAsSiblings(tracks)

        setMediaItems(MediaItem.Builder().setMediaId("3").build())

        assertShuffleUntouched()
    }

    /** The artist Mix, which used to be the row that turned shuffle on. */
    @Test
    fun tappingAnArtistMixLeavesShuffleAlone() {
        whenever(browseRepository.getArtistTracks(ARTIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        setMediaItems(mixArtistRow())

        assertShuffleUntouched()
    }

    /** The playlist Mix is the second, and dispatches on its own prefix. */
    @Test
    fun tappingAPlaylistMixLeavesShuffleAlone() {
        whenever(browseRepository.getPlaylistTracksForShuffle(PLAYLIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        setMediaItems(
            MediaItem
                .Builder()
                .setMediaId(Constants.MIX_PLAYLIST_ID + PLAYLIST)
                .build(),
        )

        assertShuffleUntouched()
    }

    /**
     * The queue handed over is a reordering of what the repository returned:
     * every track, none invented, and not the order it arrived in.
     *
     * Thirty tracks rather than four, and the number is the whole point.
     * `.shuffled()` is permitted to return the identity permutation, so an
     * order assertion over four tracks would fail once in twenty-four runs;
     * over thirty it is one in 30!, which is never.
     *
     * Both halves are load-bearing. The multiset alone would pass with the
     * shuffle deleted from the implementation entirely -- it only says the
     * queue is the tracks that were fetched. The order alone would pass if
     * tracks went missing.
     */
    @Test
    fun `a Mix queue is a reordering of the fetched tracks`() {
        val tracks = albumTracks(*(1..30).map { "$it" }.toTypedArray())
        whenever(browseRepository.getArtistTracks(ARTIST)).thenReturn(itemList(tracks))

        val result = setMediaItems(mixArtistRow())

        assertEquals(
            tracks.map { it.mediaId }.sorted(),
            result.mediaItems.map { it.mediaId }.sorted(),
        )
        assertNotEquals(
            tracks.map { it.mediaId },
            result.mediaItems.map { it.mediaId },
        )
    }

    /**
     * The head of a shuffled list is already a random draw, so the opener is 0
     * rather than a second draw that would skip a prefix of the queue.
     *
     * Named rather than left to the `else` branch: the tapped row is absent from
     * the queue it built, so that branch falls back to C.INDEX_UNSET, which
     * media3 happens to resolve to item 0 -- the right answer, arrived at by
     * accident. This pins the chosen one.
     */
    @Test
    fun `a Mix opens at the top of the queue`() {
        whenever(browseRepository.getArtistTracks(ARTIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        assertEquals(0, setMediaItems(mixArtistRow()).startIndex)
    }

    /** The playlist Mix is the other one, and gets the same treatment. */
    @Test
    fun `the playlist Mix is shuffled here too`() {
        val tracks = albumTracks(*(1..30).map { "$it" }.toTypedArray())
        whenever(browseRepository.getPlaylistTracksForShuffle(PLAYLIST))
            .thenReturn(itemList(tracks))

        val result =
            setMediaItems(
                MediaItem
                    .Builder()
                    .setMediaId(Constants.MIX_PLAYLIST_ID + PLAYLIST)
                    .build(),
            )

        assertShuffleUntouched()
        assertEquals(0, result.startIndex)
        assertEquals(
            tracks.map { it.mediaId }.sorted(),
            result.mediaItems.map { it.mediaId }.sorted(),
        )
        assertNotEquals(
            tracks.map { it.mediaId },
            result.mediaItems.map { it.mediaId },
        )
    }

    /**
     * A station plays in the server's order: the server advances its window
     * along that order, so a shuffled station would skip ahead of what the
     * server thinks is playing.
     */
    @Test
    fun aStationIsServedInTheServersOrder() {
        val tracks = albumTracks("1", "2", "3", "4", "5", "6", "7", "8")
        whenever(browseRepository.getArtistRadioTracks(ARTIST)).thenReturn(itemList(tracks))

        val result = setMediaItems(MediaItem.Builder().setMediaId(Constants.MIX_RADIO_ARTIST_ID + ARTIST).build())

        assertEquals(tracks.map { it.mediaId }, result.mediaItems.map { it.mediaId })
        assertShuffleUntouched()
    }

    @Test
    fun aLibraryStationIsServedInTheServersOrder() {
        val station = HubKey.of("machine-7", "/library/sections/7/stations/2")
        val tracks = albumTracks("1", "2", "3", "4", "5", "6", "7", "8")
        whenever(browseRepository.getRadioStationTracks(station)).thenReturn(itemList(tracks))

        val result = setMediaItems(MediaItem.Builder().setMediaId(Constants.MIX_RADIO_STATION_ID + station).build())

        assertEquals(tracks.map { it.mediaId }, result.mediaItems.map { it.mediaId })
        assertShuffleUntouched()
    }

    @Test
    fun aRefusedStationFailsTheTapSoWhatIsPlayingContinues() {
        val station = HubKey.of("machine-7", "/library/sections/7/stations/2")
        whenever(browseRepository.getRadioStationTracks(station))
            .thenReturn(Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)))

        assertThrows(ExecutionException::class.java) {
            setMediaItems(MediaItem.Builder().setMediaId(Constants.MIX_RADIO_STATION_ID + station).build())
        }
    }

    /**
     * `onAddMediaItems` is not only a browse-tap path: MediaManager's continuous
     * play appends instant-mix tracks to a running queue through
     * `browser.addMediaItems`. This is the case that made a shuffle write here
     * dangerous even when the rows still owned the toggle -- a write on every
     * call would have cleared shuffle *mid-listen* every time the queue topped
     * itself up, which is exactly during the long session it would ruin.
     *
     * Now nothing writes it on any path, so this is a much cheaper guarantee
     * than it used to be. It is kept because the top-up is the case where a
     * reintroduced write would be least visible in review and most annoying in
     * the car.
     */
    @Test
    fun addingTracksToARunningQueueLeavesShuffleAlone() {
        val mixTrack = albumTracks("9").single()

        callback.onAddMediaItems(session, controller, listOf(mixTrack)).get()

        assertShuffleUntouched()
    }

    /**
     * The add path is a browse tap too, on cars that add rather than set. The
     * queue it received from resolveQueueForItem is already shuffled, and the
     * toggle stays exactly where the driver left it.
     */
    @Test
    fun `an added Mix row leaves shuffle alone`() {
        whenever(browseRepository.getArtistTracks(ARTIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        callback.onAddMediaItems(session, controller, listOf(mixArtistRow())).get()

        assertShuffleUntouched()
    }

    /**
     * The hub Mix's cache hit: the ids of the containers already on screen,
     * expanded to tracks in one request rather than following the hub's key a
     * second time. This still issues a request -- see
     * `MediaLibrarySessionCallback.cachedHubTracks`'s KDoc for why a hub's
     * browse list (albums and artists) cannot be replayed as a queue.
     *
     * Both container kinds on screen at once, not just albums: Plex's
     * `music.vault` and `music.recent.played` hubs are `type=artist`, so the
     * artist branch of `cachedHubTracks` is a primary path in the car, not an
     * edge case. Asserting the exact call pins the KDoc's claim that "a hub
     * mixing albums and artists sends both filters" -- albums first, artists
     * second, matching `getHubTracksForIds(albumIds, artistIds)`.
     */
    @Test
    fun aHubMixExpandsTheIdsAlreadyOnScreen() {
        val hubKey = "abc123-7|/library/sections/7/all?type=9"
        browseHub(hubKey, albumIds = listOf("111", "222"), artistIds = listOf("333", "444"))
        whenever(browseRepository.getHubTracksForIds(listOf("111", "222"), listOf("333", "444")))
            .thenReturn(itemList(albumTracks("50", "51")))

        callback.onAddMediaItems(session, controller, listOf(mixRow(Constants.MIX_HUB_ID + hubKey)))

        verify(browseRepository).getHubTracksForIds(listOf("111", "222"), listOf("333", "444"))
        verify(browseRepository, never()).getHubTracksForShuffle(any())
    }

    /**
     * The hub's browse list holds albums and artists -- browsable, with no
     * stream -- so the queue a hub Mix produces must be exclusively tracks
     * expanded from those ids, never the browsable rows themselves.
     *
     * The browsable check alone passes even with dispatch broken entirely: the
     * fallback path that queues the bare tapped row produces a `MediaItem`
     * with no `isBrowsable` set, so a build that never reaches
     * `getHubTracksForIds` at all would still satisfy it. The multiset
     * assertion closes that gap by pinning what the queue *does* contain --
     * exactly the expanded tracks, none invented, none missing. Compared as a
     * multiset rather than a sequence because `resolveQueueForItem` shuffles a
     * Mix's queue, same as every other Mix in this file.
     */
    @Test
    fun aHubMixNeverQueuesABrowsableItem() {
        val hubKey = "abc123-7|/library/sections/7/all?type=9"
        browseHub(hubKey, albumIds = listOf("111"))
        val tracks = albumTracks("50", "51")
        whenever(browseRepository.getHubTracksForIds(listOf("111"), emptyList()))
            .thenReturn(itemList(tracks))

        val queued =
            callback
                .onAddMediaItems(session, controller, listOf(mixRow(Constants.MIX_HUB_ID + hubKey)))
                .get()

        assertTrue(queued.none { it.mediaMetadata.isBrowsable == true })
        assertEquals(
            tracks.map { it.mediaId }.sorted(),
            queued.map { it.mediaId }.sorted(),
        )
    }

    /**
     * `queueSourceCache` is a single slot, so it can hold another hub's most
     * recent list -- here, one whose key differs only in its query string. The
     * guard has to notice that and fall back to following this hub's key fresh
     * rather than mix the wrong hub's containers.
     */
    @Test
    fun aHubMixFallsBackWhenTheCacheHoldsAnotherHub() {
        browseHub("abc123-7|/library/sections/7/all?type=9", albumIds = listOf("111"))
        val other = "abc123-7|/library/sections/7/all?type=8"
        whenever(browseRepository.getHubTracksForShuffle(other))
            .thenReturn(itemList(albumTracks("9")))

        callback.onAddMediaItems(session, controller, listOf(mixRow(Constants.MIX_HUB_ID + other)))

        verify(browseRepository).getHubTracksForShuffle(other)
    }

    /**
     * `getHubTracksForIds(emptyList(), emptyList())` would send neither
     * filter, which Plex answers by shuffling the whole library rather than
     * the hub -- 500 arbitrary tracks with nothing to do with what was on
     * screen. A cache hit whose rows expand to no ids at all (the Mix row
     * alone, nothing after it) must fall back to following the hub's key
     * instead of sending that request.
     */
    @Test
    fun `a hub mix with no container ids in the cache falls back rather than shuffling the whole library`() {
        val hubKey = "abc123-7|/library/sections/7/all?type=9"
        browseHub(hubKey)
        whenever(browseRepository.getHubTracksForShuffle(hubKey))
            .thenReturn(itemList(albumTracks("9")))

        callback.onAddMediaItems(session, controller, listOf(mixRow(Constants.MIX_HUB_ID + hubKey)))

        verify(browseRepository, never()).getHubTracksForIds(any(), any())
        verify(browseRepository).getHubTracksForShuffle(hubKey)
    }

    /**
     * A Mix kind this build has dropped, still on a car's screen from before
     * the upgrade. Treated as a track it would be handed to the player with no
     * stream and saved as the whole queue, so it is refused before either.
     */
    @Test
    fun `a Mix row of a kind this build no longer has is refused without touching the queue`() {
        val stale = mixRow("[shuffleDecadeID]abc123-7|1980")

        mockConstruction(QueueRepository::class.java).use { construction ->
            val set =
                assertThrows(ExecutionException::class.java) {
                    callback.onSetMediaItems(session, controller, listOf(stale), C.INDEX_UNSET, C.TIME_UNSET).get()
                }
            val add =
                assertThrows(ExecutionException::class.java) {
                    callback.onAddMediaItems(session, controller, listOf(stale)).get()
                }

            assertTrue(set.cause is UnsupportedOperationException)
            assertTrue(add.cause is UnsupportedOperationException)
            assertTrue(construction.constructed().isEmpty())
        }
    }

    // ─────────────────────────────────────────────────────────────

    private fun setMediaItems(tapped: MediaItem): MediaSession.MediaItemsWithStartPosition =
        mockConstruction(QueueRepository::class.java).use {
            callback
                .onSetMediaItems(
                    session,
                    controller,
                    listOf(tapped),
                    // What the car sends for a browse tap: it names the track and
                    // says nothing about where to start.
                    C.INDEX_UNSET,
                    C.TIME_UNSET,
                ).get()
        }

    /**
     * Exhaustive for a Boolean: verifying both values never happen says the
     * setter was not called at all, without depending on an argument matcher
     * against a synthetic property setter.
     */
    private fun assertShuffleUntouched() {
        verify(player, never()).shuffleModeEnabled = true
        verify(player, never()).shuffleModeEnabled = false
    }

    private fun mixArtistRow() =
        MediaItem
            .Builder()
            .setMediaId(Constants.MIX_ARTIST_ID + ARTIST)
            .build()

    /** A bare tapped Mix row, the shape the car echoes back on any Mix tap. */
    private fun mixRow(mediaId: String) =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .build()

    /**
     * Built with the same [PlexMediaMapper.mixRowToMediaItem] the real row
     * uses, not a bare `MediaItem.Builder()`: [browseHub] runs it through
     * `LibraryResult.ofItemList`, which requires `isBrowsable` to be set on
     * every item, and a bare builder trips that check.
     */
    private fun hubMixRow(hubKey: String) = PlexMediaMapper.mixRowToMediaItem(Constants.MIX_HUB_ID + hubKey, "Hub Mix")

    /** A hub's album container row, as `PlexBrowseRepository.getHubContent` builds one. */
    private fun albumRow(ratingKey: String) =
        MediaItem
            .Builder()
            .setMediaId(Constants.ALBUM_ID + ratingKey)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle("Album $ratingKey")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            ).build()

    /** A hub's artist container row, as `PlexBrowseRepository.getHubContent` builds one. */
    private fun artistRow(ratingKey: String) =
        MediaItem
            .Builder()
            .setMediaId(Constants.ARTIST_ID + ratingKey)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle("Artist $ratingKey")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            ).build()

    /**
     * What a real browse of a hub leaves in `queueSourceCache`: the Mix row
     * at index 0, then its containers -- see
     * `PlexBrowseRepository.getHubContent`. Driven through `onGetChildren`,
     * the way `MediaLibrarySessionCallbackStartIndexTest`'s `browseAlbum`
     * does, rather than by reaching into the private top-level cache directly.
     */
    private fun browseHub(
        hubKey: String,
        albumIds: List<String> = emptyList(),
        artistIds: List<String> = emptyList(),
    ) {
        val containers = albumIds.map { albumRow(it) } + artistIds.map { artistRow(it) }
        whenever(browseRepository.getHubContent(hubKey))
            .thenReturn(itemList(listOf(hubMixRow(hubKey)) + containers))

        val children =
            callback
                .onGetChildren(
                    mock<MediaLibraryService.MediaLibrarySession>(),
                    controller,
                    Constants.HUB_ID + hubKey,
                    0,
                    Constants.MAX_ITEMS,
                    null,
                ).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, children.resultCode)
    }

    /** What a browse list leaves in the session cache for a later tap. */
    private fun rememberAsSiblings(tracks: List<MediaItem>) {
        tracks.forEach { track ->
            whenever(sessionMediaItemRepository.get(track.mediaId)).thenReturn(
                SessionMediaItem().apply {
                    id = track.mediaId
                    timestamp = GROUP
                },
            )
        }
        whenever(sessionMediaItemRepository.getSiblings(GROUP)).thenReturn(tracks)
    }

    private fun itemList(tracks: List<MediaItem>) = Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(tracks), null))

    private fun albumTracks(vararg ratingKeys: String) =
        ratingKeys.map { key ->
            PlexMediaMapper.buildTrackMediaItem(
                ratingKey = key,
                title = "Track $key",
                albumTitle = "AMEN Remixes",
                artist = "Artist",
                thumb = null,
                partKey = "/library/parts/$key/file.flac",
                durationMs = null,
                trackIndex = null,
                year = null,
                grandparentRatingKey = ARTIST,
                isHearted = false,
                parentId = Constants.QUEUE_CACHED_SOURCE,
                serverUri = SERVER,
                token = "server-token",
            )
        }

    private companion object {
        const val SERVER = "https://plex.example"
        const val ARTIST = "7"
        const val PLAYLIST = "88"

        /** Sibling group the cached tracks share -- see SessionMediaItemRepository. */
        const val GROUP = 1_700_000_000_000L
    }
}
