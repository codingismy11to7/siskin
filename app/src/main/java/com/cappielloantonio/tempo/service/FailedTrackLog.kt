package com.cappielloantonio.tempo.service

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.annotation.Keep
import androidx.core.content.edit
import com.cappielloantonio.tempo.App
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken

@Keep
data class FailedTrack(
    val ratingKey: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val reason: String,
    val skipped: Boolean,
    val atMillis: Long,
    val server: String?,
)

/**
 * The most recent track failures, for the debug screen's Failures tab. See
 * docs/decisions/2026-10-27-skip-dead-tracks-design.md.
 */
object FailedTrackLog {
    const val MAX = 20
    internal const val KEY = "failed_tracks"

    /**
     * Failures ever added, and how many of those had been added the last time
     * the tab was open. Counted rather than compared by [FailedTrack.atMillis]
     * because a head unit can boot with its clock behind, and a failure stamped
     * earlier than one already seen would never show as new.
     */
    private const val ADDED_KEY = "failed_tracks_added"
    private const val SEEN_KEY = "failed_tracks_seen"

    private val gson = Gson()
    private val listType = object : TypeToken<List<FailedTrack>>() {}.type

    fun add(track: FailedTrack) {
        val prefs = App.getInstance().preferences
        val next = (listOf(track) + all()).take(MAX)
        prefs.edit {
            putString(KEY, gson.toJson(next))
            putInt(ADDED_KEY, prefs.getInt(ADDED_KEY, 0) + 1)
        }
    }

    fun all(): List<FailedTrack> {
        val json = App.getInstance().preferences.getString(KEY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<FailedTrack>>(json, listType) ?: emptyList()
        } catch (_: JsonParseException) {
            emptyList()
        }
    }

    /** Failures added since the last [markSeen], for the rail's badge. */
    fun unseenCount(): Int {
        val prefs = App.getInstance().preferences
        return (prefs.getInt(ADDED_KEY, 0) - prefs.getInt(SEEN_KEY, 0)).coerceIn(0, MAX)
    }

    fun markSeen() {
        val prefs = App.getInstance().preferences
        prefs.edit { putInt(SEEN_KEY, prefs.getInt(ADDED_KEY, 0)) }
    }

    fun clear() {
        App.getInstance().preferences.edit {
            remove(KEY)
            remove(ADDED_KEY)
            remove(SEEN_KEY)
        }
    }

    /**
     * Calls [onChange] whenever an entry is added or the log is cleared, for a
     * screen that shows it while playback carries on. Keep the returned
     * listener and pass it to [unwatch]: SharedPreferences holds it weakly.
     */
    fun watch(onChange: () -> Unit): OnSharedPreferenceChangeListener =
        OnSharedPreferenceChangeListener { _, key -> if (key == KEY) onChange() }
            .also(App.getInstance().preferences::registerOnSharedPreferenceChangeListener)

    fun unwatch(listener: OnSharedPreferenceChangeListener) {
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
