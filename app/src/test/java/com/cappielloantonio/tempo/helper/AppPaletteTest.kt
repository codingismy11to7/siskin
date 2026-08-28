package com.cappielloantonio.tempo.helper

import com.cappielloantonio.tempo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pins the two things about the palette that are decisions rather than taste:
 * the app has a green of its own, and every filled button's label stays
 * legible on it.
 *
 * The second is the hazard #133 called out. `colorOnPrimary` has to move
 * whenever `colorPrimary` does -- both button sites draw their text with it,
 * and `addChoice` restores it by hand after `setTextAppearance` -- so a
 * primary swapped without its onPrimary lands as pale text on a pale fill,
 * which is legible on a desk and not at arm's length in daylight.
 *
 * Colours are read back through resources rather than restated here, so the
 * test fails when colors.xml changes rather than agreeing with itself.
 */
@RunWith(RobolectricTestRunner::class)
class AppPaletteTest {
    private val context = RuntimeEnvironment.getApplication()

    private val palettes =
        listOf(
            Palette("light", R.color.md_theme_light_primary, R.color.md_theme_light_onPrimary),
            Palette("dark", R.color.md_theme_dark_primary, R.color.md_theme_dark_onPrimary),
            Palette("amoled", R.color.md_theme_amoled_primary, R.color.md_theme_amoled_onPrimary),
        )

    private data class Palette(
        val name: String,
        val primary: Int,
        val onPrimary: Int,
    )

    @Test
    fun primaryIsGreenInEveryPalette() {
        palettes.forEach { palette ->
            val hue = hueOf(context.getColor(palette.primary))
            assertTrue(
                "${palette.name} primary should be green, hue was $hue",
                hue in GREEN_HUE_RANGE,
            )
        }
    }

    /**
     * 4.5:1 is WCAG AA for body text. A button label is large enough to
     * qualify for the 3:1 large-text allowance, and the stricter bar is
     * deliberate: the reader is at arm's length and may be in sunlight.
     */
    @Test
    fun buttonLabelsStayLegibleOnPrimary() {
        palettes.forEach { palette ->
            val ratio =
                contrastRatio(
                    context.getColor(palette.primary),
                    context.getColor(palette.onPrimary),
                )
            assertTrue(
                "${palette.name} onPrimary on primary was ${"%.2f".format(ratio)}:1",
                ratio >= 4.5,
            )
        }
    }

    /**
     * Amoled exists to take the surface family to black; it is not a second
     * accent. A primary that drifts from dark's would be a change nobody
     * asked for, so it is pinned rather than left to notice later.
     */
    @Test
    fun amoledBorrowsDarksPrimary() {
        assertEquals(
            context.getColor(R.color.md_theme_dark_primary),
            context.getColor(R.color.md_theme_amoled_primary),
        )
        assertEquals(
            context.getColor(R.color.md_theme_dark_onPrimary),
            context.getColor(R.color.md_theme_amoled_onPrimary),
        )
    }

    private fun hueOf(color: Int): Double {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val span = hi - lo
        if (span == 0.0) return 0.0
        val hue =
            when (hi) {
                r -> ((g - b) / span) % 6.0
                g -> (b - r) / span + 2.0
                else -> (r - g) / span + 4.0
            } * 60.0
        return if (hue < 0) hue + 360.0 else hue
    }

    private fun contrastRatio(
        first: Int,
        second: Int,
    ): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val raw = ((color shr shift) and 0xFF) / 255.0
            return if (raw <= 0.03928) raw / 12.92 else ((raw + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        /**
         * Yellow-green through to the blue end of teal. Wide enough that
         * regenerating the palette from a different green does not fail the
         * test, narrow enough to exclude the purple this replaced (258) and
         * the head unit's blue (228).
         */
        val GREEN_HUE_RANGE = 90.0..180.0
    }
}
