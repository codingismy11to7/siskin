package com.cappielloantonio.tempo.ui.fragment

import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.italic
import androidx.core.text.scale
import com.google.android.material.color.MaterialColors

/** Shows [CarMarkdown] blocks, with bullets in the theme's colorOnSurfaceVariant. */
internal fun TextView.showMarkdown(blocks: List<MarkdownBlock>) {
    val bulletColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    text = markdownSpans(blocks, bulletColor, resources.displayMetrics.density)
}

/** Draws [CarMarkdown] blocks for a TextView. [bulletColor] comes from the theme, so it follows day and night. */
internal fun markdownSpans(
    blocks: List<MarkdownBlock>,
    bulletColor: Int,
    density: Float,
): CharSequence =
    buildSpannedString {
        blocks.forEachIndexed { i, block ->
            if (i > 0) {
                append("\n")
                // Half-height blank lines: one between blocks, so a tight
                // list stays tight and a loose one does not sprawl, and two
                // above a heading.
                repeat(if (block is MarkdownBlock.Heading) 2 else 1) { scale(0.5f) { append("\n") } }
            }
            when (block) {
                is MarkdownBlock.Heading -> {
                    bold { scale(if (block.level == 2) 1.35f else 1.15f) { runs(block.runs) } }
                }

                is MarkdownBlock.Bullet -> {
                    inSpans(BulletSpan((12 * density).toInt(), bulletColor, (3 * density).toInt())) { runs(block.runs) }
                }

                is MarkdownBlock.Paragraph -> {
                    runs(block.runs)
                }
            }
        }
    }

private fun SpannableStringBuilder.runs(runs: List<MarkdownRun>) =
    runs.forEach { run ->
        when (run.style) {
            MarkdownRun.Style.PLAIN -> append(run.text)
            MarkdownRun.Style.BOLD -> bold { append(run.text) }
            MarkdownRun.Style.ITALIC -> italic { append(run.text) }
            MarkdownRun.Style.CODE -> inSpans(TypefaceSpan("monospace")) { append(run.text) }
        }
    }
