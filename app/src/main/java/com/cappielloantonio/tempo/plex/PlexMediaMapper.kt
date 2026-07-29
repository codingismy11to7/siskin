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
import com.cappielloantonio.tempo.util.BrowseContentStyle
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.ConstantsAA
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
 * The pure functions here (partKey, artworkThumb, isHearted) carry the
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
            grandparentRatingKey = extras?.getString(EXTRA_ARTIST_ID),
            isHearted = (item.mediaMetadata.userRating as? HeartRating)?.isHeart == true
        )
    }

    /**
     * No credentials parameter, unlike [trackToMediaItem]: a browsable item has
     * no stream URL to build, and its artwork is a `content://` URI that
     * AlbumArtContentProvider resolves against the current credentials when the
     * car opens it.
     */
    @JvmStatic
    fun albumToMediaItem(metadata: Metadata, idPrefix: String): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = metadata.parentTitle,
            thumb = artworkThumb(metadata),
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            fallbackIcon = R.drawable.ic_aa_albums,
            browsableChildrenAsGrid = true
        )
    }

    /** Credential-free for the same reason as [albumToMediaItem]. */
    @JvmStatic
    fun artistToMediaItem(metadata: Metadata, idPrefix: String): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = null,
            thumb = artworkThumb(metadata),
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            fallbackIcon = R.drawable.ic_aa_artists,
            browsableChildrenAsGrid = true
        )
    }

    /**
     * A "shuffle this <thing>" row, at the head of the list of what it shuffles:
     * an artist's albums, or a playlist's tracks.
     *
     * [mediaId] is one of ConstantsAA's shuffle prefixes plus the subject's
     * ratingKey, and carrying it in the id is the whole mechanism --
     * MediaLibrarySessionCallback dispatches on the prefix to decide which
     * tracks to fetch.
     *
     * Playable but streamless, and deliberately so: there is no single track to
     * point at. Like [browsableItem] it never calls setUri -- a non-null
     * localConfiguration would make resolveQueueForItem treat the row as already
     * resolved and "play" a track with no stream.
     *
     * The icon is media3's own Material shuffle glyph rather than a vendored
     * copy. It is not declared in media3's public.xml, so a rename on upgrade
     * would break this reference -- as a compile error, which is why relying on
     * it is acceptable.
     */
    @JvmStatic
    fun shuffleRowToMediaItem(mediaId: String, title: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(ResourceUris.forResource(R.drawable.media3_icon_shuffle_on))
                    .build()
            )
            .build()

    @JvmStatic
    fun playlistToMediaItem(metadata: Metadata, idPrefix: String): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        // Plex never sets thumb on a playlist -- it generates composite, a
        // mosaic of the playlist's own contents, in its place. artworkThumb
        // is the fallback rather than the primary source here because it
        // reads fields (thumb/parentThumb/grandparentThumb) that playlists
        // don't populate; kept as a fallback in case a future Plex response
        // ever does. A playlist with neither (e.g. one Plex has no art for
        // at all) still falls through to the placeholder icon below.
        val thumb = metadata.composite?.takeIf { it.isNotBlank() } ?: artworkThumb(metadata)
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = null,
            thumb = thumb,
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            fallbackIcon = R.drawable.ic_aa_playlist,
            browsableChildrenAsGrid = false
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
        browsableChildrenAsGrid: Boolean
    ): MediaItem {
        val artworkUri = thumb?.takeIf { it.isNotBlank() }
            ?.let { AlbumArtContentProvider.contentUri(it) }
            ?: ResourceUris.forResource(fallbackIcon)

        val extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                BrowseContentStyle.browsableChildStyle(browsableChildrenAsGrid)
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                BrowseContentStyle.PLAYABLE_CHILD_STYLE
            )
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
