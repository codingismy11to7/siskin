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
     * case rather than designing a fallback for it.
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
     * The largest grid that fills completely: 2x2 from four covers or more, one
     * full-bleed cover from one to three, nothing from none.
     *
     * The midpoint is used as both the right edge of the first column and the
     * left edge of the second, rather than computing each from `size / 2`, so an
     * odd size leaves neither a seam nor an overhang.
     */
    @JvmStatic
    fun cells(count: Int, size: Int): List<Cell> {
        if (count <= 0) return emptyList()
        if (count < COVERS) return listOf(Cell(0, 0, size, size))

        val mid = size / 2
        return listOf(
            Cell(0, 0, mid, mid),
            Cell(mid, 0, size, mid),
            Cell(0, mid, mid, size),
            Cell(mid, mid, size, size)
        )
    }
}
