package com.cappielloantonio.tempo.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.repository.LibraryPickerRepository
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.util.ConstantsAA
import com.cappielloantonio.tempo.util.ResourceUris
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

@UnstableApi
object MediaBrowserTree {
    private lateinit var appContext: Context
    private lateinit var browseRepository: PlexBrowseRepository
    private val pickerRepository = LibraryPickerRepository()

    private var treeNodes: MutableMap<String, MediaItemNode> = mutableMapOf()

    private var isInitialized = false

    private fun iconUri(resId: Int): Uri = ResourceUris.forResource(resId)

    private class MediaItemNode(val item: MediaItem) {
        private val children: MutableList<MediaItem> = ArrayList()

        fun addChild(childID: String) {
            this.children.add(treeNodes[childID]!!.item)
        }

        fun getChildren(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val listenableFuture = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            listenableFuture.set(LibraryResult.ofItemList(children, null))
            return listenableFuture
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaItem(
        gridView: Boolean,
        title: String,
        mediaId: String,
        isPlayable: Boolean,
        isBrowsable: Boolean,
        mediaType: @MediaMetadata.MediaType Int,
        subtitleConfigurations: List<SubtitleConfiguration> = mutableListOf(),
        album: String? = null,
        artist: String? = null,
        genre: String? = null,
        sourceUri: Uri? = null,
        imageUri: Uri? = null
    ): MediaItem {
        val style = if (gridView) {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        } else {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }

        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        }

        val metadata = MediaMetadata.Builder()
            .setAlbumTitle(album)
            .setTitle(title)
            .setArtist(artist)
            .setGenre(genre)
            .setIsBrowsable(isBrowsable)
            .setIsPlayable(isPlayable)
            .setArtworkUri(imageUri)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setSubtitleConfigurations(subtitleConfigurations)
            .setMediaMetadata(metadata)
            .setUri(sourceUri)
            .build()
    }

    fun initialize(context: Context, browseRepository: PlexBrowseRepository) {
        this.browseRepository = browseRepository
        appContext = context.applicationContext
        if (isInitialized) return
        isInitialized = true
    }

    /**
     * The browse root is fixed at Playlists | Artists | Albums | More.
     *
     * Four is the maximum the car allows: it enforces a root-children limit of
     * four and silently drops a fifth, so do not add another top-level tab here
     * -- nest it under More instead.
     *
     * Grid-versus-list styling is frozen at what a default install showed before
     * the settings screen was removed: albums and artists as grids
     * (AA_ALBUM_VIEW defaulted true), playlists as a list (AA_PLAYLIST_VIEW
     * defaulted false).
     */
    fun buildTree() {
        treeNodes.clear()

        treeNodes[ConstantsAA.ROOT_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = true,
                    title = "Root Folder",
                    mediaId = ConstantsAA.ROOT_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.PLAYLIST_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_playlists),
                    mediaId = ConstantsAA.PLAYLIST_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_playlist),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

        treeNodes[ConstantsAA.ARTISTS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = true,
                    title = appContext.getString(R.string.aa_artists),
                    mediaId = ConstantsAA.ARTISTS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_artists),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                )
            )

        treeNodes[ConstantsAA.ALBUMS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = true,
                    title = appContext.getString(R.string.aa_albums),
                    mediaId = ConstantsAA.ALBUMS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_albums),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )

        treeNodes[ConstantsAA.MORE_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_more),
                    mediaId = ConstantsAA.MORE_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_playlist),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.SELECT_LIBRARY_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_select_library),
                    mediaId = ConstantsAA.SELECT_LIBRARY_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_playlist),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.MORE_ID]!!.addChild(ConstantsAA.SELECT_LIBRARY_ID)

        val root = treeNodes[ConstantsAA.ROOT_ID]!!
        root.addChild(ConstantsAA.PLAYLIST_ID)
        root.addChild(ConstantsAA.ARTISTS_ID)
        root.addChild(ConstantsAA.ALBUMS_ID)
        root.addChild(ConstantsAA.MORE_ID)
    }

    fun getRootItem(): MediaItem {
        return treeNodes[ConstantsAA.ROOT_ID]!!.item
    }

    /**
     * Looks up a single node's [MediaItem] by id, for `onGetItem`.
     *
     * The default `MediaLibrarySession.Callback.onSubscribe` implementation calls
     * `onGetItem` to validate the target before letting a subscription stick,
     * so this must succeed for any browsable id the tree exposes -- including
     * on a cold session where nothing has called [buildTree] yet.
     */
    fun getItem(mediaId: String): MediaItem? {
        if (treeNodes.isEmpty()) buildTree()
        treeNodes[mediaId]?.let { return it.item }

        // The picker's rows are built ad hoc by LibraryPickerRepository and are
        // never registered in treeNodes, so the lookup above can only miss them
        // -- and a miss here is not cosmetic. onSubscribe (see
        // MediaLibrarySessionCallback.onGetItem) drops the subscription unless
        // this returns an item *and* that item is browsable, and
        // BrowseTreeInvalidator.invalidateNode dispatches only to live
        // subscriptions. Without this placeholder every invalidation of a server
        // node is a guaranteed no-op and the tick only ever moves on a fresh
        // re-entry -- which is precisely the interaction the More tab exists for.
        //
        // The placeholder is never drawn: the car renders these lists from
        // onGetChildren. It looks pointless for that reason. It is not.
        if (mediaId.startsWith(ConstantsAA.PICK_SERVER_ID) ||
            mediaId.startsWith(ConstantsAA.PICK_LIBRARY_ID)
        ) {
            return LibraryPickerRepository.browsableRow(
                mediaId = mediaId,
                title = appContext.getString(R.string.aa_select_library)
            )
        }
        return null
    }

    fun getChildren(id: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return when (id) {
            ConstantsAA.ROOT_ID -> treeNodes[ConstantsAA.ROOT_ID]!!.getChildren()

            ConstantsAA.PLAYLIST_ID -> browseRepository.getPlaylists(ConstantsAA.PLAYLIST_ID)
            ConstantsAA.ARTISTS_ID -> browseRepository.getArtists(ConstantsAA.ARTIST_ID)
            ConstantsAA.ALBUMS_ID -> browseRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                LibraryClient.SORT_TITLE
            )

            ConstantsAA.ARTISTS_BY_ALBUMS_ID -> browseRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                LibraryClient.SORT_ARTIST
            )

            ConstantsAA.MORE_ID -> treeNodes[ConstantsAA.MORE_ID]!!.getChildren()

            ConstantsAA.SELECT_LIBRARY_ID -> pickerRepository.getServers()

            else -> {
                if (id.startsWith(ConstantsAA.PLAYLIST_ID)) {
                    return browseRepository.getPlaylistTracks(
                        id.removePrefix(ConstantsAA.PLAYLIST_ID)
                    )
                }
                if (id.startsWith(ConstantsAA.ALBUM_ID)) {
                    return browseRepository.getAlbumTracks(
                        id.removePrefix(ConstantsAA.ALBUM_ID)
                    )
                }
                if (id.startsWith(ConstantsAA.ARTIST_ID)) {
                    return browseRepository.getArtistAlbums(
                        ConstantsAA.ALBUM_ID,
                        id.removePrefix(ConstantsAA.ARTIST_ID)
                    )
                }
                if (id.startsWith(ConstantsAA.PICK_LIBRARY_ID)) {
                    val payload = id.removePrefix(ConstantsAA.PICK_LIBRARY_ID)
                    if (payload.endsWith(LibraryPickerRepository.CONFIRMED_SUFFIX)) {
                        // Tapping the confirmation row returns the row. Committing
                        // again would be wrong and an empty list is the blank
                        // screen the row exists to avoid.
                        return Futures.immediateFuture(
                            LibraryResult.ofItemList(
                                ImmutableList.of(
                                    pickerRepository.confirmationRow(
                                        payload.removeSuffix(
                                            LibraryPickerRepository.CONFIRMED_SUFFIX
                                        )
                                    )
                                ),
                                null
                            )
                        )
                    }
                    return pickerRepository.selectLibrary(payload)
                }
                if (id.startsWith(ConstantsAA.PICK_SERVER_ID)) {
                    return pickerRepository.getLibraries(
                        id.removePrefix(ConstantsAA.PICK_SERVER_ID)
                    )
                }
                if (id.startsWith(ConstantsAA.PICK_MESSAGE_ID)) {
                    // Same rule as the confirmation row: a row that explains a
                    // failure must not become a blank screen when it is tapped.
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(
                            ImmutableList.of(
                                LibraryPickerRepository.messageRow(
                                    id.removePrefix(ConstantsAA.PICK_MESSAGE_ID)
                                )
                            ),
                            null
                        )
                    )
                }
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }
    }

    fun search(query: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return browseRepository.search(
            query,
            ConstantsAA.ALBUM_ID,
            ConstantsAA.ARTIST_ID
        )
    }
}
