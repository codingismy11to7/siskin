package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.model.SessionMediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.ConstantsAA
import com.cappielloantonio.tempo.util.Preferences
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * What shuffle mode a browse tap leaves the player in.
 *
 * The rule is that the tapped row decides: a shuffle row turns shuffle on,
 * every other tap turns it off. Before this, nothing ever turned it off, so one
 * tap on "Shuffle this artist" left every later tap shuffled.
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
        // Defaults to true, and a leak of false from another class would make
        // the three on-branch tests below pass for the wrong reason.
        App.getInstance().preferences.edit().remove("car_shuffle").commit()

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
     * The tap this whole change exists for: a plain track, which means "play
     * this one and then what follows it", and under shuffle neither half of
     * that is true.
     */
    @Test
    fun tappingATrackTurnsShuffleOff() {
        val tracks = albumTracks("1", "2", "3", "4")
        rememberAsSiblings(tracks)

        setMediaItems(MediaItem.Builder().setMediaId("3").build())

        verify(player).shuffleModeEnabled = false
    }

    /** The one row that is allowed to turn it on. */
    @Test
    fun tappingAShuffleRowTurnsShuffleOn() {
        val tracks = albumTracks("1", "2", "3", "4")
        whenever(browseRepository.getArtistTracks(ARTIST)).thenReturn(itemList(tracks))

        val row = MediaItem.Builder()
            .setMediaId(ConstantsAA.SHUFFLE_ARTIST_ID + ARTIST)
            .build()
        setMediaItems(row)

        verify(player).shuffleModeEnabled = true
    }

    /** The playlist row is the other one, and dispatches on its own prefix. */
    @Test
    fun tappingAPlaylistShuffleRowTurnsShuffleOn() {
        val tracks = albumTracks("1", "2", "3", "4")
        whenever(browseRepository.getPlaylistTracksForShuffle(PLAYLIST))
            .thenReturn(itemList(tracks))

        val row = MediaItem.Builder()
            .setMediaId(ConstantsAA.SHUFFLE_PLAYLIST_ID + PLAYLIST)
            .build()
        setMediaItems(row)

        verify(player).shuffleModeEnabled = true
    }

    /**
     * With the setting off the queue is ours to order, so the player must be
     * told *not* to shuffle -- and told rather than left alone, because a toggle
     * left on from an earlier listen would shuffle a shuffled queue and undo the
     * point of the setting.
     */
    @Test
    fun `with the car's shuffle off a shuffle row leaves the player unshuffled`() {
        Preferences.setCarShuffleEnabled(false)
        whenever(browseRepository.getArtistTracks(ARTIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        setMediaItems(shuffleArtistRow())

        verify(player).shuffleModeEnabled = false
        verify(player, never()).shuffleModeEnabled = true
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
    fun `with the car's shuffle off the queue is a reordering of the fetched tracks`() {
        Preferences.setCarShuffleEnabled(false)
        val tracks = albumTracks(*(1..30).map { "$it" }.toTypedArray())
        whenever(browseRepository.getArtistTracks(ARTIST)).thenReturn(itemList(tracks))

        val result = setMediaItems(shuffleArtistRow())

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
    fun `with the car's shuffle off a shuffle row opens at the top of the queue`() {
        Preferences.setCarShuffleEnabled(false)
        whenever(browseRepository.getArtistTracks(ARTIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        assertEquals(0, setMediaItems(shuffleArtistRow()).startIndex)
    }

    /** The playlist row is the other one, and gets the same treatment. */
    @Test
    fun `with the car's shuffle off the playlist row is shuffled here too`() {
        Preferences.setCarShuffleEnabled(false)
        val tracks = albumTracks(*(1..30).map { "$it" }.toTypedArray())
        whenever(browseRepository.getPlaylistTracksForShuffle(PLAYLIST))
            .thenReturn(itemList(tracks))

        val result = setMediaItems(
            MediaItem.Builder()
                .setMediaId(ConstantsAA.SHUFFLE_PLAYLIST_ID + PLAYLIST)
                .build()
        )

        verify(player).shuffleModeEnabled = false
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
     * The add path must not be made total the way the set path was.
     *
     * `onAddMediaItems` is also how MediaManager.continuousPlay appends
     * instant-mix tracks to a running queue -- see MediaManager's
     * `browser.addMediaItems` calls -- so clearing shuffle here would turn it
     * off mid-listen every time the queue topped itself up, which is precisely
     * during the long shuffle-this-artist session it would ruin. Enable-only is
     * safe by construction: a mix track is never a shuffle row.
     */
    @Test
    fun addingTracksToARunningQueueLeavesShuffleAlone() {
        val mixTrack = albumTracks("9").single()

        callback.onAddMediaItems(session, controller, listOf(mixTrack)).get()

        verify(player, never()).shuffleModeEnabled = false
        verify(player, never()).shuffleModeEnabled = true
    }

    /**
     * The add path is a browse tap too on cars that add rather than set, and
     * with the setting off the queue it just received from resolveQueueForItem
     * was already shuffled. Enabling the toggle would shuffle it a second time.
     */
    @Test
    fun `with the car's shuffle off an added shuffle row does not enable the toggle`() {
        Preferences.setCarShuffleEnabled(false)
        whenever(browseRepository.getArtistTracks(ARTIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        callback.onAddMediaItems(session, controller, listOf(shuffleArtistRow())).get()

        verify(player, never()).shuffleModeEnabled = true
    }

    /** And with the setting on, the add path still defers the way it always did. */
    @Test
    fun `an added shuffle row still enables the toggle when the car's shuffle is on`() {
        whenever(browseRepository.getArtistTracks(ARTIST))
            .thenReturn(itemList(albumTracks("1", "2", "3", "4")))

        callback.onAddMediaItems(session, controller, listOf(shuffleArtistRow())).get()

        verify(player).shuffleModeEnabled = true
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

    private fun shuffleArtistRow() = MediaItem.Builder()
        .setMediaId(ConstantsAA.SHUFFLE_ARTIST_ID + ARTIST)
        .build()

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
            parentId = ConstantsAA.QUEUE_CACHED_SOURCE,
            serverUri = SERVER,
            token = "server-token"
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
