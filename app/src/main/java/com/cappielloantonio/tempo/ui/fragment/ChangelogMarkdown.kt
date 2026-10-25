package com.cappielloantonio.tempo.ui.fragment

internal data class ChangelogRun(
    val text: String,
    val style: Style,
) {
    enum class Style { PLAIN, BOLD, ITALIC, CODE }
}

internal sealed interface ChangelogBlock {
    val runs: List<ChangelogRun>

    data class Heading(
        override val runs: List<ChangelogRun>,
    ) : ChangelogBlock

    data class Bullet(
        override val runs: List<ChangelogRun>,
    ) : ChangelogBlock

    data class Paragraph(
        override val runs: List<ChangelogRun>,
    ) : ChangelogBlock
}

/**
 * Reads the markdown `CHANGELOG.md` is written in -- `##` headings, `- `
 * bullets, paragraphs, bold, italic, code and links -- and nothing more.
 * Anything else comes through as text rather than failing. See
 * docs/decisions/2026-10-25-changelog-tab-design.md.
 */
internal object ChangelogMarkdown {
    private val inline = Regex("""\*\*(.+?)\*\*|\*([^*\s][^*]*?)\*|`([^`]+)`|\[([^\]]+)]\([^)]*\)""")
    private val bareBrackets = Regex("""\[([^\]]+)](?!\()""")

    fun parse(markdown: String): List<ChangelogBlock> {
        val blocks = mutableListOf<ChangelogBlock>()
        var make: ((List<ChangelogRun>) -> ChangelogBlock)? = null
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

                line.startsWith("## ") -> {
                    flush()
                    make = ChangelogBlock::Heading
                    text.append(line.removePrefix("## ").replace(bareBrackets, "$1"))
                }

                line.startsWith("- ") -> {
                    flush()
                    make = ChangelogBlock::Bullet
                    text.append(line.removePrefix("- "))
                }

                make != null -> {
                    text.append(' ').append(line.trim())
                }

                else -> {
                    make = ChangelogBlock::Paragraph
                    text.append(line.trim())
                }
            }
        }
        flush()

        // A release build's [Unreleased] is always an empty heading: the cut
        // leaves it in place, and the cut's own merge is what publishes.
        return blocks.filterIndexed { i, block ->
            block !is ChangelogBlock.Heading || blocks.getOrNull(i + 1).let { it != null && it !is ChangelogBlock.Heading }
        }
    }

    private fun runs(text: String): List<ChangelogRun> =
        styled(text, ChangelogRun.Style.PLAIN).fold(mutableListOf()) { merged, run ->
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
        outer: ChangelogRun.Style,
    ): List<ChangelogRun> {
        val out = mutableListOf<ChangelogRun>()
        var at = 0
        for (match in inline.findAll(text)) {
            if (match.range.first > at) out += ChangelogRun(text.substring(at, match.range.first), outer)
            val (bold, italic, code, link) = match.destructured
            out +=
                when {
                    bold.isNotEmpty() -> styled(bold, ChangelogRun.Style.BOLD)
                    italic.isNotEmpty() -> styled(italic, ChangelogRun.Style.ITALIC)
                    code.isNotEmpty() -> listOf(ChangelogRun(code, ChangelogRun.Style.CODE))
                    else -> styled(link, outer)
                }
            at = match.range.last + 1
        }
        if (at < text.length) out += ChangelogRun(text.substring(at), outer)
        return out
    }
}
