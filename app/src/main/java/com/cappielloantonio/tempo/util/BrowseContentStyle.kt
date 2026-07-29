package com.cappielloantonio.tempo.util

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants

/**
 * Grid-versus-list hints for the car's browse UI.
 *
 * The two content-style keys describe an item's **children**, not the item
 * itself: EXTRAS_KEY_CONTENT_STYLE_BROWSABLE styles the browsable children and
 * EXTRAS_KEY_CONTENT_STYLE_PLAYABLE the playable ones. An album is therefore
 * rendered as a tile because the *Albums tab* asked for a browsable grid, not
 * because the album says so — what the album's own hints control is how its
 * tracks appear.
 *
 * Writing one value to both keys, which is what this replaced, made every node
 * that wanted a grid of albums also ask for a grid of tracks.
 */
@OptIn(UnstableApi::class)
object BrowseContentStyle {

    /**
     * Playable children are always tracks, and tracks are always a list.
     *
     * A track grid is four tiles across, so it fits a single row per screen
     * where a list fits five — and every tile carries the same album cover,
     * because that is the art a track inherits. The one visual that
     * distinguishes the rows is identical in all of them, which is the case a
     * grid is worst at.
     */
    const val PLAYABLE_CHILD_STYLE = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

    /**
     * Browsable children follow the node's preference: albums and artists carry
     * distinct art worth showing large, while playlists and the More entries
     * are text and read better as rows.
     */
    fun browsableChildStyle(asGrid: Boolean): Int =
        if (asGrid) {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        } else {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
}
