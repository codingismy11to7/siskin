package com.cappielloantonio.tempo.ui.fragment

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.R

/**
 * The debug screen's tabs, in rail order. Adding one is a fragment, an icon, an
 * entry here and a non-translatable `debug_` label string; [CarDebugFragment] builds
 * the rail from [entries] and needs nothing else.
 */
internal enum class DebugTab(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val create: () -> Fragment,
) {
    SERVER(R.string.debug_tab_server, R.drawable.ic_browse_server, ::DebugServerFragment),
    CAR(R.string.debug_tab_car, R.drawable.ic_debug_car, ::DebugCarFragment),
}
