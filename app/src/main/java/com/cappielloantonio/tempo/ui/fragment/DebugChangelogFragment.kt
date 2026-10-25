package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.italic
import androidx.core.text.scale
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.databinding.FragmentDebugChangelogBinding
import com.google.android.material.color.MaterialColors

/**
 * The debug screen's Changes tab: Siskin's own sections of CHANGELOG.md,
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
        return buildSpannedString {
            blocks.forEachIndexed { i, block ->
                if (i > 0) {
                    append("\n")
                    // Half-height blank lines: one between blocks, so a tight
                    // list stays tight and a loose one does not sprawl, and two
                    // above a heading.
                    repeat(if (block is ChangelogBlock.Heading) 2 else 1) { scale(0.5f) { append("\n") } }
                }
                when (block) {
                    is ChangelogBlock.Heading -> {
                        bold { scale(1.35f) { runs(block.runs) } }
                    }

                    is ChangelogBlock.Bullet -> {
                        inSpans(BulletSpan((12 * density).toInt(), bulletColor, (3 * density).toInt())) { runs(block.runs) }
                    }

                    is ChangelogBlock.Paragraph -> {
                        runs(block.runs)
                    }
                }
            }
        }
    }

    private fun SpannableStringBuilder.runs(runs: List<ChangelogRun>) =
        runs.forEach { run ->
            when (run.style) {
                ChangelogRun.Style.PLAIN -> append(run.text)
                ChangelogRun.Style.BOLD -> bold { append(run.text) }
                ChangelogRun.Style.ITALIC -> italic { append(run.text) }
                ChangelogRun.Style.CODE -> inSpans(TypefaceSpan("monospace")) { append(run.text) }
            }
        }

    companion object {
        const val ASSET = "changelog.md"
    }
}
