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
                Cell(256, 256, 512, 512),
            ),
            cells,
        )
    }

    @Test
    fun moreCoversThanCellsStillYieldsFourCells() {
        // The fetch over-fetches deliberately; the layout must not grow with it.
        assertEquals(4, CompositeGrid.cells(count = CompositeGrid.OVER_FETCH, size = 512).size)
    }

    @Test
    fun oneCoverIsStillFullBleed() {
        // The one layout the sparse rule does not touch: with one cover there
        // is nothing to under-report, and a quarter-size cover surrounded by
        // three dark cells is the one arrangement here that really would look
        // like artwork that failed to load.
        assertEquals(
            listOf(Cell(0, 0, 512, 512)),
            CompositeGrid.cells(count = 1, size = 512),
        )
    }

    @Test
    fun twoCoversTakeTheDiagonal() {
        // Top-left and bottom-right rather than a filled top row. Both keep
        // square cells; the diagonal reads as a deliberate arrangement where a
        // top row reads as a grid that stopped halfway.
        assertEquals(
            listOf(
                Cell(0, 0, 256, 256),
                Cell(256, 256, 512, 512),
            ),
            CompositeGrid.cells(count = 2, size = 512),
        )
    }

    @Test
    fun threeCoversFillInReadingOrderAndLeaveTheBottomRightDark() {
        assertEquals(
            listOf(
                Cell(0, 0, 256, 256),
                Cell(256, 0, 512, 256),
                Cell(0, 256, 256, 512),
            ),
            CompositeGrid.cells(count = 3, size = 512),
        )
    }

    @Test
    fun anUndrawnCellIsAbsentRatherThanEmpty() {
        // The draw loop pairs cell n with cover n, so a "blank" has to be a
        // missing cell and not a zero-area rectangle sitting in the list --
        // the latter would pair a real cover with a rectangle of no pixels and
        // silently drop it.
        (1..CompositeGrid.COVERS).forEach { count ->
            assertEquals("count=$count", count, CompositeGrid.cells(count, size = 512).size)
        }
    }

    @Test
    fun aSparseLayoutAlsoTilesAnOddSizeWithNoSeam() {
        // Same guard as the 2x2's, on the layouts that share its midpoint.
        val two = CompositeGrid.cells(count = 2, size = 513)
        assertEquals(two[0].right, two[1].left)
        assertEquals(two[0].bottom, two[1].top)
        assertEquals(513, two[1].right)
        assertEquals(513, two[1].bottom)

        val three = CompositeGrid.cells(count = 3, size = 513)
        assertEquals(three[0].right, three[1].left)
        assertEquals(three[0].bottom, three[2].top)
        assertEquals(513, three[1].right)
        assertEquals(513, three[2].bottom)
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
