package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cappielloantonio.tempo.model.SessionMediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DecadeKey
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

        callback = MediaLibrarySessionCallback(
            RuntimeEnvironment.getApplication(),
            mock<BaseMediaService>(),
            browseRepository,
            sessionMediaItemRepository
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
            MediaItem.Builder()
                .setMediaId(Constants.MIX_PLAYLIST_ID + PLAYLIST)
                .build()
        )

        assertShuffleUntouched()
    }

    /** The decade Mix is the third, and dispatches on its own prefix. */
    @Test
    fun tappingADecadeMixLeavesShuffleAlone() {
        whenever(browseRepository.getDecadeTracksForShuffle(DECADE))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        setMediaItems(
            MediaItem.Builder()
                .setMediaId(Constants.MIX_DECADE_ID + DECADE)
                .build()
        )

        assertShuffleUntouched()
    }

    /**
     * The redundant-fetch fix: the list on screen was already served through
     * `onGetChildren`, which cached it under `QUEUE_CACHED_SOURCE`. Tapping
     * the decade Mix right after browsing it must replay that list
     * rather than draw a second uniform random 500 -- statistically identical
     * for the tap, but ~300ms of dead air and a queue that does not match what
     * was on screen.
     *
     * Compared as a multiset, not a sequence: the cache hit feeds the same
     * `.shuffled()` every Mix goes through, so the queue is those tracks in a
     * different order. Which tracks is the claim here; that they are reordered
     * is pinned over thirty of them elsewhere in this class, where `.shuffled()`
     * returning the identity permutation is not a one-in-24 flake.
     */
    @Test
    fun `tapping a decade Mix row after browsing it serves the cached list`() {
        val tracks = albumTracks("1", "2", "3", "4")
        browseDecade(DECADE, tracks)

        val result = setMediaItems(decadeMixRow(DECADE))

        assertEquals(
            tracks.map { it.mediaId }.sorted(),
            result.mediaItems.map { it.mediaId }.sorted()
        )
        verify(browseRepository, never()).getDecadeTracksForShuffle(DECADE)
    }

    /**
     * The Mix row itself is playable but has no stream -- it exists to be
     * tapped, not to be queued. A queue that kept it would "play" a track that
     * does not exist. `drop(1)` on the cache hit path has to remove exactly it.
     */
    @Test
    fun `the cached decade Mix queue excludes the Mix row`() {
        val tracks = albumTracks("1", "2", "3", "4")
        browseDecade(DECADE, tracks)

        val result = setMediaItems(decadeMixRow(DECADE))

        assertTrue(
            "queue must not contain the Mix row: ${result.mediaItems.map { it.mediaId }}",
            result.mediaItems.none { it.mediaId == Constants.MIX_DECADE_ID + DECADE }
        )
    }

    /**
     * `queueSourceCache` is a single slot keyed by one constant, so it can hold
     * any node's most recent list -- here, a different decade's. The guard has
     * to notice that and fall back to a fresh fetch rather than queue the wrong
     * decade's tracks. Seeded by actually browsing the other decade, the same
     * way a real stale cache would arise, rather than by reaching into the
     * cache directly.
     */
    @Test
    fun `tapping a decade Mix row with a mismatched cache falls back to the repository`() {
        browseDecade(OTHER_DECADE, albumTracks("9"))
        val freshTracks = albumTracks("1", "2", "3", "4")
        whenever(browseRepository.getDecadeTracksForShuffle(DECADE))
            .thenReturn(itemList(freshTracks))

        val result = setMediaItems(decadeMixRow(DECADE))

        verify(browseRepository).getDecadeTracksForShuffle(DECADE)
        // A multiset, for the same reason as the cache-hit test above.
        assertEquals(
            freshTracks.map { it.mediaId }.sorted(),
            result.mediaItems.map { it.mediaId }.sorted()
        )
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
            result.mediaItems.map { it.mediaId }.sorted()
        )
        assertNotEquals(
            tracks.map { it.mediaId },
            result.mediaItems.map { it.mediaId }
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

        val result = setMediaItems(
            MediaItem.Builder()
                .setMediaId(Constants.MIX_PLAYLIST_ID + PLAYLIST)
                .build()
        )

        assertShuffleUntouched()
        assertEquals(0, result.startIndex)
        assertEquals(
            tracks.map { it.mediaId }.sorted(),
            result.mediaItems.map { it.mediaId }.sorted()
        )
        assertNotEquals(
            tracks.map { it.mediaId },
            result.mediaItems.map { it.mediaId }
        )
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

    // ─────────────────────────────────────────────────────────────

    private fun setMediaItems(tapped: MediaItem): MediaSession.MediaItemsWithStartPosition =
        mockConstruction(QueueRepository::class.java).use {
            callback.onSetMediaItems(
                session,
                controller,
                listOf(tapped),
                // What the car sends for a browse tap: it names the track and
                // says nothing about where to start.
                C.INDEX_UNSET,
                C.TIME_UNSET
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

    private fun mixArtistRow() = MediaItem.Builder()
        .setMediaId(Constants.MIX_ARTIST_ID + ARTIST)
        .build()

    /**
     * Built with the same [PlexMediaMapper.mixRowToMediaItem] the real row
     * uses, not a bare `MediaItem.Builder()`: [browseDecade] runs the row
     * through `LibraryResult.ofItemList`, which requires `isBrowsable` to be
     * set on every item, and a bare builder trips that check.
     */
    private fun decadeMixRow(decade: String) =
        PlexMediaMapper.mixRowToMediaItem(Constants.MIX_DECADE_ID + decade, "Decade Mix")

    /**
     * What a real browse of a decade leaves in `queueSourceCache`: the shuffle
     * row at index 0, then its tracks -- see
     * `PlexBrowseRepository.getDecadeTracks`. Driving it through
     * `onGetChildren`, the way `MediaLibrarySessionCallbackStartIndexTest`'s
     * `browseAlbum` does, rather than reaching into the private top-level cache
     * directly.
     */
    private fun browseDecade(decade: String, tracks: List<MediaItem>) {
        whenever(browseRepository.getDecadeTracks(decade))
            .thenReturn(itemList(listOf(decadeMixRow(decade)) + tracks))

        val children = callback.onGetChildren(
            mock<MediaLibraryService.MediaLibrarySession>(),
            controller,
            Constants.DECADE_ID + decade,
            0,
            Constants.MAX_ITEMS,
            null
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
                }
            )
        }
        whenever(sessionMediaItemRepository.getSiblings(GROUP)).thenReturn(tracks)
    }

    private fun itemList(tracks: List<MediaItem>) =
        Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(tracks), null))

    private fun albumTracks(vararg ratingKeys: String) = ratingKeys.map { key ->
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
            token = "server-token"
        )
    }

    private companion object {
        const val SERVER = "https://plex.example"
        const val ARTIST = "7"
        const val PLAYLIST = "88"

        /**
         * Whole DecadeKey payloads -- library and decade -- because that is
         * what a decade row's media id carries and therefore all the callback
         * ever sees. It never splits one, so these are opaque to it; written
         * in their real shape rather than as a bare "1980" so the guard below
         * is exercised on the string the car actually sends.
         */
        val DECADE = DecadeKey.of("abc123def456-4", "1980")
        val OTHER_DECADE = DecadeKey.of("abc123def456-4", "1970")

        /** Sibling group the cached tracks share -- see SessionMediaItemRepository. */
        const val GROUP = 1_700_000_000_000L
    }
}
