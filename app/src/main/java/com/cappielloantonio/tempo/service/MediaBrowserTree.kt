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
import com.cappielloantonio.tempo.repository.LibraryPickerRepository
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.util.BrowseContentStyle
import com.cappielloantonio.tempo.util.ConstantsAA
import com.cappielloantonio.tempo.util.Preferences
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

    private const val SIGNED_OUT_ROW_ID = "siskin://signed-out"

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
        browsableChildrenAsGrid: Boolean,
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
     * Grid-versus-list styling: Playlists is a list, and Artists and Albums
     * became lists when they started serving group rows -- a window's range
     * ("Beck  -  Cake") or a bucket's letter carries no artwork of its own, and a
     * grid of placeholders is worse than a list, the same call
     * decadeToMediaItem makes. Artists is a list under *both* settings on
     * purpose: this runs on onGetLibraryRoot, before any library has been
     * queried, so a style that followed the by-initial preference would need the
     * root invalidated and visibly re-rendered on every toggle. It governs each
     * tab's *browsable* children only -- see BrowseContentStyle for why tracks
     * are never affected.
     */
    fun buildTree() {
        treeNodes.clear()

        treeNodes[ConstantsAA.ROOT_ID] =
            MediaItemNode(
                buildMediaItem(
                    browsableChildrenAsGrid = true,
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
                    browsableChildrenAsGrid = false,
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
                    browsableChildrenAsGrid = false,
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
                    browsableChildrenAsGrid = false,
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
                    browsableChildrenAsGrid = false,
                    title = appContext.getString(R.string.aa_more),
                    mediaId = ConstantsAA.MORE_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_more),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.DECADES_ID] =
            MediaItemNode(
                buildMediaItem(
                    // A grid: each row wears a composite of four covers drawn
                    // from that decade, so there is artwork worth the space.
                    // See the 2026-08-09 decade composite artwork design.
                    browsableChildrenAsGrid = true,
                    title = appContext.getString(R.string.aa_decades),
                    mediaId = ConstantsAA.DECADES_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_decades),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.SELECT_LIBRARY_ID] =
            MediaItemNode(
                buildMediaItem(
                    browsableChildrenAsGrid = false,
                    title = appContext.getString(R.string.aa_select_library),
                    mediaId = ConstantsAA.SELECT_LIBRARY_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_library),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.MORE_ID]!!.addChild(ConstantsAA.DECADES_ID)
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

        // Same requirement as the picker rows above, for the same reason: the
        // signed-out row is browsable (see signedOutRow's KDoc), so a tap on
        // it drives onSubscribe through onGetItem before onGetChildren ever
        // runs. It is not registered in treeNodes -- it is built fresh per
        // request by onGetChildren's no-credentials guard, not by
        // buildTree -- so without this branch the lookup above misses it and
        // the subscription is refused.
        if (mediaId == SIGNED_OUT_ROW_ID) {
            return signedOutRow(appContext).single()
        }

        // No branch for DECADE_ID rows, deliberately: unlike the picker and
        // signed-out rows above, nothing calls BrowseTreeInvalidator on a
        // decade, so there's no live subscription that a null here would
        // drop. Artist and album rows already navigate correctly with no
        // branch here either.
        //
        // That is a different question from the DECADES_ID node itself: like
        // the three music tabs, it is a registered tree node left stale on a
        // library switch on purpose -- see LibraryPickerRepository.selectLibrary,
        // which invalidates the root and the picker node but not those tabs.
        return null
    }

    fun getChildren(id: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return when (id) {
            ConstantsAA.ROOT_ID -> treeNodes[ConstantsAA.ROOT_ID]!!.getChildren()

            ConstantsAA.PLAYLIST_ID -> browseRepository.getPlaylists(ConstantsAA.PLAYLIST_ID)

            // The one place the by-initial preference is read. The tab's own
            // style does not depend on it -- see buildTree -- so nothing about
            // the root has to change when it is toggled; only this list does.
            ConstantsAA.ARTISTS_ID -> if (Preferences.isArtistsByInitialEnabled()) {
                browseRepository.getArtistLetters(
                    ConstantsAA.ARTIST_LETTER_ID,
                    ConstantsAA.ARTIST_ID
                )
            } else {
                browseRepository.getArtistWindows(
                    ConstantsAA.ARTIST_WINDOW_ID,
                    ConstantsAA.ARTIST_ID
                )
            }

            ConstantsAA.ALBUMS_ID -> browseRepository.getAlbumWindows(
                ConstantsAA.ALBUM_WINDOW_ID,
                ConstantsAA.ALBUM_ID
            )

            ConstantsAA.MORE_ID -> treeNodes[ConstantsAA.MORE_ID]!!.getChildren()

            ConstantsAA.SELECT_LIBRARY_ID -> pickerRepository.getServers()

            ConstantsAA.DECADES_ID -> browseRepository.getDecades(ConstantsAA.DECADE_ID)

            else -> {
                if (id.startsWith(ConstantsAA.PLAYLIST_ID)) {
                    return browseRepository.getPlaylistTracks(
                        id.removePrefix(ConstantsAA.PLAYLIST_ID)
                    )
                }
                // Before the ARTIST_ID/ALBUM_ID tests below: no window or letter
                // id is a prefix of an item id, but keeping the narrower matches
                // first means that stays true by construction rather than by
                // coincidence of spelling.
                if (id.startsWith(ConstantsAA.ARTIST_WINDOW_ID)) {
                    return browseRepository.getArtistWindow(
                        id.removePrefix(ConstantsAA.ARTIST_WINDOW_ID).toIntOrNull() ?: 0,
                        ConstantsAA.ARTIST_ID
                    )
                }
                if (id.startsWith(ConstantsAA.ALBUM_WINDOW_ID)) {
                    return browseRepository.getAlbumWindow(
                        id.removePrefix(ConstantsAA.ALBUM_WINDOW_ID).toIntOrNull() ?: 0,
                        ConstantsAA.ALBUM_ID
                    )
                }
                if (id.startsWith(ConstantsAA.ARTIST_LETTER_ID)) {
                    return browseRepository.getArtistLetter(
                        id.removePrefix(ConstantsAA.ARTIST_LETTER_ID),
                        ConstantsAA.ARTIST_ID
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
                if (id.startsWith(ConstantsAA.DECADE_ID)) {
                    // The DecadeKey payload -- library and decade -- handed over
                    // whole. Nothing here needs to know its shape, and the
                    // shuffle row the repository builds from it has to carry the
                    // same string the callback's cache guard rebuilds.
                    return browseRepository.getDecadeTracks(
                        id.removePrefix(ConstantsAA.DECADE_ID)
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
                if (id == SIGNED_OUT_ROW_ID) {
                    // Reachable while signed in, not just while signed out:
                    // [MediaLibrarySessionCallback.onGetChildren]'s
                    // no-credentials guard is what normally answers a request
                    // for this id, but that guard only fires while
                    // CredentialGate.isSignedIn() is false. Drill into this
                    // row signed out, then sign in via the gear --
                    // onLoginSuccess() finishes the activity, and the car
                    // returns to the drilled-in screen underneath and
                    // re-requests the same node, now signed in, so the
                    // request reaches this `when` directly instead of the
                    // guard. Same rule as the confirmation row and the
                    // message row above: a row that explains a state must not
                    // become a blank screen -- here, ERROR_BAD_VALUE -- when
                    // the state it described has already changed underneath
                    // it.
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(signedOutRow(appContext), null)
                    )
                }
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }
    }

    /**
     * What a non-root browse node shows when there are no credentials.
     *
     * A row rather than a LibraryResult.ofError: the car places an error's
     * message and button wherever it likes, and a Cadillac puts that button
     * under a mini player it never hides. A list row cannot be covered.
     *
     * Browsable, but not playable. The content was first built as a single
     * row, neither browsable nor playable -- tapping it can do nothing
     * useful, so that read as the honest shape. Measured wrong: on an AAOS
     * API 33 emulator, signed out, the browse screen rendered with no row at
     * all. `uiautomator` showed `browse_content_area` with no list child
     * whatsoever; the only thing onscreen was the car's own empty
     * mini-player bar, easy to mistake for content at a glance. A
     * `MediaItem` that is neither browsable nor playable is not merely inert
     * to this car -- it is invisible, which is worse than the error result
     * it replaced.
     *
     * The next attempt was this same shape -- one row, browsable, with
     * itself as its only child, the fallback the design doc named for that
     * risk. That was measured wrong too, one layer deeper: this row was, at
     * the time, being returned as a **root** child, and
     * `com.android.car.media` auto-drills into a root node whose only child
     * is itself browsable. The message rendered twice -- once correctly, as
     * the `car_ui_toolbar_title` where "Siskin" belongs, and again as the
     * sole row underneath it -- and tapping the row re-entered the same
     * single-child node forever. The view hierarchy showed two stacked
     * `browse_list` levels for what was meant to be one screen.
     *
     * The fix attempted next was splitting the message across two rows
     * instead of one -- more than one child leaves nothing for the car to
     * auto-drill into. That does hold *below* the root: two rows on an
     * ordinary node render as two ordinary rows. Measured wrong anyway, for
     * a reason one layer up from either row shape: **the browse root itself
     * is a tab bar, not a list.** A fourth pass tried one root child, then
     * two, and found the same auto-drill/truncation problems regardless of
     * row count or content, because root children are rendered only as tabs
     * -- there is no way to place list content at the root at all. The fix
     * is not a different row shape but a different node: stop returning
     * this row (or rows) as a root child. Signed out, the root now returns
     * its normal four tabs -- Playlists, Artists, Albums, More, built by
     * [buildTree] exactly as when signed in, since that tree is static and
     * needs no credentials -- and the car auto-opens the first tab, landing
     * the user on this row immediately with the toolbar correctly reading
     * "Siskin". Every *non-root* `parentId` returns this single row, which
     * is what the two-line split above was solving for in the first place,
     * so it collapses back into one row using both lines the browse list
     * already gives every item: `car_sign_in_required` as the title,
     * `car_sign_in_hint` as the subtitle. That costs nothing *further*: both
     * lines were already in place by the two-row split, which is what first
     * introduced `car_sign_in_hint` as a second row's title. But
     * `car_sign_in_hint` is not a pre-existing string overall -- it is new to
     * this branch, one of the three the design doc's string budget accounts
     * for (alongside the Settings heading and the Sign out button);
     * `car_sign_in_required` is the one that predates this work.
     *
     * [MediaLibrarySessionCallback.onGetChildren]'s no-credentials guard
     * exempts `ROOT_ID` for exactly this reason and falls through to the
     * normal tab construction for it; every other `parentId` still answers
     * with this row, so a request for the row's own id lands back here
     * again -- while signed out. Signed in, the guard no longer intercepts
     * (`CredentialGate.isSignedIn()` is true), so the same id can still
     * reach [getChildren] directly: drill into this row while signed out,
     * sign in through the gear, and the car returns to the screen it was
     * already showing underneath and re-requests that same node, now signed
     * in. [getChildren]'s own `SIGNED_OUT_ROW_ID` branch is what answers
     * that with this row again rather than `ERROR_BAD_VALUE`, the same rule
     * the confirmation row and the message row above follow: a row that
     * explains a state must not become a blank screen when the state it
     * described has already changed underneath it. [getItem] does need a
     * branch for the row's id, because the default `onSubscribe` validates
     * a target through `onGetItem` before a tap into a browsable row is
     * allowed to stick.
     *
     * Still not playable: background activity-launch restrictions mean we
     * cannot start the sign-in screen ourselves, only the car can, which is
     * what the subtitle points at. Making the row browsable does not change
     * that -- drilling into it only shows the same row again, never a
     * stream.
     */
    fun signedOutRow(context: Context): ImmutableList<MediaItem> = ImmutableList.of(
        MediaItem.Builder()
            .setMediaId(SIGNED_OUT_ROW_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.car_sign_in_required))
                    // The browse list's second line, the same one an album
                    // uses for its artist.
                    .setArtist(context.getString(R.string.car_sign_in_hint))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    )

    fun search(query: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return browseRepository.search(
            query,
            ConstantsAA.ALBUM_ID,
            ConstantsAA.ARTIST_ID
        )
    }
}
