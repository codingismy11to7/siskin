package com.cappielloantonio.tempo.provider

/**
 * Where each cover goes in a composite.
 *
 * Four rather than nine, on legibility: a browse-grid tile on the 1024x768
 * landscape head unit is on the order of 240px, which gives roughly 120px a
 * cover at 2x2 and roughly 80px at 3x3, and at 80px the text on a cover is gone
 * and only dominant colour survives. Four also matches the shape of Plex's own
 * playlist composite, which the Playlists tab already renders, so the two browse
 * surfaces agree.
 */
object CompositeGrid {

    /** The composite's edge, in pixels. Matches AlbumArtContentProvider's
     * DEFAULT_ARTWORK_SIZE so a decade tile costs the car no more than an album
     * one. */
    const val SIZE = 512

    /** Cells in a full grid. */
    const val COVERS = 4

    /**
     * How many albums to ask the server for.
     *
     * More than [COVERS] because an album carrying no thumb would otherwise
     * leave a hole. Free on the same request, and it removes the missing-cover
     * case rather than designing a fallback for it. The spares also cover a
     * cover that fails to *load* -- [CompositeArt.pick] walks past a failure
     * rather than discarding these before the fetch, which is what lets a
     * failed load cost a cell instead of the whole tile.
     */
    const val OVER_FETCH = 8

    /**
     * A destination rectangle, in composite pixels.
     *
     * Deliberately not `android.graphics.Rect`: android.jar is stubbed under
     * unitTests.returnDefaultValues, so a Rect constructed in a unit test is not
     * reliably the Rect it looks like, and the layout assertions would pass
     * while measuring nothing. Same reason MediaUrlBuilder reaches for
     * java.net.URLEncoder over android.net.Uri. Conversion happens at the draw
     * call, which is not unit-tested anyway.
     */
    data class Cell(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Where [count] covers go, in fill order: the draw loop pairs cell *n* with
     * cover *n*, so the order here is the order covers are consumed.
     *
     * | covers | layout |
     * |---|---|
     * | 0 | nothing — the caller draws no composite at all |
     * | 1 | one cover, full-bleed |
     * | 2 | the diagonal: top-left and bottom-right |
     * | 3 | top-left, top-right, bottom-left |
     * | 4 or more | the full 2x2 |
     *
     * **A sparse layout returns fewer cells than the grid has positions**, not
     * four cells of which some are empty. An undrawn cell is an absence, so the
     * draw loop needs no notion of skipping one.
     *
     * Nothing is drawn into the remaining positions, which leaves them black --
     * the composite is RGB_565 and carries no alpha, and black is also the car's
     * browse background. That is what makes a sparse composite read as
     * composition rather than as damage: there is no visible empty box beside
     * the covers to look like a failed load. It reverses the rule this file
     * shipped with; see 2026-08-16-sparse-composite-cells-design.md, which
     * supersedes the decade design's *Four covers, in the largest grid that
     * fills completely*.
     *
     * Repeating a cover to fill a position is still refused, for the reason
     * that design gave: it would claim albums that are not there.
     *
     * The midpoint is used as both the right edge of the first column and the
     * left edge of the second, rather than computing each from `size / 2`, so an
     * odd size leaves neither a seam nor an overhang.
     */
    @JvmStatic
    fun cells(count: Int, size: Int): List<Cell> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(Cell(0, 0, size, size))

        val mid = size / 2
        val topLeft = Cell(0, 0, mid, mid)
        val topRight = Cell(mid, 0, size, mid)
        val bottomLeft = Cell(0, mid, mid, size)
        val bottomRight = Cell(mid, mid, size, size)

        return when (count) {
            2 -> listOf(topLeft, bottomRight)
            3 -> listOf(topLeft, topRight, bottomLeft)
            else -> listOf(topLeft, topRight, bottomLeft, bottomRight)
        }
    }
}
