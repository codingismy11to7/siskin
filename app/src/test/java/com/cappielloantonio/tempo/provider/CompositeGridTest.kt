package com.cappielloantonio.tempo.provider

import com.cappielloantonio.tempo.provider.CompositeGrid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit, and [Cell] is a plain data class rather than
 * `android.graphics.Rect`, for the reason MediaUrlBuilder uses
 * java.net.URLEncoder over android.net.Uri: android.jar is stubbed under this
 * module's unitTests.returnDefaultValues, so a Rect built here is not reliably
 * the Rect it looks like and these assertions could pass while measuring
 * nothing.
 */
class CompositeGridTest {

    @Test
    fun fourOrMoreCoversTileIntoQuadrantsThatCoverTheWholeImage() {
        val cells = CompositeGrid.cells(count = 4, size = 512)

        assertEquals(
            listOf(
                Cell(0, 0, 256, 256),
                Cell(256, 0, 512, 256),
                Cell(0, 256, 256, 512),
                Cell(256, 256, 512, 512)
            ),
            cells
        )
    }

    @Test
    fun moreCoversThanCellsStillYieldsFourCells() {
        // The fetch over-fetches deliberately; the layout must not grow with it.
        assertEquals(4, CompositeGrid.cells(count = CompositeGrid.OVER_FETCH, size = 512).size)
    }

    @Test
    fun fewerThanFourCoversYieldOneFullBleedCell() {
        // The largest grid that fills completely. A decade with two albums
        // genuinely has no mosaic, and one cover says that honestly -- repeats
        // would claim albums that are not there, and empty cells look like
        // artwork that failed to load, which is the one thing this must never
        // be confused with.
        listOf(1, 2, 3).forEach { count ->
            assertEquals(
                "count=$count",
                listOf(Cell(0, 0, 512, 512)),
                CompositeGrid.cells(count = count, size = 512)
            )
        }
    }

    @Test
    fun noCoversYieldNoCells() {
        assertTrue(CompositeGrid.cells(count = 0, size = 512).isEmpty())
    }

    @Test
    fun anOddSizeStillTilesWithNoSeamAndNoOverhang() {
        // Guards the obvious `size / 2` for both edges, which would leave a
        // one-pixel gap down the middle of an odd-sized image.
        val cells = CompositeGrid.cells(count = 4, size = 513)

        assertEquals(0, cells[0].left)
        assertEquals(513, cells[3].right)
        assertEquals(513, cells[3].bottom)
        assertEquals(cells[0].right, cells[1].left)
        assertEquals(cells[0].bottom, cells[2].top)
    }
}
