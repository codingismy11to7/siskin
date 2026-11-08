package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.databinding.FragmentDebugChangelogBinding

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
        bind.changelogText.showMarkdown(ChangelogMarkdown.parse(markdown))
        return bind.root
    }

    companion object {
        const val ASSET = "changelog.md"
    }
}
