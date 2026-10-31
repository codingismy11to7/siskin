package com.cappielloantonio.tempo.ui.fragment

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentDebugVoiceBinding
import com.cappielloantonio.tempo.service.VoiceLogEntry
import com.cappielloantonio.tempo.service.VoiceRequestLog
import java.util.Date

/**
 * The debug screen's Voice tab: the requests that reached Siskin, which is what
 * tells "Assistant never asked" from "it asked and failed". See
 * docs/decisions/2026-10-31-assistant-voice-requests-design.md.
 */
class DebugVoiceFragment : Fragment() {
    private var bind: FragmentDebugVoiceBinding? = null
    private var voiceWatch: OnSharedPreferenceChangeListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugVoiceBinding.inflate(inflater, container, false)
        this.bind = bind
        bind.clearButton.setOnClickListener {
            VoiceRequestLog.clear()
            renderEntries()
        }
        return bind.root
    }

    /** As in DebugFailuresFragment: the shell hides and shows tabs rather than recreating them. */
    override fun onStart() {
        super.onStart()
        voiceWatch = VoiceRequestLog.watch(::renderEntries)
        renderEntries()
    }

    override fun onStop() {
        voiceWatch?.let(VoiceRequestLog::unwatch)
        voiceWatch = null
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) renderEntries()
    }

    override fun onDestroyView() {
        bind = null
        super.onDestroyView()
    }

    private fun renderEntries() {
        val bind = bind ?: return
        val entries = VoiceRequestLog.all()
        bind.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        bind.clearButton.isEnabled = entries.isNotEmpty()
        val time = DateFormat.getTimeFormat(requireContext())
        val date = DateFormat.getMediumDateFormat(requireContext())
        val labels =
            RowLabels(
                emptyQuery = getString(R.string.debug_voice_no_query),
                noFocus = getString(R.string.debug_voice_no_focus),
                pending = getString(R.string.debug_voice_pending),
            )
        bind.entryContainer.removeAllViews()
        buildRows(entries, labels) { at -> "${date.format(Date(at))} ${time.format(Date(at))}" }
            .forEach { bind.entryContainer.addView(rowView(it)) }
    }

    private fun rowView(row: VoiceRow): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (ROW_PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(0, padding, 0, padding)
            addView(text(row.title, com.google.android.material.R.attr.textAppearanceTitleMedium))
            addView(text(row.subtitle, com.google.android.material.R.attr.textAppearanceBodyLarge))
            addView(text(row.detail, com.google.android.material.R.attr.textAppearanceBodyMedium))
        }

    private fun text(
        value: String,
        appearanceAttr: Int,
    ): TextView =
        TextView(requireContext()).apply {
            text = value
            val resolved = TypedValue()
            context.theme.resolveAttribute(appearanceAttr, resolved, true)
            setTextAppearance(resolved.resourceId)
        }

    companion object {
        private const val ROW_PADDING_DP = 8
        private const val SEPARATOR = " · "

        @VisibleForTesting
        internal data class RowLabels(
            val emptyQuery: String,
            val noFocus: String,
            val pending: String,
        )

        @VisibleForTesting
        internal data class VoiceRow(
            val title: String,
            val subtitle: String,
            val detail: String,
        )

        /** Framework-free so it can be asserted without a view. */
        @VisibleForTesting
        internal fun buildRows(
            entries: List<VoiceLogEntry>,
            labels: RowLabels,
            formatTime: (Long) -> String,
        ): List<VoiceRow> =
            entries.map { entry ->
                VoiceRow(
                    title = entry.query.ifEmpty { labels.emptyQuery },
                    subtitle = entry.outcome ?: labels.pending,
                    detail =
                        listOfNotNull(
                            entry.focus ?: labels.noFocus,
                            entry.artist?.let { "artist=$it" },
                            entry.album?.let { "album=$it" },
                            entry.title?.let { "title=$it" },
                            formatTime(entry.atMillis),
                        ).joinToString(SEPARATOR),
                )
            }
    }
}
