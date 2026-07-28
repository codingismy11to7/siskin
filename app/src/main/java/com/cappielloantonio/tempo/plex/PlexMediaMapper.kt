package com.cappielloantonio.tempo.plex

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.ResourceUris
import com.google.common.collect.ImmutableList

/**
 * Plex Metadata -> media3 MediaItem, and the single definition of what the
 * extras bundle carries.
 *
 * The bundle is this codebase's domain model: MediaManager, BaseMediaService,
 * BaseSessionCallback and MediaLibraryServiceCallback all read it, and nothing
 * reads a domain object. That is why there is no intermediate track type -- see
 * docs/decisions/2026-07-28-plex-browse-playback-design.md.
 *
 * The pure functions here (partKey, artworkThumb, mergeSearchResults) carry the
 * logic that can go wrong and are unit-tested with plain JUnit. The MediaItem
 * builders touch Uri, Bundle and MediaItem.Builder, all of which return
 * defaults under unitTests.returnDefaultValues -- a plain JUnit assertion on
 * their output would pass against a broken implementation just as readily as
 * a correct one -- so they are verified under Robolectric instead, in
 * PlexMediaMapperAssemblyTest, where the real framework classes are loaded.
 */
@OptIn(UnstableApi::class)
object PlexMediaMapper {

    const val EXTRA_ID = "id"
    const val EXTRA_ARTIST_ID = "artistId"
    const val EXTRA_TYPE = "type"
    const val EXTRA_URI = "uri"
    const val EXTRA_PARENT_ID = "parent_id"
    const val EXTRA_PART_KEY = "partKey"
    const val EXTRA_THUMB = "thumb"

    /**
     * The track's album. Its only current readers are Subsonic-era code
     * being removed in a later task (Chronology.kt and MappingUtil.java both
     * read extras.getString("albumId")); no Plex-side code reads it yet.
     * The Room entities persist it per the spec regardless, and a field that
     * cannot round-trip through the bundle would make buildTrackMediaItem's
     * parentRatingKey parameter dead.
     */
    const val EXTRA_PARENT_RATING_KEY = "albumId"

    /**
     * Plex rates 0-10; this app writes this value for a hearted track, so
     * reading it back is what keeps the heart state in sync with every
     * other Plex client.
     */
    const val HEARTED_RATING = 10.0

    // ── pure helpers ──────────────────────────────────────────

    /**
     * The server-relative file path MediaUrlBuilder.streamUrl turns into a
     * playable URL. Plex can return a part with no key beside a usable one, so
     * this takes the first that actually has one rather than media[0].part[0].
     */
    @JvmStatic
    fun partKey(metadata: Metadata): String? =
        metadata.media
            ?.asSequence()
            ?.flatMap { it.part.orEmpty().asSequence() }
            ?.firstOrNull { !it.key.isNullOrBlank() }
            ?.key

    /**
     * Individual tracks often carry no thumb, so fall back to the album's and
     * then the artist's. Without this a whole album renders as placeholders.
     */
    @JvmStatic
    fun artworkThumb(metadata: Metadata): String? = when {
        !metadata.thumb.isNullOrBlank() -> metadata.thumb
        !metadata.parentThumb.isNullOrBlank() -> metadata.parentThumb
        !metadata.grandparentThumb.isNullOrBlank() -> metadata.grandparentThumb
        else -> null
    }

    /**
     * Without this a browsed track always showed an empty heart no matter
     * what it was rated.
     */
    @JvmStatic
    fun isHearted(metadata: Metadata): Boolean = (metadata.userRating ?: 0.0) >= HEARTED_RATING

    /**
     * Plex rejects a multi-type search with HTTP 400, so the browse layer issues
     * three and merges here. Order matches what the Subsonic implementation
     * presented: artists, then albums, then tracks.
     */
    @JvmStatic
    fun mergeSearchResults(
        artists: List<Metadata>,
        albums: List<Metadata>,
        tracks: List<Metadata>
    ): List<Metadata> =
        (artists + albums + tracks).filter { !it.ratingKey.isNullOrBlank() }

    // ── MediaItem builders ────────────────────────────────────

    /**
     * The one place a playable MediaItem is assembled. Called both by
     * [trackToMediaItem] for fresh Plex responses and by the Room entities when
     * rebuilding a persisted queue, so that the bundle contract has exactly one
     * definition.
     *
     * The stream URL is built here from [partKey] rather than persisted,
     * because it carries X-Plex-Token: a stored URL breaks whenever the token
     * rotates.
     */
    @JvmStatic
    fun buildTrackMediaItem(
        ratingKey: String,
        title: String?,
        albumTitle: String?,
        artist: String?,
        thumb: String?,
        partKey: String?,
        durationMs: Long?,
        trackIndex: Int?,
        year: Int?,
        parentRatingKey: String?,
        grandparentRatingKey: String?,
        isHearted: Boolean,
        parentId: String?,
        serverUri: String?,
        token: String?
    ): MediaItem {
        val streamUrl = MediaUrlBuilder.streamUrl(serverUri, partKey, token)
        val uri = if (streamUrl != null) Uri.parse(streamUrl) else Uri.EMPTY
        val artworkUri = thumb?.takeIf { it.isNotBlank() }
            ?.let { AlbumArtContentProvider.contentUri(it) }

        val bundle = Bundle().apply {
            putString(EXTRA_ID, ratingKey)
            putString(EXTRA_ARTIST_ID, grandparentRatingKey)
            putString(EXTRA_PARENT_RATING_KEY, parentRatingKey)
            putString(EXTRA_TYPE, Constants.MEDIA_TYPE_MUSIC)
            putString(EXTRA_URI, uri.toString())
            putString(EXTRA_PART_KEY, partKey)
            putString(EXTRA_THUMB, thumb)
            if (parentId != null) putString(EXTRA_PARENT_ID, parentId)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setAlbumTitle(albumTitle)
            .setArtist(artist)
            .setTrackNumber(trackIndex ?: 0)
            .setReleaseYear(year ?: 0)
            .setDurationMs(durationMs)
            .setArtworkUri(artworkUri)
            .setUserRating(HeartRating(isHearted))
            .setSupportedCommands(
                ImmutableList.of(
                    Constants.CUSTOM_COMMAND_TOGGLE_HEART_ON,
                    Constants.CUSTOM_COMMAND_TOGGLE_HEART_OFF
                )
            )
            .setExtras(bundle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setMediaId(ratingKey)
            .setMediaMetadata(metadata)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri)
                    .setExtras(bundle)
                    .build()
            )
            .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
            .setUri(uri)
            .build()
    }

    @JvmStatic
    fun trackToMediaItem(
        metadata: Metadata,
        parentId: String?,
        serverUri: String?,
        token: String?
    ): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return buildTrackMediaItem(
            ratingKey = ratingKey,
            title = metadata.title,
            albumTitle = metadata.parentTitle,
            artist = metadata.grandparentTitle,
            thumb = artworkThumb(metadata),
            partKey = partKey(metadata),
            durationMs = metadata.duration,
            trackIndex = metadata.index,
            year = metadata.year,
            parentRatingKey = metadata.parentRatingKey,
            grandparentRatingKey = metadata.grandparentRatingKey,
            isHearted = isHearted(metadata),
            parentId = parentId,
            serverUri = serverUri,
            token = token
        )
    }

    /**
     * Everything the Room entities persist about a track, read out of a
     * MediaItem in one place so SessionMediaItem and Queue do not each repeat
     * the same field-by-field read.
     *
     * Deliberately not a pivot type: nothing outside those two entities
     * constructs or consumes one. Tracks flow through the app as MediaItem.
     */
    data class TrackFields(
        val ratingKey: String,
        val title: String?,
        val albumTitle: String?,
        val artist: String?,
        val thumb: String?,
        val partKey: String?,
        val durationMs: Long?,
        val trackIndex: Int?,
        val year: Int?,
        val parentRatingKey: String?,
        val grandparentRatingKey: String?,
        val isHearted: Boolean
    )

    @JvmStatic
    fun readTrackFields(item: MediaItem?): TrackFields? {
        if (item == null) return null
        val extras = item.mediaMetadata.extras
        val ratingKey = extras?.getString(EXTRA_ID) ?: item.mediaId
        if (ratingKey.isNullOrBlank()) return null

        return TrackFields(
            ratingKey = ratingKey,
            title = item.mediaMetadata.title?.toString(),
            albumTitle = item.mediaMetadata.albumTitle?.toString(),
            artist = item.mediaMetadata.artist?.toString(),
            thumb = extras?.getString(EXTRA_THUMB),
            partKey = extras?.getString(EXTRA_PART_KEY),
            durationMs = item.mediaMetadata.durationMs,
            trackIndex = item.mediaMetadata.trackNumber,
            year = item.mediaMetadata.releaseYear,
            parentRatingKey = extras?.getString(EXTRA_PARENT_RATING_KEY),
            grandparentRatingKey = extras?.getString(EXTRA_ARTIST_ID),
            isHearted = (item.mediaMetadata.userRating as? HeartRating)?.isHeart == true
        )
    }

    @JvmStatic
    fun albumToMediaItem(
        metadata: Metadata,
        idPrefix: String,
        serverUri: String?,
        token: String?
    ): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = metadata.parentTitle,
            thumb = artworkThumb(metadata),
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            fallbackIcon = R.drawable.ic_aa_albums,
            gridView = true
        )
    }

    @JvmStatic
    fun artistToMediaItem(
        metadata: Metadata,
        idPrefix: String,
        serverUri: String?,
        token: String?
    ): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = null,
            thumb = artworkThumb(metadata),
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            fallbackIcon = R.drawable.ic_aa_artists,
            gridView = true
        )
    }

    @JvmStatic
    fun playlistToMediaItem(metadata: Metadata, idPrefix: String): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = null,
            thumb = artworkThumb(metadata),
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            fallbackIcon = R.drawable.ic_aa_playlist,
            gridView = false
        )
    }

    /**
     * Deliberately does not call setUri: media3's setUri(String) parses even
     * "" to a non-null Uri, which would give this item a non-null
     * localConfiguration. MediaLibraryServiceCallback.resolveQueueForItem
     * treats item.localConfiguration?.uri?.let { item } as "already
     * resolved, use as-is" -- a browsable item carrying that would bypass
     * queue resolution entirely. Matches MediaBrowserTree.buildMediaItem,
     * whose sourceUri defaults to null for the same reason.
     */
    private fun browsableItem(
        mediaId: String,
        title: String?,
        subtitle: String?,
        thumb: String?,
        mediaType: Int,
        fallbackIcon: Int,
        gridView: Boolean
    ): MediaItem {
        val artworkUri = thumb?.takeIf { it.isNotBlank() }
            ?.let { AlbumArtContentProvider.contentUri(it) }
            ?: ResourceUris.forResource(fallbackIcon)

        val style = if (gridView) {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        } else {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        }

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setAlbumTitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .setArtworkUri(artworkUri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}
