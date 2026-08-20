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
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Hub
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider
import com.cappielloantonio.tempo.util.BrowseContentStyle
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DecadeKey
import com.cappielloantonio.tempo.util.HubCoverPool
import com.cappielloantonio.tempo.util.HubKey
import com.cappielloantonio.tempo.util.ResourceUris

/**
 * Plex Metadata -> media3 MediaItem, and the single definition of what the
 * extras bundle carries.
 *
 * The bundle is this codebase's domain model: MediaManager, BaseMediaService,
 * BaseSessionCallback and MediaLibrarySessionCallback all read it, and nothing
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
    fun artworkThumb(metadata: Metadata): String? =
        when {
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
     * Who to credit for a track.
     *
     * `grandparentTitle` is the *album* artist, so on a compilation every track
     * read "Various Artists" rather than whoever actually performed it. Plex puts
     * the track's own artist in `originalTitle`, and populates it only when the
     * two differ -- so the fallback is not a nicety, it is the normal case.
     *
     * This changes the displayed credit only. `originalTitle` is free text with no
     * rating key, so the artist a track can *navigate* to stays
     * `grandparentRatingKey` -- which is also what continuous play follows.
     */
    @JvmStatic
    fun trackArtist(metadata: Metadata): String? = metadata.originalTitle?.takeIf { it.isNotBlank() } ?: metadata.grandparentTitle

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
        token: String?,
    ): MediaItem {
        val streamUrl = MediaUrlBuilder.streamUrl(serverUri, partKey, token)
        val uri = if (streamUrl != null) Uri.parse(streamUrl) else Uri.EMPTY
        val artworkUri =
            thumb
                ?.takeIf { it.isNotBlank() }
                ?.let { AlbumArtContentProvider.contentUri(it) }

        val bundle =
            Bundle().apply {
                putString(EXTRA_ID, ratingKey)
                putString(EXTRA_ARTIST_ID, grandparentRatingKey)
                putString(EXTRA_TYPE, Constants.MEDIA_TYPE_MUSIC)
                putString(EXTRA_URI, uri.toString())
                putString(EXTRA_PART_KEY, partKey)
                putString(EXTRA_THUMB, thumb)
                if (parentId != null) putString(EXTRA_PARENT_ID, parentId)
            }

        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setAlbumTitle(albumTitle)
                .setArtist(artist)
                .setTrackNumber(trackIndex ?: 0)
                .setReleaseYear(year ?: 0)
                .setDurationMs(durationMs)
                .setArtworkUri(artworkUri)
                // Publishing the rating is what asks com.android.car.media for its own
                // rating control left of transport -- it reads the type off this Rating
                // subtype, so a HeartRating gets the on/off star and a StarRating gets
                // nothing at all. See the 2026-08-02 car star rating design.
                .setUserRating(HeartRating(isHearted))
                .setExtras(bundle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()

        return MediaItem
            .Builder()
            .setMediaId(ratingKey)
            .setMediaMetadata(metadata)
            .setRequestMetadata(
                MediaItem.RequestMetadata
                    .Builder()
                    .setMediaUri(uri)
                    .setExtras(bundle)
                    .build(),
            ).setMimeType(MimeTypes.BASE_TYPE_AUDIO)
            .setUri(uri)
            .build()
    }

    @JvmStatic
    fun trackToMediaItem(
        metadata: Metadata,
        parentId: String?,
        serverUri: String?,
        token: String?,
    ): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return buildTrackMediaItem(
            ratingKey = ratingKey,
            title = metadata.title,
            albumTitle = metadata.parentTitle,
            artist = trackArtist(metadata),
            thumb = artworkThumb(metadata),
            partKey = partKey(metadata),
            durationMs = metadata.duration,
            trackIndex = metadata.index,
            year = metadata.year,
            grandparentRatingKey = metadata.grandparentRatingKey,
            isHearted = isHearted(metadata),
            parentId = parentId,
            serverUri = serverUri,
            token = token,
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
        val isHearted: Boolean,
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
            isHearted = (item.mediaMetadata.userRating as? HeartRating)?.isHeart == true,
        )
    }

    /**
     * No credentials parameter, unlike [trackToMediaItem]: a browsable item has
     * no stream URL to build, and its artwork is a `content://` URI that
     * AlbumArtContentProvider resolves against the current credentials when the
     * car opens it.
     */
    @JvmStatic
    fun albumToMediaItem(
        metadata: Metadata,
        idPrefix: String,
    ): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = metadata.parentTitle,
            thumb = artworkThumb(metadata),
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            fallbackIcon = R.drawable.ic_browse_albums,
            browsableChildrenAsGrid = true,
        )
    }

    /** Credential-free for the same reason as [albumToMediaItem]. */
    @JvmStatic
    fun artistToMediaItem(
        metadata: Metadata,
        idPrefix: String,
    ): MediaItem? {
        val ratingKey = metadata.ratingKey?.takeIf { it.isNotBlank() } ?: return null
        return browsableItem(
            mediaId = idPrefix + ratingKey,
            title = metadata.title,
            subtitle = null,
            thumb = artworkThumb(metadata),
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            fallbackIcon = R.drawable.ic_browse_artists,
            browsableChildrenAsGrid = true,
        )
    }

    /**
     * A "shuffle this <thing>" row, at the head of the list of what it shuffles:
     * an artist's albums, a playlist's tracks, or a decade's tracks.
     *
     * [mediaId] is one of Constants's shuffle prefixes plus the subject's
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
    fun mixRowToMediaItem(
        mediaId: String,
        title: String?,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(ResourceUris.forResource(R.drawable.media3_icon_shuffle_on))
                    .build(),
            ).build()

    /**
     * A decade row, from the `Directory` entries
     * [com.cappielloantonio.tempo.plex.api.library.LibraryService.getDecades]
     * returns.
     *
     * Deliberately not built through [browsableItem], which falls back to an
     * icon when there is no thumb. A decade wants no artwork at all rather than
     * a shared glyph when its composite cannot be built: eight rows wearing the
     * same icon carry less than the car's own per-row placeholder does.
     *
     * The artwork is ours rather than Plex's -- there is no composite for a
     * filter value, see the 2026-08-09 decade composite artwork design.
     * [bucket] is the hour window it belongs to and [scope] is the library it
     * was drawn from; both ride in the URI for one reason, which is that the
     * car's image cache keys on the URI and will otherwise pin a tile for the
     * life of the process. An hour's roll has to change the URI, and so does a
     * switch to another server. Both are passed in rather than read from a
     * clock or a session here, so this stays a pure function.
     *
     * Filtered on key and title rather than on `type`: a decade Directory has no
     * `type` field, unlike the section Directory `LibraryClient.musicSections`
     * narrows.
     *
     * [Directory.title] arrives already formatted for display ("1980s") and
     * [Directory.key] is the first year ("1980"). The key rides in the media id
     * because that is all the car sends back on a tap -- and so does [scope],
     * via [DecadeKey], because a decade is the one row type whose key means the
     * same thing on every server. See [DecadeKey] for what the car does with
     * two servers' rows that share an id.
     */
    @JvmStatic
    fun decadeToMediaItem(
        directory: Directory,
        idPrefix: String,
        scope: String,
        bucket: Long,
    ): MediaItem? {
        val key = directory.key?.takeIf { it.isNotBlank() } ?: return null
        val title = directory.title?.takeIf { it.isNotBlank() } ?: return null

        // Only the playable-child key is set. A decade's children are the
        // shuffle row plus up to 500 tracks -- all playable, and none
        // browsable -- so EXTRAS_KEY_CONTENT_STYLE_BROWSABLE would be a hint
        // about a grid that never renders here. See BrowseContentStyle's KDoc:
        // these keys describe an item's children, not the item itself.
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    BrowseContentStyle.PLAYABLE_CHILD_STYLE,
                )
            }

        return MediaItem
            .Builder()
            .setMediaId(idPrefix + DecadeKey.of(scope, key))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(AlbumArtContentProvider.decadeContentUri(scope, key, bucket))
                    .setExtras(extras)
                    .build(),
            ).build()
    }

    /**
     * A Discover row, from one [Hub].
     *
     * Its children are albums and artists, which covers *do* identify, so they
     * grid like everywhere else. **Whether Discover's own rows list or grid is
     * decided on the node in `MediaBrowserTree.buildTree`, and the answer is
     * list, permanently.** A hub row is a proposition -- "Haven't played in 5
     * months" -- and nothing about four covers tells it from "Most Played in
     * April", so the covers below decorate the row rather than promoting it: a
     * list gives the full server-supplied title plus a second line where a grid
     * gives a caption that truncates, in five locales whose lengths this app
     * neither controls nor can test in four of them.
     *
     * [artworkUri] is a composite tiled from the hub's own items, which cost
     * nothing to obtain -- they arrived with the listing that decided this row
     * exists. A hub whose items carry no thumb gets none, and the car draws its
     * own placeholder, which is what every Discover row did before this.
     *
     * [Hub.title] arrives localised, because the client sends X-Plex-Language.
     */
    @JvmStatic
    fun hubToMediaItem(
        hub: Hub,
        idPrefix: String,
        scope: String,
        bucket: Long,
    ): MediaItem? {
        val key = hub.key?.takeIf { it.isNotBlank() } ?: return null
        val title = hub.title?.takeIf { it.isNotBlank() } ?: return null

        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    BrowseContentStyle.browsableChildStyle(true),
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    BrowseContentStyle.PLAYABLE_CHILD_STYLE,
                )
            }

        val pool =
            hub.metadata
                .orEmpty()
                .mapNotNull { artworkThumb(it) }
                .distinct()
                .take(HubCoverPool.MAX)

        val metadataBuilder =
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
        if (pool.isNotEmpty()) {
            metadataBuilder.setArtworkUri(AlbumArtContentProvider.hubContentUri(scope, bucket, pool))
        }

        return MediaItem
            .Builder()
            .setMediaId(idPrefix + HubKey.of(scope, key))
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * A row standing for a *group* of items rather than an item: one window of a
     * list too long to send in full ("Beck  -  Cake"), or one first-character
     * bucket ("A").
     *
     * A group is a range or a filter value rather than a library item, so there
     * is nothing on the server to draw and [icon] is always a local drawable. It
     * is set rather than left null -- which is what [decadeToMediaItem] does --
     * because the car fills an absent artworkUri with a music note on a per-row
     * colour, and a tab holding 25 to 56 of them turns that into a column of
     * unrelated colours competing for a driver's attention. One repeated glyph
     * says just as little and says it quietly.
     *
     * [subtitle] rides the browse list's second line, the same one an album uses
     * for its artist. Letter rows spend it on the bucket's count; window rows
     * pass none, because every window holds WINDOW_SIZE items but the last.
     *
     * Its children *are* artists or albums, so unlike [decadeToMediaItem] this
     * sets the browsable content style -- those children render as a grid.
     *
     * Otherwise this largely re-implements [browsableItem]; the one deliberate
     * difference from it is that EXTRAS_KEY_CONTENT_STYLE_PLAYABLE is not set
     * here, because a group row has no playable children of its own.
     */
    @JvmStatic
    fun groupRowToMediaItem(
        mediaId: String,
        title: String,
        icon: Int,
        subtitle: String? = null,
    ): MediaItem {
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    BrowseContentStyle.browsableChildStyle(true),
                )
            }

        return MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(ResourceUris.forResource(icon))
                    .setExtras(extras)
                    .build(),
            ).build()
    }

    @JvmStatic
    fun playlistToMediaItem(
        metadata: Metadata,
        idPrefix: String,
    ): MediaItem? {
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
            fallbackIcon = R.drawable.ic_browse_playlist,
            browsableChildrenAsGrid = false,
        )
    }

    /**
     * Deliberately does not call setUri: media3's setUri(String) parses even
     * "" to a non-null Uri, which would give this item a non-null
     * localConfiguration. MediaLibrarySessionCallback.resolveQueueForItem
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
        browsableChildrenAsGrid: Boolean,
    ): MediaItem {
        val artworkUri =
            thumb
                ?.takeIf { it.isNotBlank() }
                ?.let { AlbumArtContentProvider.contentUri(it) }
                ?: ResourceUris.forResource(fallbackIcon)

        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    BrowseContentStyle.browsableChildStyle(browsableChildrenAsGrid),
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    BrowseContentStyle.PLAYABLE_CHILD_STYLE,
                )
            }

        return MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setAlbumTitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .setArtworkUri(artworkUri)
                    .setExtras(extras)
                    .build(),
            ).build()
    }
}
