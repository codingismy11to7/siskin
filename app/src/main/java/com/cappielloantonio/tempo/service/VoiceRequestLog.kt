package com.cappielloantonio.tempo.service

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.annotation.Keep
import androidx.core.content.edit
import com.cappielloantonio.tempo.App
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken

@Keep
data class VoiceLogEntry(
    val query: String,
    val focus: String?,
    val outcome: String?,
    val atMillis: Long,
    val artist: String?,
    val album: String?,
    val title: String?,
)

/**
 * Recent voice requests for the debug screen's Voice tab. Written on arrival,
 * so a tab with no entry means the request never reached Siskin. See
 * docs/decisions/2026-10-31-assistant-voice-requests-design.md.
 */
object VoiceRequestLog {
    const val MAX = 20
    private const val KEY = "voice_requests"

    private val gson = Gson()
    private val listType = object : TypeToken<List<VoiceLogEntry>>() {}.type

    @Synchronized
    fun add(entry: VoiceLogEntry) = write((listOf(entry) + all()).take(MAX))

    @Synchronized
    fun finish(
        atMillis: Long,
        outcome: String,
    ) = write(all().map { if (it.atMillis == atMillis) it.copy(outcome = outcome) else it })

    fun all(): List<VoiceLogEntry> {
        val json = App.getInstance().preferences.getString(KEY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<VoiceLogEntry>>(json, listType) ?: emptyList()
        } catch (_: JsonParseException) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        App.getInstance().preferences.edit { remove(KEY) }
    }

    fun watch(onChange: () -> Unit): OnSharedPreferenceChangeListener =
        OnSharedPreferenceChangeListener { _, key -> if (key == KEY) onChange() }
            .also(App.getInstance().preferences::registerOnSharedPreferenceChangeListener)

    fun unwatch(listener: OnSharedPreferenceChangeListener) {
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun write(entries: List<VoiceLogEntry>) {
        App.getInstance().preferences.edit { putString(KEY, gson.toJson(entries)) }
    }
}
