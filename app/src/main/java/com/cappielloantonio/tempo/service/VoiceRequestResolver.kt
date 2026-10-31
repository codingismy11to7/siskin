package com.cappielloantonio.tempo.service

import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.PlexTransportException
import com.cappielloantonio.tempo.repository.VoiceSearchHits
import com.cappielloantonio.tempo.repository.VoiceTrackHit
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.CredentialGate
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** What a voice request asked for. An empty [query] is "play music". */
data class VoiceRequest(
    val query: String,
    val focus: String?,
    val artist: String?,
    val album: String?,
    val title: String?,
) {
    companion object {
        fun from(metadata: MediaItem.RequestMetadata): VoiceRequest {
            val extras = metadata.extras
            return VoiceRequest(
                query = metadata.searchQuery.orEmpty(),
                focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
                artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            )
        }
    }
}

/** The queue already in the player, read on the application thread. */
data class ResumePoint(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
)

enum class VoiceFailure { SignIn, NotFound, Unreachable }

sealed interface VoiceOutcome {
    data class Play(
        val items: List<MediaItem>,
        val index: Int,
        val described: String,
    ) : VoiceOutcome

    data class Resume(
        val point: ResumePoint,
    ) : VoiceOutcome

    data class Failed(
        val failure: VoiceFailure,
    ) : VoiceOutcome
}

/**
 * Turns a voice request into a queue or a typed failure. See
 * docs/decisions/2026-10-31-assistant-voice-requests-design.md.
 */
@UnstableApi
class VoiceRequestResolver(
    private val browse: PlexBrowseRepository,
    private val isSignedIn: () -> Boolean = CredentialGate::isSignedIn,
) {
    private sealed interface Match {
        data class Artist(
            val key: String,
            val name: String,
        ) : Match

        data class Album(
            val key: String,
            val name: String,
        ) : Match

        data class Track(
            val hit: VoiceTrackHit,
        ) : Match
    }

    fun resolve(
        request: VoiceRequest,
        resume: ResumePoint?,
    ): ListenableFuture<VoiceOutcome> {
        if (!isSignedIn()) return Futures.immediateFuture(VoiceOutcome.Failed(VoiceFailure.SignIn))

        val outcome =
            when {
                request.query.isNotBlank() -> search(request)
                resume != null -> Futures.immediateFuture(VoiceOutcome.Resume(resume))
                else -> shuffled(browse.getLibraryShuffle(), "library shuffle")
            }
        return Futures.catching(outcome, Throwable::class.java, ::failedBy, MoreExecutors.directExecutor())
    }

    private fun search(request: VoiceRequest): ListenableFuture<VoiceOutcome> {
        val term = focusedTerm(request) ?: request.query
        return Futures.transformAsync(
            browse.voiceSearch(term),
            { hits ->
                when (val match = pick(hits, request.focus, term)) {
                    null -> Futures.immediateFuture(VoiceOutcome.Failed(VoiceFailure.NotFound))
                    is Match.Artist -> shuffled(browse.getArtistTracks(match.key), "artist ${match.name}")
                    is Match.Album -> inOrder(browse.getAlbumTracks(match.key), "album ${match.name}")
                    is Match.Track -> aroundTrack(match.hit)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun focusedTerm(request: VoiceRequest): String? =
        when (request.focus) {
            ARTIST_FOCUS -> request.artist
            ALBUM_FOCUS -> request.album
            TRACK_FOCUS -> request.title
            else -> null
        }?.takeIf { it.isNotBlank() }

    private fun pick(
        hits: VoiceSearchHits,
        focus: String?,
        term: String,
    ): Match? {
        fun named(item: MediaItem) =
            item.mediaMetadata.title
                ?.toString()
                .equals(term, ignoreCase = true)

        val artist = { item: MediaItem ->
            Match.Artist(item.mediaId.removePrefix(Constants.ARTIST_ID), item.mediaMetadata.title.toString())
        }
        val album = { item: MediaItem ->
            Match.Album(item.mediaId.removePrefix(Constants.ALBUM_ID), item.mediaMetadata.title.toString())
        }

        val focused =
            when (focus) {
                ARTIST_FOCUS -> (hits.artists.firstOrNull(::named) ?: hits.artists.firstOrNull())?.let(artist)
                ALBUM_FOCUS -> (hits.albums.firstOrNull(::named) ?: hits.albums.firstOrNull())?.let(album)
                TRACK_FOCUS -> (hits.tracks.firstOrNull { named(it.item) } ?: hits.tracks.firstOrNull())?.let(Match::Track)
                else -> null
            }

        return focused
            ?: hits.tracks.firstOrNull { named(it.item) }?.let(Match::Track)
            ?: hits.albums.firstOrNull(::named)?.let(album)
            ?: hits.artists.firstOrNull(::named)?.let(artist)
            ?: hits.artists.firstOrNull()?.let(artist)
            ?: hits.albums.firstOrNull()?.let(album)
            ?: hits.tracks.firstOrNull()?.let(Match::Track)
    }

    private fun shuffled(
        tracks: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>,
        described: String,
    ): ListenableFuture<VoiceOutcome> = played(tracks, described) { it.shuffled() }

    private fun inOrder(
        tracks: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>,
        described: String,
    ): ListenableFuture<VoiceOutcome> = played(tracks, described) { it }

    private fun played(
        tracks: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>,
        described: String,
        order: (List<MediaItem>) -> List<MediaItem>,
    ): ListenableFuture<VoiceOutcome> =
        Futures.transform<LibraryResult<ImmutableList<MediaItem>>, VoiceOutcome>(
            tracks,
            { result ->
                val items = result.value.orEmpty()
                when {
                    result.resultCode == SessionError.ERROR_PERMISSION_DENIED -> VoiceOutcome.Failed(VoiceFailure.SignIn)
                    result.resultCode != LibraryResult.RESULT_SUCCESS -> VoiceOutcome.Failed(VoiceFailure.Unreachable)
                    items.isEmpty() -> VoiceOutcome.Failed(VoiceFailure.NotFound)
                    else -> VoiceOutcome.Play(order(items), 0, described)
                }
            },
            MoreExecutors.directExecutor(),
        )

    private fun aroundTrack(hit: VoiceTrackHit): ListenableFuture<VoiceOutcome> {
        val alone = VoiceOutcome.Play(listOf(hit.item), 0, "track ${hit.item.mediaMetadata.title}")
        val albumKey = hit.albumKey ?: return Futures.immediateFuture(alone)

        val around: ListenableFuture<VoiceOutcome> =
            Futures.transform<LibraryResult<ImmutableList<MediaItem>>, VoiceOutcome>(
                browse.getAlbumTracks(albumKey),
                { result ->
                    val items = result.value.orEmpty()
                    val index = items.indexOfFirst { it.mediaId == hit.item.mediaId }
                    if (index >= 0) alone.copy(items = items, index = index) else alone
                },
                MoreExecutors.directExecutor(),
            )
        return Futures.catching<VoiceOutcome, Throwable>(
            around,
            Throwable::class.java,
            { alone },
            MoreExecutors.directExecutor(),
        )
    }

    private fun failedBy(failure: Throwable): VoiceOutcome {
        val transport = (failure as? PlexTransportException)?.failure
        val rejected = transport is PlexTransportFailure.Http && (transport.code == HTTP_UNAUTHORIZED || transport.code == HTTP_FORBIDDEN)
        return VoiceOutcome.Failed(if (rejected) VoiceFailure.SignIn else VoiceFailure.Unreachable)
    }

    private companion object {
        const val ARTIST_FOCUS = MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
        const val ALBUM_FOCUS = MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
        const val TRACK_FOCUS = MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
