package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.ui.fragment.MarkdownBlock.Bullet
import com.cappielloantonio.tempo.ui.fragment.MarkdownBlock.Heading
import com.cappielloantonio.tempo.ui.fragment.MarkdownBlock.Paragraph
import com.cappielloantonio.tempo.ui.fragment.MarkdownRun.Style.BOLD
import com.cappielloantonio.tempo.ui.fragment.MarkdownRun.Style.CODE
import com.cappielloantonio.tempo.ui.fragment.MarkdownRun.Style.ITALIC
import com.cappielloantonio.tempo.ui.fragment.MarkdownRun.Style.PLAIN
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The markdown parser behind the Changelog tab and Settings help, with the
 * changelog's own filter and the help file's sections. Framework-free, so it
 * is asserted directly rather than through spans that `returnDefaultValues`
 * would stub.
 */
class CarMarkdownTest {
    private fun plain(text: String) = listOf(MarkdownRun(text, PLAIN))

    private fun parse(vararg lines: String) = ChangelogMarkdown.parse(lines.joinToString("\n"))

    @Test
    fun `a heading drops the brackets around its version`() {
        assertEquals(
            listOf(Heading(2, plain("1.1.0 (2026-10-24)")), Bullet(plain("a"))),
            parse("## [1.1.0] (2026-10-24)", "", "- a"),
        )
    }

    @Test
    fun `a heading ends at its own line`() {
        assertEquals(
            listOf(Heading(2, plain("0.99.0 (2026-07-31)")), Paragraph(plain("The first build."))),
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
                        MarkdownRun("Debug:", BOLD),
                        MarkdownRun(" runs ", PLAIN),
                        MarkdownRun("cut-release.sh", CODE),
                        MarkdownRun(" ", PLAIN),
                        MarkdownRun("in", ITALIC),
                        MarkdownRun(" the car (#53).", PLAIN),
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
                        MarkdownRun("use *one of ", PLAIN),
                        MarkdownRun("*.plex.direct", CODE),
                        MarkdownRun(" hosts", PLAIN),
                    ),
                ),
            ),
            parse("use *one of `*.plex.direct` hosts"),
        )
    }

    @Test
    fun `emphasis can hold a code span`() {
        assertEquals(
            listOf(Paragraph(listOf(MarkdownRun("see ", BOLD), MarkdownRun("x", CODE)))),
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
            listOf(Heading(2, plain("1.0.0")), Paragraph(plain("#### Fixed | a | b |"))),
            parse("## [1.0.0]", "", "#### Fixed", "| a | b |"),
        )
    }

    @Test
    fun `a heading with nothing under it is dropped`() {
        assertEquals(
            listOf(Heading(2, plain("1.1.0 (2026-10-24)")), Bullet(plain("a"))),
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

    @Test
    fun `a heading with a subheading under it is kept`() {
        assertEquals(
            listOf(Heading(2, plain("1.6.0")), Heading(3, plain("Fixed")), Bullet(plain("a"))),
            parse("## [1.6.0]", "", "### Fixed", "", "- a"),
        )
    }

    @Test
    fun `a subheading with nothing under it before a heading is dropped`() {
        assertEquals(
            listOf(Heading(2, plain("1.6.0")), Bullet(plain("a")), Heading(2, plain("1.5.0")), Bullet(plain("b"))),
            parse("## [1.6.0]", "- a", "### Fixed", "## [1.5.0]", "- b"),
        )
    }

    private fun general(vararg lines: String) = CarMarkdown.parse(lines.joinToString("\n"))

    @Test
    fun `a third-level heading is a heading of its own level`() {
        assertEquals(
            listOf(Heading(2, plain("Playback")), Heading(3, plain("Volume leveling")), Paragraph(plain("Evens it out."))),
            general("## Playback", "", "### Volume leveling", "Evens it out."),
        )
    }

    @Test
    fun `a heading drops its id`() {
        assertEquals(listOf(Heading(2, plain("Playback"))), general("## Playback {#playback}"))
    }

    @Test
    fun `braces that are not an id stay in the heading`() {
        assertEquals(
            listOf(Heading(2, plain("Playback {#two words}")), Heading(2, plain("Storage {#}"))),
            general("## Playback {#two words}", "## Storage {#}"),
        )
    }

    @Test
    fun `the general parser keeps an empty heading`() {
        assertEquals(listOf(Heading(2, plain("a")), Heading(2, plain("b"))), general("## a", "## b"))
    }

    private val help =
        listOf(
            "# Settings help",
            "",
            "Intro.",
            "",
            "## Playback {#playback}",
            "",
            "### Volume leveling",
            "Evens it out.",
            "",
            "## Storage {#storage}",
            "",
            "### Cache size",
            "Keeps music.",
        ).joinToString("\n")

    @Test
    fun `section ids are listed in order`() {
        assertEquals(listOf("playback", "storage"), CarMarkdown.sectionIds(help))
    }

    @Test
    fun `a section runs from its heading to the next one`() {
        assertEquals("\n### Volume leveling\nEvens it out.\n", CarMarkdown.section(help, "playback"))
    }

    @Test
    fun `the last section runs to the end`() {
        assertEquals("\n### Cache size\nKeeps music.", CarMarkdown.section(help, "storage"))
    }

    @Test
    fun `an unknown section is null`() {
        assertEquals(null, CarMarkdown.section(help, "about"))
    }
}
