package com.cappielloantonio.tempo.ui.fragment

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.databinding.FragmentDebugChangelogBinding
import com.google.android.material.color.MaterialColors

/**
 * The debug screen's Changelog tab: Siskin's own sections of CHANGELOG.md,
 * bundled by the trimChangelog task in app/build.gradle. See
 * docs/decisions/2026-10-25-changelog-tab-design.md.
 */
class DebugChangelogFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugChangelogBinding.inflate(inflater, container, false)
        val markdown =
            requireContext()
                .assets
                .open(ASSET)
                .bufferedReader()
                .use { it.readText() }
        val bulletColor = MaterialColors.getColor(bind.changelogText, com.google.android.material.R.attr.colorOnSurfaceVariant)
        bind.changelogText.text = spans(ChangelogMarkdown.parse(markdown), bulletColor)
        return bind.root
    }

    private fun spans(
        blocks: List<ChangelogBlock>,
        bulletColor: Int,
    ): CharSequence {
        val density = resources.displayMetrics.density
        val out = SpannableStringBuilder()
        blocks.forEachIndexed { i, block ->
            // Half-height blank lines: one between blocks, so a tight list stays
            // tight and a loose one does not sprawl, and two above a heading.
            val gaps =
                when {
                    i == 0 -> 0
                    block is ChangelogBlock.Heading -> 2
                    else -> 1
                }
            repeat(gaps) { out.span("\n", RelativeSizeSpan(0.5f)) }
            val start = out.length
            block.runs.forEach { run ->
                when (run.style) {
                    ChangelogRun.Style.PLAIN -> out.append(run.text)
                    ChangelogRun.Style.BOLD -> out.span(run.text, StyleSpan(Typeface.BOLD))
                    ChangelogRun.Style.ITALIC -> out.span(run.text, StyleSpan(Typeface.ITALIC))
                    ChangelogRun.Style.CODE -> out.span(run.text, TypefaceSpan("monospace"))
                }
            }
            val blockSpans =
                when (block) {
                    is ChangelogBlock.Heading -> listOf(StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.35f))
                    is ChangelogBlock.Bullet -> listOf(BulletSpan((12 * density).toInt(), bulletColor, (3 * density).toInt()))
                    is ChangelogBlock.Paragraph -> emptyList()
                }
            blockSpans.forEach { out.setSpan(it, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            out.append("\n")
        }
        return out
    }

    private fun SpannableStringBuilder.span(
        text: String,
        what: Any,
    ) {
        val start = length
        append(text)
        setSpan(what, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    companion object {
        const val ASSET = "changelog.md"
    }
}
