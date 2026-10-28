package com.cappielloantonio.tempo.ui.fragment

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentDebugFailuresBinding
import com.cappielloantonio.tempo.service.FailedTrack
import com.cappielloantonio.tempo.service.FailedTrackLog
import com.cappielloantonio.tempo.service.PlaybackFailurePolicy
import com.cappielloantonio.tempo.util.Preferences
import com.google.android.material.button.MaterialButton
import java.util.Date

/**
 * The debug screen's Failures tab: how many track failures in a row stop
 * playback, and the tracks that failed recently. See
 * docs/decisions/2026-10-27-skip-dead-tracks-design.md.
 */
class DebugFailuresFragment : Fragment() {
    private var bind: FragmentDebugFailuresBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugFailuresBinding.inflate(inflater, container, false)
        this.bind = bind
        buildLimitGroup(bind)
        bind.clearButton.setOnClickListener {
            FailedTrackLog.clear()
            renderEntries()
        }
        return bind.root
    }

    private var failuresWatch: OnSharedPreferenceChangeListener? = null

    /**
     * Rendered on every showing, because the shell hides and shows this tab
     * rather than recreating it, and on every write while visible, because
     * playback carries on behind the debug screen.
     */
    override fun onStart() {
        super.onStart()
        failuresWatch = FailedTrackLog.watch(::renderEntries)
        renderEntries()
    }

    override fun onStop() {
        failuresWatch?.let(FailedTrackLog::unwatch)
        failuresWatch = null
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

    private fun buildLimitGroup(bind: FragmentDebugFailuresBinding) {
        val group = bind.limitGroup
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val current = Preferences.getFailureLimit()
        for (limit in Preferences.FAILURE_LIMIT_OPTIONS) {
            val button =
                MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = limitLabel(limit, getString(R.string.debug_failures_never))
                    minWidth = resources.getDimensionPixelSize(R.dimen.plex_sign_in_choice_min_height)
                    minHeight = resources.getDimensionPixelSize(R.dimen.plex_sign_in_choice_min_height)
                    // As in addChoice: the default 6dp insets would shave 12dp off that.
                    insetTop = 0
                    insetBottom = 0
                    tag = limit
                }
            group.addView(button)
            if (limit == current) group.check(button.id)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) Preferences.setFailureLimit(group.findViewById<View>(checkedId).tag as Int)
        }
    }

    private fun renderEntries() {
        val bind = bind ?: return
        val entries = FailedTrackLog.all()
        bind.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        bind.clearButton.isEnabled = entries.isNotEmpty()
        val container = bind.entryContainer
        container.removeAllViews()
        val format = DateFormat.getTimeFormat(requireContext())
        val dateFormat = DateFormat.getMediumDateFormat(requireContext())
        val rows =
            buildRows(
                entries,
                RowLabels(
                    unknown = getString(R.string.debug_failures_unknown),
                    skipped = getString(R.string.debug_failures_skipped),
                    stopped = getString(R.string.debug_failures_stopped),
                ),
            ) { at -> "${dateFormat.format(Date(at))} ${format.format(Date(at))}" }
        for (row in rows) {
            container.addView(rowView(row))
        }
    }

    private fun rowView(row: FailureRow): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ROW_PADDING_DP.dp(), 0, ROW_PADDING_DP.dp())
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
            val resolved = android.util.TypedValue()
            context.theme.resolveAttribute(appearanceAttr, resolved, true)
            setTextAppearance(resolved.resourceId)
        }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val ROW_PADDING_DP = 8
        private const val SEPARATOR = " · "

        @VisibleForTesting
        internal data class RowLabels(
            val unknown: String,
            val skipped: String,
            val stopped: String,
        )

        @VisibleForTesting
        internal data class FailureRow(
            val title: String,
            val subtitle: String,
            val detail: String,
        )

        @VisibleForTesting
        internal fun limitLabel(
            limit: Int,
            never: String,
        ): String = if (limit == PlaybackFailurePolicy.NEVER) never else limit.toString()

        /** Framework-free so it can be asserted without a view. */
        @VisibleForTesting
        internal fun buildRows(
            entries: List<FailedTrack>,
            labels: RowLabels,
            formatTime: (Long) -> String,
        ): List<FailureRow> =
            entries.map { entry ->
                FailureRow(
                    title = entry.title ?: labels.unknown,
                    subtitle = listOfNotNull(entry.artist, entry.album).joinToString(SEPARATOR).ifEmpty { labels.unknown },
                    detail =
                        listOf(
                            entry.reason,
                            if (entry.skipped) labels.skipped else labels.stopped,
                            formatTime(entry.atMillis),
                        ).joinToString(SEPARATOR),
                )
            }
    }
}
