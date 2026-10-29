package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentDebugServerBinding
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.api.server.ServerProbe
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import java.util.Date

/**
 * The debug screen's Server tab: which server this is, every address it
 * advertises with what kind it is and whether it answers, and one address's
 * detail in place of the list.
 *
 * Opening it fetches from plex.tv and probes every address; the stored list
 * draws first and each answer fills in as it lands. It can move the session to
 * another address of this server, never to another server -- Choose server
 * goes through the sign-in flow's picker for that.
 *
 * See docs/decisions/2026-10-27-debug-server-tab-design.md.
 */
class DebugServerFragment : Fragment() {
    private var bind: FragmentDebugServerBinding? = null

    private val book get() = ServerAddressBook.shared
    private val source: DebugServerSource get() = sourceForTest ?: LiveDebugServerSource

    /** plex.tv's answer this visit; meaningful only once [plexTvSettled]. */
    private var resource: Resource? = null
    private var plexTvSettled = false
    private var libraryName: String? = null
    private val checks = mutableMapOf<String, ServerProbe.Check>()
    private val checking = mutableSetOf<String>()
    private var checkedAt: Long? = null
    private var raceRunning = false

    /** The previous re-probe's result, reported because a win by the same address looks like nothing. */
    private var outcome: String? = null

    /** The address whose detail is showing, or null on the list. */
    private var detailUri: String? = null

    /**
     * The inflated rows, keyed by URI, and the order they were built in. A
     * redraw rebinds them in place, because re-inflating on every probe result
     * drops rotary focus and any touch in progress.
     */
    private val rowViews = mutableMapOf<String, View>()
    private var rowOrder: List<String>? = null

    /**
     * Live only while a detail shows on a visible tab. Hidden, the tab must not
     * hold back: one press leaves the debug screen from any tab
     * (2026-10-25-debug-tabs-design.md), and a detail left open behind the Car
     * tab would otherwise eat it.
     */
    private val backToList =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showList()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugServerBinding.inflate(inflater, container, false)
        this.bind = bind
        bind.reprobe.setOnClickListener { reprobe() }
        bind.chooseServer.setOnClickListener { chooseServer() }
        bind.detailBack.setOnClickListener { showList() }
        bind.detailProbe.setOnClickListener { detailUri?.let(::checkOne) }
        bind.detailUse.setOnClickListener { detailUri?.let(::useAddress) }
        return bind.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToList)
        detailUri = null
        load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        syncBack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bind = null
        rowViews.clear()
        rowOrder = null
    }

    private fun rows(known: ServerAddressBook.KnownAddresses = book.knownAddresses()): List<AddressRow> =
        DebugServerRows.build(known, resource?.connections)

    private fun load() {
        resource = null
        plexTvSettled = false
        libraryName = null
        checks.clear()
        checking.clear()
        checkedAt = null
        outcome = null
        raceRunning = false
        render()

        val scope = viewLifecycleOwner.lifecycleScope
        scope.launch {
            resource = source.refresh()
            plexTvSettled = true
            render()
            // A fresh list can name addresses the stored one did not.
            checkAll()
        }
        scope.launch {
            libraryName = source.libraryName()
            render()
        }
        checkAll()
    }

    private fun checkAll() {
        for (row in rows()) {
            if (row.uri !in checks) checkOne(row.uri)
        }
    }

    private fun checkOne(uri: String) {
        if (uri in checking) return
        checking += uri
        checks -= uri
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            checks[uri] = source.check(uri)
            checking -= uri
            if (checking.isEmpty()) checkedAt = System.currentTimeMillis()
            render()
        }
    }

    /**
     * Re-races the known addresses and redraws with what happened.
     *
     * The outcome has to be reported: when the same address wins again the
     * adopt is a no-op and the list redraws identically, so a silent row reads
     * as broken exactly when it is working.
     *
     * force = true because the cooldown is aimed at automatic callers -- an
     * offline car paying a full race per browse tab -- and a parked human
     * pressing this once is neither, and is most likely to press it while the
     * cooldown is armed.
     */
    private fun reprobe() {
        val before = book.current()
        if (before == null) {
            // The session cleared out from under an open screen -- sign out is
            // one back press away. Reporting the same wording reprobe() uses
            // for "nothing answered" at least shows the press was seen.
            outcome = getString(R.string.debug_reprobe_failed)
            render()
            return
        }

        raceRunning = true
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            val after = book.reprobe(before, force = true)
            outcome =
                when (after) {
                    null -> getString(R.string.debug_reprobe_failed)
                    before -> getString(R.string.debug_reprobe_unchanged, DebugServerRows.hostPort(after))
                    else -> getString(R.string.debug_reprobe_moved, DebugServerRows.hostPort(after))
                }
            raceRunning = false
            checks.clear()
            checkAll()
            render()
        }
    }

    private fun useAddress(uri: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            outcome =
                book.use(uri)?.let { getString(R.string.debug_reprobe_moved, DebugServerRows.hostPort(it)) }
                    ?: getString(R.string.debug_server_use_refused)
            render()
        }
    }

    private fun showDetail(uri: String) {
        detailUri = uri
        render()
    }

    private fun showList() {
        detailUri = null
        render()
    }

    private fun syncBack() {
        backToList.isEnabled = detailUri != null && !isHidden
    }

    private fun render() {
        val bind = bind ?: return
        val session = PlexApi().session
        val rows = rows(book.knownAddresses(session))
        if (detailUri != null && rows.none { it.uri == detailUri }) detailUri = null
        syncBack()

        val detail = rows.firstOrNull { it.uri == detailUri }
        bind.listPane.visibility = if (detail == null) View.VISIBLE else View.GONE
        bind.detailScroll.visibility = if (detail == null) View.GONE else View.VISIBLE

        renderHeader(bind, session)
        renderList(bind, rows)
        if (detail != null) renderDetail(bind, detail)
    }

    private fun renderHeader(
        bind: FragmentDebugServerBinding,
        session: PlexSession?,
    ) {
        val resource = resource
        bind.serverName.text =
            when {
                !plexTvSettled -> getString(R.string.debug_server_loading)
                resource?.name.isNullOrBlank() -> getString(R.string.debug_server_unnamed)
                else -> resource.name
            }
        bind.serverMeta.text =
            listOfNotNull(
                session?.let { getString(if (it.owned) R.string.debug_server_owned else R.string.debug_server_shared) },
                libraryName?.let { getString(R.string.debug_server_library, it) },
                resource?.productVersion?.let { getString(R.string.debug_server_version, it) },
                resource?.platform,
                if (plexTvSettled && resource == null) getString(R.string.debug_server_plex_tv_unreachable) else null,
            ).joinToString(SEPARATOR)
        bind.serverMachine.text = session?.machineIdentifier.orEmpty()
        bind.chooseServer.isEnabled = !raceRunning
    }

    private fun renderList(
        bind: FragmentDebugServerBinding,
        rows: List<AddressRow>,
    ) {
        bind.reprobe.isEnabled = !raceRunning && checking.isEmpty()
        bind.addressStatusLine.text =
            when {
                raceRunning -> {
                    getString(R.string.debug_reprobe_running)
                }

                checking.isNotEmpty() -> {
                    getString(R.string.debug_server_probing)
                }

                outcome != null -> {
                    outcome
                }

                else -> {
                    checkedAt?.let {
                        getString(
                            R.string.debug_server_checked,
                            DateFormat.getTimeFormat(requireContext()).format(Date(it)),
                        )
                    }
                }
            }

        val uris = rows.map { it.uri }
        if (uris != rowOrder) {
            val container = bind.addressRows
            container.removeAllViews()
            rowViews.clear()
            rowOrder = uris
            if (rows.isEmpty()) {
                container.addView(TextView(requireContext()).apply { text = getString(R.string.debug_addresses_none) })
                return
            }
            val inflater = LayoutInflater.from(requireContext())
            for (uri in uris) {
                val view = inflater.inflate(R.layout.item_debug_address, container, false)
                view.setOnClickListener { showDetail(uri) }
                rowViews[uri] = view
                container.addView(view)
            }
        }
        for (row in rows) bindRow(rowViews.getValue(row.uri), row)
    }

    private fun bindRow(
        view: View,
        row: AddressRow,
    ) {
        // The ripple is the layout's android:foreground, so clearing this loses nothing.
        if (row.inUse) view.setBackgroundResource(R.drawable.bg_debug_address_in_use) else view.background = null
        view.findViewById<ImageView>(R.id.address_kind_icon).setImageResource(iconFor(row.kind))
        view.findViewById<TextView>(R.id.address_title).text = labelFor(row.kind)
        view.findViewById<TextView>(R.id.address_subtitle).text = row.hostPort
        view.findViewById<View>(R.id.address_in_use).visibility = if (row.inUse) View.VISIBLE else View.GONE
        renderStatus(
            view.findViewById(R.id.address_status),
            view.findViewById(R.id.address_progress),
            row.uri,
        )
    }

    private fun renderStatus(
        status: TextView,
        progress: CircularProgressIndicator,
        uri: String,
    ) {
        val check = checks[uri]
        progress.visibility = if (uri in checking) View.VISIBLE else View.GONE
        status.visibility = if (uri in checking) View.GONE else View.VISIBLE
        status.text = describe(check, verbose = false)
        status.setTextColor(
            MaterialColors.getColor(
                status,
                if (check is ServerProbe.Check.Failed) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            ),
        )
    }

    private fun renderDetail(
        bind: FragmentDebugServerBinding,
        row: AddressRow,
    ) {
        bind.detailTitle.text = row.hostPort
        bind.detailInUse.visibility = if (row.inUse) View.VISIBLE else View.GONE

        val facts = bind.detailFacts
        facts.removeAllViews()
        facts.addView(
            factRow(
                getString(R.string.debug_server_fact_kind),
                labelFor(row.kind),
                if (row.dockerRange) getString(R.string.debug_server_docker_hint) else null,
            ),
        )
        facts.addView(
            factRow(
                getString(R.string.debug_server_fact_plex_tv),
                row.flags?.let { getString(R.string.debug_server_flags, it.local.toString(), it.relay.toString()) }
                    ?: getString(R.string.debug_server_flags_unknown),
                null,
            ),
        )
        facts.addView(
            factRow(
                getString(R.string.debug_server_fact_probe),
                if (row.uri in checking) {
                    getString(R.string.debug_server_probing)
                } else {
                    describe(checks[row.uri], verbose = true)
                },
                null,
            ),
        )
        facts.addView(factRow(getString(R.string.debug_server_fact_address), row.uri, null, monospace = true))

        bind.detailProbe.isEnabled = row.uri !in checking
        bind.detailUse.isEnabled = !row.inUse && !raceRunning && checks[row.uri] is ServerProbe.Check.Answered
    }

    /** A probe result as text; [verbose] names how it failed rather than just that it did. */
    private fun describe(
        check: ServerProbe.Check?,
        verbose: Boolean,
    ): String =
        when (check) {
            is ServerProbe.Check.Answered -> getString(R.string.debug_server_latency, check.millis)
            is ServerProbe.Check.Failed -> if (verbose) check.reason else getString(R.string.debug_server_no_answer)
            null -> ""
        }

    private fun factRow(
        label: String,
        value: String,
        note: String?,
        monospace: Boolean = false,
    ): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, FACT_PADDING_DP.dp(), 0, FACT_PADDING_DP.dp())
            addView(
                text(label, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                },
                LinearLayout.LayoutParams(FACT_LABEL_WIDTH_DP.dp(), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        text(value, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
                            if (monospace) typeface = android.graphics.Typeface.MONOSPACE
                        },
                    )
                    note?.let {
                        addView(
                            text(it, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
                                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
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

    private fun labelFor(kind: AddressKind): String =
        getString(
            when (kind) {
                AddressKind.LOCAL -> R.string.debug_server_kind_local
                AddressKind.REMOTE -> R.string.debug_server_kind_remote
                AddressKind.RELAY -> R.string.debug_server_kind_relay
                AddressKind.DIRECT -> R.string.debug_server_kind_direct
            },
        )

    private fun iconFor(kind: AddressKind): Int =
        when (kind) {
            AddressKind.LOCAL -> R.drawable.ic_debug_address_local
            AddressKind.REMOTE -> R.drawable.ic_debug_address_remote
            AddressKind.RELAY -> R.drawable.ic_debug_address_relay
            AddressKind.DIRECT -> R.drawable.ic_debug_address_direct
        }

    /**
     * Opens the sign-in flow's own server picker, without signing in.
     *
     * **Pushed, not routed, and that is the whole design.** Back out of the
     * picker should return here, to the screen it was opened from, and a back
     * stack is precisely the thing that knows how to do that. Pushing also
     * keeps CarHostActivity's router out of the way for free: it declines to
     * act while the back stack is non-empty, and this transaction only makes it
     * emptier by nothing.
     *
     * An earlier draft had the router show the picker instead. That forced this
     * row to pop *itself* first -- destroying the very entry that would have
     * brought the user back -- and then to name a hardcoded destination for
     * back, which could only ever be a state the flow already had. Settings,
     * in practice, which is not where anyone came from.
     *
     * The order below does not matter. The push makes the back stack deeper, so
     * the router is silent either way, and the fragment picks up whatever state
     * is current when it starts observing.
     *
     * Onto the activity's fragment manager, not the parent's child manager:
     * this tab lives inside the debug screen's shell, and the picker has to
     * replace the whole screen so that back returns to it.
     */
    private fun chooseServer() {
        requireActivity()
            .supportFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, PlexSignInFragment.pushed())
            .addToBackStack(null)
            .commit()

        ViewModelProvider(requireActivity())[PlexSignInViewModel::class.java]
            .reopenServerPicker()
    }

    companion object {
        private const val SEPARATOR = " · "
        private const val FACT_PADDING_DP = 12
        private const val FACT_LABEL_WIDTH_DP = 180

        /** Replaces the live plex.tv and probe calls in Robolectric tests. Static, so reset it in @After. */
        @VisibleForTesting
        internal var sourceForTest: DebugServerSource? = null
    }
}
