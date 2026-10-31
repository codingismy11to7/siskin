package com.cappielloantonio.tempo.service

import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.PlexTransportException
import com.cappielloantonio.tempo.repository.VoiceSearchHits
import com.cappielloantonio.tempo.repository.VoiceTrackHit
import com.cappielloantonio.tempo.util.Constants
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class VoiceRequestResolverTest {
    private val browse = mock<PlexBrowseRepository>()
    private val resolver = VoiceRequestResolver(browse) { true }

    private fun named(
        id: String,
        title: String,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        ).build()

    private fun artist(
        id: String,
        title: String,
    ) = named(Constants.ARTIST_ID + id, title)

    private fun album(
        id: String,
        title: String,
    ) = named(Constants.ALBUM_ID + id, title)

    private fun track(
        id: String,
        title: String,
        albumKey: String? = "b1",
    ) = VoiceTrackHit(named(id, title), albumKey)

    private fun tracks(vararg ids: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(ids.map { named(it, it) }), null))

    private fun errorResult(code: Int): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(LibraryResult.ofError(code))

    private fun hits(
        artists: List<MediaItem> = emptyList(),
        albums: List<MediaItem> = emptyList(),
        tracks: List<VoiceTrackHit> = emptyList(),
    ) {
        whenever(browse.voiceSearch(any())).thenReturn(Futures.immediateFuture(VoiceSearchHits(artists, albums, tracks)))
    }

    private fun request(
        query: String,
        focus: String? = null,
        artist: String? = null,
        album: String? = null,
        title: String? = null,
    ) = VoiceRequest(query, focus, artist, album, title)

    private fun resolve(
        request: VoiceRequest,
        resume: ResumePoint? = null,
    ) = resolver.resolve(request, resume).get()

    private fun played(outcome: VoiceOutcome) = (outcome as VoiceOutcome.Play).items.map { it.mediaId }

    @Test
    fun `signed out fails before anything is searched`() {
        val outcome = VoiceRequestResolver(browse) { false }.resolve(request("x"), null).get()

        assertEquals(VoiceOutcome.Failed(VoiceFailure.SignIn), outcome)
        verify(browse, never()).voiceSearch(any())
    }

    @Test
    fun `an empty query resumes what the player holds`() {
        val point = ResumePoint(listOf(named("1", "one")), 0, 5_000L)

        assertEquals(VoiceOutcome.Resume(point), resolve(request(""), point))
        verify(browse, never()).voiceSearch(any())
    }

    @Test
    fun `an empty query with nothing to resume shuffles the library`() {
        whenever(browse.getLibraryShuffle()).thenReturn(tracks("1", "2", "3"))

        val outcome = resolve(request("")) as VoiceOutcome.Play

        assertEquals(setOf("1", "2", "3"), outcome.items.map { it.mediaId }.toSet())
        assertEquals(0, outcome.index)
    }

    @Test
    fun `an exact track name beats an exact album and artist`() {
        hits(listOf(artist("a", "Aerials")), listOf(album("b", "Aerials")), listOf(track("t", "aerials", "b9")))
        whenever(browse.getAlbumTracks("b9")).thenReturn(tracks("s", "t", "u"))

        val outcome = resolve(request("Aerials")) as VoiceOutcome.Play

        assertEquals(listOf("s", "t", "u"), outcome.items.map { it.mediaId })
        assertEquals(1, outcome.index)
    }

    @Test
    fun `an exact album beats an exact artist`() {
        hits(listOf(artist("a", "Toxicity")), listOf(album("b", "Toxicity")), listOf(track("t", "Toxicity (Live)")))
        whenever(browse.getAlbumTracks("b")).thenReturn(tracks("1", "2"))

        assertEquals(listOf("1", "2"), played(resolve(request("Toxicity"))))
    }

    @Test
    fun `with nothing exact the first result in search order plays`() {
        hits(listOf(artist("a", "Tool Academy")), listOf(album("b", "Toolbox")), listOf(track("t", "Toolshed")))
        whenever(browse.getArtistTracks("a")).thenReturn(tracks("1"))

        assertEquals(listOf("1"), played(resolve(request("tool"))))
    }

    @Test
    fun `an artist focus searches the artist name and picks that tier`() {
        hits(listOf(artist("a", "Tool")), emptyList(), listOf(track("t", "Tool")))
        whenever(browse.getArtistTracks("a")).thenReturn(tracks("1", "2"))

        val request = request("play tool", MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE, artist = "Tool")

        assertEquals(setOf("1", "2"), played(resolve(request)).toSet())
        verify(browse).voiceSearch("Tool")
    }

    @Test
    fun `an album focus picks that tier`() {
        hits(listOf(artist("a", "Lateralus")), listOf(album("b", "Lateralus")))
        whenever(browse.getAlbumTracks("b")).thenReturn(tracks("1", "2"))

        val request = request("lateralus", MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE, album = "Lateralus")

        assertEquals(listOf("1", "2"), played(resolve(request)))
    }

    @Test
    fun `a track focus picks that tier`() {
        hits(listOf(artist("a", "Sober")), listOf(album("b", "Sober")), listOf(track("t", "Sober", albumKey = null)))

        val request = request("sober", MediaStore.Audio.Media.ENTRY_CONTENT_TYPE, title = "Sober")

        assertEquals(listOf("t"), played(resolve(request)))
    }

    @Test
    fun `a focus whose tier is empty still plays something`() {
        hits(tracks = listOf(track("t", "Sober", albumKey = null)))

        val request = request("sober", MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE, artist = "Sober")

        assertEquals(listOf("t"), played(resolve(request)))
    }

    @Test
    fun `a track whose album fetch fails plays alone`() {
        hits(tracks = listOf(track("t", "Sober", "b9")))
        whenever(browse.getAlbumTracks("b9")).thenReturn(Futures.immediateFailedFuture(RuntimeException("boom")))

        assertEquals(listOf("t"), played(resolve(request("Sober"))))
    }

    @Test
    fun `a track its album does not list plays alone`() {
        hits(tracks = listOf(track("t", "Sober", "b9")))
        whenever(browse.getAlbumTracks("b9")).thenReturn(tracks("x", "y"))

        assertEquals(listOf("t"), played(resolve(request("Sober"))))
    }

    @Test
    fun `no match is not found`() {
        hits()

        assertEquals(VoiceOutcome.Failed(VoiceFailure.NotFound), resolve(request("x")))
    }

    @Test
    fun `a matched artist with no tracks is not found`() {
        hits(listOf(artist("a", "x")))
        whenever(browse.getArtistTracks("a")).thenReturn(tracks())

        assertEquals(VoiceOutcome.Failed(VoiceFailure.NotFound), resolve(request("x")))
    }

    @Test
    fun `a dead server is unreachable`() {
        whenever(browse.voiceSearch(any())).thenReturn(
            Futures.immediateFailedFuture(PlexTransportException(PlexTransportFailure.Unreachable(PlexHost.Server))),
        )

        assertEquals(VoiceOutcome.Failed(VoiceFailure.Unreachable), resolve(request("x")))
    }

    @Test
    fun `a rejected token is sign in`() {
        whenever(browse.voiceSearch(any())).thenReturn(
            Futures.immediateFailedFuture(PlexTransportException(PlexTransportFailure.Http(PlexHost.Server, 401))),
        )

        assertEquals(VoiceOutcome.Failed(VoiceFailure.SignIn), resolve(request("x")))
    }

    @Test
    fun `a rejected expansion is sign in`() {
        hits(listOf(artist("a", "x")))
        whenever(browse.getArtistTracks("a")).thenReturn(errorResult(SessionError.ERROR_PERMISSION_DENIED))

        assertEquals(VoiceOutcome.Failed(VoiceFailure.SignIn), resolve(request("x")))
    }

    @Test
    fun `a failed expansion is unreachable`() {
        hits(listOf(artist("a", "x")))
        whenever(browse.getArtistTracks("a")).thenReturn(errorResult(SessionError.ERROR_IO))

        assertEquals(VoiceOutcome.Failed(VoiceFailure.Unreachable), resolve(request("x")))
    }

    @Test
    fun `requestMetadata reads the query and hints`() {
        val extras =
            android.os.Bundle().apply {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, "focus")
                putString(MediaStore.EXTRA_MEDIA_ARTIST, "artist")
                putString(MediaStore.EXTRA_MEDIA_ALBUM, "album")
                putString(MediaStore.EXTRA_MEDIA_TITLE, "title")
            }
        val metadata =
            MediaItem.RequestMetadata
                .Builder()
                .setSearchQuery("query")
                .setExtras(extras)
                .build()

        assertEquals(VoiceRequest("query", "focus", "artist", "album", "title"), VoiceRequest.from(metadata))
    }

    @Test
    fun `missing requestMetadata reads as an empty query`() {
        assertEquals(VoiceRequest("", null, null, null, null), VoiceRequest.from(MediaItem.RequestMetadata.EMPTY))
    }
}
