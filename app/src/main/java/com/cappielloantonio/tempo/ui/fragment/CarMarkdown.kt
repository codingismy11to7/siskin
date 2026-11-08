package com.cappielloantonio.tempo.ui.fragment

internal data class MarkdownRun(
    val text: String,
    val style: Style,
) {
    enum class Style { PLAIN, BOLD, ITALIC, CODE }
}

internal sealed interface MarkdownBlock {
    val runs: List<MarkdownRun>

    data class Heading(
        val level: Int,
        override val runs: List<MarkdownRun>,
    ) : MarkdownBlock

    data class Bullet(
        override val runs: List<MarkdownRun>,
    ) : MarkdownBlock

    data class Paragraph(
        override val runs: List<MarkdownRun>,
    ) : MarkdownBlock
}

/**
 * Reads the markdown `CHANGELOG.md` and `docs/web/settings-help.md` are written
 * in -- `##` and `###` headings, `- ` bullets, paragraphs, bold, italic, code
 * and links -- and nothing more. Anything else comes through as text rather
 * than failing. Pandoc renders the help file for the site and reads far more,
 * so a construct outside this subset looks right there and wrong here. See
 * docs/decisions/2026-10-25-changelog-tab-design.md and
 * docs/decisions/2026-11-08-settings-help-design.md.
 */
internal object CarMarkdown {
    // Emphasis may hold a whole code span but never a lone backtick, so it
    // cannot run into one; and, as in CommonMark, it neither opens before nor
    // closes after whitespace.
    private val inline =
        Regex(
            """\*\*(?![\s*])((?:`[^`]*`|[^`])+?)(?<!\s)\*\*""" +
                """|\*(?![\s*])((?:`[^`]*`|[^`*])+?)(?<!\s)\*""" +
                """|`([^`]+)`""" +
                """|\[([^\]]+)]\([^)]*\)""",
        )
    private val bareBrackets = Regex("""\[([^\]]+)](?!\()""")

    /** A heading's text without its trailing ` {#id}`, and that id, or the text unchanged and null. */
    private fun anchored(text: String): Pair<String, String?> {
        val trimmed = text.trimEnd()
        val open = trimmed.lastIndexOf(" {#")
        if (!trimmed.endsWith("}") || open < 0) return text to null
        val id = trimmed.substring(open + 3, trimmed.length - 1)
        val valid = id.isNotEmpty() && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
        return if (valid) trimmed.substring(0, open).trimEnd() to id else text to null
    }

    private fun anchorOf(line: String) = anchored(line).second

    fun parse(markdown: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var make: ((List<MarkdownRun>) -> MarkdownBlock)? = null
        val text = StringBuilder()

        fun flush() {
            make?.let { blocks += it(runs(text.toString())) }
            make = null
            text.clear()
        }

        for (line in markdown.lines()) {
            when {
                line.isBlank() -> {
                    flush()
                }

                line.startsWith("### ") -> {
                    flush()
                    blocks += MarkdownBlock.Heading(3, runs(heading(line.removePrefix("### "))))
                }

                line.startsWith("## ") -> {
                    flush()
                    blocks += MarkdownBlock.Heading(2, runs(heading(line.removePrefix("## "))))
                }

                line.startsWith("- ") -> {
                    flush()
                    make = MarkdownBlock::Bullet
                    text.append(line.removePrefix("- "))
                }

                make != null -> {
                    text.append(' ').append(line.trim())
                }

                else -> {
                    make = MarkdownBlock::Paragraph
                    text.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    fun sectionIds(markdown: String): List<String> = markdown.lines().filter { it.startsWith("## ") }.mapNotNull(::anchorOf)

    fun section(
        markdown: String,
        id: String,
    ): String? {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && anchorOf(it) == id }
        if (start < 0) return null
        val end =
            lines
                .withIndex()
                .drop(start + 1)
                .firstOrNull { (_, l) -> l.startsWith("# ") || l.startsWith("## ") }
                ?.index ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n")
    }

    private fun heading(text: String) = anchored(text).first.replace(bareBrackets, "$1")

    private fun runs(text: String): List<MarkdownRun> =
        styled(text, MarkdownRun.Style.PLAIN).fold(mutableListOf()) { merged, run ->
            val last = merged.lastOrNull()
            if (last?.style == run.style) {
                merged[merged.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                merged += run
            }
            merged
        }

    /** Runs inside a marker take its style unless they carry their own. */
    private fun styled(
        text: String,
        outer: MarkdownRun.Style,
    ): List<MarkdownRun> {
        val out = mutableListOf<MarkdownRun>()
        var at = 0
        for (match in inline.findAll(text)) {
            if (match.range.first > at) out += MarkdownRun(text.substring(at, match.range.first), outer)
            val (bold, italic, code, link) = match.destructured
            out +=
                when {
                    bold.isNotEmpty() -> styled(bold, MarkdownRun.Style.BOLD)
                    italic.isNotEmpty() -> styled(italic, MarkdownRun.Style.ITALIC)
                    code.isNotEmpty() -> listOf(MarkdownRun(code, MarkdownRun.Style.CODE))
                    else -> styled(link, outer)
                }
            at = match.range.last + 1
        }
        if (at < text.length) out += MarkdownRun(text.substring(at), outer)
        return out
    }
}

/** The changelog's reading: a release build's [Unreleased] is always an empty heading. */
internal object ChangelogMarkdown {
    fun parse(markdown: String): List<MarkdownBlock> {
        val blocks = CarMarkdown.parse(markdown)
        // The cut leaves [Unreleased] in place, and the cut's own merge is what publishes.
        // A subheading belongs to the heading above it, so only a heading at
        // the same level or higher, or the end, leaves one empty.
        return blocks.filterIndexed { i, block ->
            val next = blocks.getOrNull(i + 1)
            block !is MarkdownBlock.Heading ||
                (next != null && (next !is MarkdownBlock.Heading || next.level > block.level))
        }
    }
}
