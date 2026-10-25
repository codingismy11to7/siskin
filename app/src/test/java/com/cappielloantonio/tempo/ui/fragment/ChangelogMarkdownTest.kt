package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.ui.fragment.ChangelogBlock.Bullet
import com.cappielloantonio.tempo.ui.fragment.ChangelogBlock.Heading
import com.cappielloantonio.tempo.ui.fragment.ChangelogBlock.Paragraph
import com.cappielloantonio.tempo.ui.fragment.ChangelogRun.Style.BOLD
import com.cappielloantonio.tempo.ui.fragment.ChangelogRun.Style.CODE
import com.cappielloantonio.tempo.ui.fragment.ChangelogRun.Style.ITALIC
import com.cappielloantonio.tempo.ui.fragment.ChangelogRun.Style.PLAIN
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parser half of the Changelog tab, framework-free so that it is asserted
 * directly rather than through spans that `returnDefaultValues` would stub.
 */
class ChangelogMarkdownTest {
    private fun plain(text: String) = listOf(ChangelogRun(text, PLAIN))

    private fun parse(vararg lines: String) = ChangelogMarkdown.parse(lines.joinToString("\n"))

    @Test
    fun `a heading drops the brackets around its version`() {
        assertEquals(
            listOf(Heading(plain("1.1.0 (2026-10-24)")), Bullet(plain("a"))),
            parse("## [1.1.0] (2026-10-24)", "", "- a"),
        )
    }

    @Test
    fun `a heading ends at its own line`() {
        assertEquals(
            listOf(Heading(plain("0.99.0 (2026-07-31)")), Paragraph(plain("The first build."))),
            parse("## [0.99.0] (2026-07-31)", "The first build."),
        )
    }

    @Test
    fun `a bullet's continuation lines join into one`() {
        assertEquals(
            listOf(Bullet(plain("Settings has a switch, off by default."))),
            parse("- Settings has a switch,", "  off by default."),
        )
    }

    @Test
    fun `tight and loose lists parse alike`() {
        val expected = listOf(Bullet(plain("a")), Bullet(plain("b")))

        assertEquals(expected, parse("- a", "- b"))
        assertEquals(expected, parse("- a", "", "- b"))
    }

    @Test
    fun `a paragraph's lines join into one`() {
        assertEquals(
            listOf(Paragraph(plain("The first build. Never tagged."))),
            parse("The first build.", "Never tagged."),
        )
    }

    @Test
    fun `inline markup becomes styled runs`() {
        assertEquals(
            listOf(
                Bullet(
                    listOf(
                        ChangelogRun("Debug:", BOLD),
                        ChangelogRun(" runs ", PLAIN),
                        ChangelogRun("cut-release.sh", CODE),
                        ChangelogRun(" ", PLAIN),
                        ChangelogRun("in", ITALIC),
                        ChangelogRun(" the car (#53).", PLAIN),
                    ),
                ),
            ),
            parse("- **Debug:** runs `cut-release.sh` *in* the car ([#53](https://example.invalid/53))."),
        )
    }

    @Test
    fun `a link keeps its text and drops its URL`() {
        assertEquals(
            listOf(Paragraph(plain("a fork of Tempus, itself"))),
            parse("a fork of [Tempus](https://github.com/eddyizm/tempus), itself"),
        )
    }

    @Test
    fun `emphasis does not run into a code span`() {
        assertEquals(
            listOf(
                Paragraph(
                    listOf(
                        ChangelogRun("use *one of ", PLAIN),
                        ChangelogRun("*.plex.direct", CODE),
                        ChangelogRun(" hosts", PLAIN),
                    ),
                ),
            ),
            parse("use *one of `*.plex.direct` hosts"),
        )
    }

    @Test
    fun `emphasis can hold a code span`() {
        assertEquals(
            listOf(Paragraph(listOf(ChangelogRun("see ", BOLD), ChangelogRun("x", CODE)))),
            parse("**see `x`**"),
        )
    }

    @Test
    fun `a marker beside whitespace is not emphasis`() {
        assertEquals(listOf(Paragraph(plain("a * b * c"))), parse("a * b * c"))
    }

    @Test
    fun `an unclosed marker stays as text`() {
        assertEquals(
            listOf(Bullet(plain("**Debug: never closed"))),
            parse("- **Debug: never closed"),
        )
    }

    @Test
    fun `syntax outside the subset comes through as text`() {
        assertEquals(
            listOf(Heading(plain("1.0.0")), Paragraph(plain("### Fixed | a | b |"))),
            parse("## [1.0.0]", "", "### Fixed", "| a | b |"),
        )
    }

    @Test
    fun `a heading with nothing under it is dropped`() {
        assertEquals(
            listOf(Heading(plain("1.1.0 (2026-10-24)")), Bullet(plain("a"))),
            parse("## [Unreleased] — Siskin", "", "## [1.1.0] (2026-10-24)", "", "- a"),
        )
    }

    @Test
    fun `a trailing heading with nothing under it is dropped`() {
        assertEquals(
            listOf(Bullet(plain("a"))),
            parse("- a", "", "## [0.99.0]", ""),
        )
    }
}
