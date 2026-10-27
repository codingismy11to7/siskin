package com.cappielloantonio.tempo.service

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.Preferences

/**
 * Calls [onChanged] when the saved browse tab order has changed, once writes
 * to it have settled for [DEBOUNCE_MS].
 *
 * Watches the preference rather than its writers, so a new writer cannot
 * forget to tell the car. Debounced because the reorder screen writes once
 * per adjacent swap during a drag. See
 * docs/decisions/2026-10-26-tab-order-invalidation-design.md.
 */
class BrowseTabOrderWatcher(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onChanged: () -> Unit,
) {
    // What this watcher last reported, not necessarily what the car shows:
    // invalidateTree() and a reconnect also send the current order without
    // passing through here, so at worst a change is reported twice.
    private var lastReported: List<String> = emptyList()

    private val check =
        Runnable {
            val saved = resolvedOrder()
            if (saved == lastReported) return@Runnable
            lastReported = saved
            onChanged()
        }

    // A field rather than a lambda at the register call: SharedPreferences
    // holds its listeners weakly, and an unreferenced one is collected and
    // goes silent. A null key is Editor.clear(), which may have removed the
    // order too.
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key != Preferences.BROWSE_TAB_ORDER) return@OnSharedPreferenceChangeListener
            handler.removeCallbacks(check)
            handler.postDelayed(check, DEBOUNCE_MS)
        }

    fun start() {
        lastReported = resolvedOrder()
        App.getInstance().preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun stop() {
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(listener)
        handler.removeCallbacks(check)
    }

    private fun resolvedOrder() = BrowseTabOrder.resolve(Preferences.getBrowseTabOrder())

    companion object {
        const val DEBOUNCE_MS = 500L
    }
}
