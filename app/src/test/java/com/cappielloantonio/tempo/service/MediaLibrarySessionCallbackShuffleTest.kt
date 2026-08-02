package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import com.cappielloantonio.tempo.model.SessionMediaItem
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.ConstantsAA
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
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
