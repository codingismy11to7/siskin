package com.cappielloantonio.tempo.util

import androidx.media3.common.Player
import com.cappielloantonio.tempo.App

object Preferences {
    const val THEME = "theme"
    private const val PLAYBACK_SPEED = "playback_speed"
    private const val PLAYBACK_SPEED_PITCH = "playback_speed_pitch"
    private const val PLAYBACK_SPEED_MANUAL_PITCH = "playback_speed_manual_pitch"
    private const val PLAYBACK_SPEED_MANUAL_PITCH_VALUE = "playback_speed_manual_pitch_value"
    private const val SHUFFLE_MODE = "shuffle_mode"
    private const val REPEAT_MODE = "repeat_mode"
    private const val IMAGE_CACHE_SIZE = "image_cache_size"
    private const val STREAMING_CACHE_SIZE = "streaming_cache_size"
    private const val IMAGE_SIZE = "image_size"
    private const val DATA_SAVING_MODE = "data_saving_mode"
    private const val REPLAY_GAIN_MODE = "replay_gain_mode"
    private const val REPLAY_GAIN_PREVENT_CLIPPING = "replay_gain_prevent_clipping"
    private const val LOUDNESS_PREAMP = "loudness_preamp"
    private const val STREAMING_CACHE_STORAGE = "streaming_cache_storage"
    private const val SCROBBLING = "scrobbling"
    private const val SONG_PRELOAD_BUFFER = "song_preload_buffer"
    private const val PRECACHE_TRACKS_COUNT = "precache_tracks_count"
    private const val PRECACHE_WIFI_ONLY = "precache_wifi_only"
    private const val CONTINUOUS_PLAY = "continuous_play"
    private const val ARTISTS_BY_INITIAL = "artists_by_initial"
    private const val NUMBER_TRACKS_KEEP_IN_QUEUE = "number_tracks_keep_in_queue"
    private const val FALLBACK_TO_RANDOM_TRACKS = "fallback_to_random_tracks"
    private const val LAST_INSTANT_MIX = "last_instant_mix"
    private const val SELECTED_EQUALIZER = "selected_equalizer"
    private const val EQUALIZER_ENABLED = "equalizer_enabled"
    private const val EQUALIZER_BAND_LEVELS = "equalizer_band_levels"

    private const val DARK_THEME_STYLE = "dark_theme_style"

    @JvmStatic
    fun getPlaybackSpeed(): Float {
        return App.getInstance().preferences.getFloat(PLAYBACK_SPEED, 1f)
    }
    @JvmStatic
    fun isPlaybackSpeedPitchEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(PLAYBACK_SPEED_PITCH, false)
    }
    @JvmStatic
    fun isPlaybackSpeedManualPitchEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(PLAYBACK_SPEED_MANUAL_PITCH, false)
    }
    @JvmStatic
    fun getPlaybackSpeedManualPitch(): Float {
        return App.getInstance().preferences.getFloat(PLAYBACK_SPEED_MANUAL_PITCH_VALUE, 1f)
    }
    @JvmStatic
    fun isShuffleModeEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SHUFFLE_MODE, false)
    }

    @JvmStatic
    fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        App.getInstance().preferences.edit().putBoolean(SHUFFLE_MODE, shuffleModeEnabled).apply()
    }

    @JvmStatic
    fun getRepeatMode(): Int {
        return App.getInstance().preferences.getInt(REPEAT_MODE, Player.REPEAT_MODE_OFF)
    }

    @JvmStatic
    fun setRepeatMode(repeatMode: Int) {
        App.getInstance().preferences.edit().putInt(REPEAT_MODE, repeatMode).apply()
    }

    @JvmStatic
    fun getImageCacheSize(): Int {
        return App.getInstance().preferences.getString(IMAGE_CACHE_SIZE, "500")!!.toInt()
    }
    @JvmStatic
    fun getImageSize(): Int {
        return App.getInstance().preferences.getString(IMAGE_SIZE, "-1")!!.toInt()
    }

    @JvmStatic
    fun getStreamingCacheSize(): Long {
        return App.getInstance().preferences.getString(STREAMING_CACHE_SIZE, "256")!!.toLong()
    }

    @JvmStatic
    fun isDataSavingMode(): Boolean {
        return App.getInstance().preferences.getBoolean(DATA_SAVING_MODE, false)
    }

    /**
     * Four-way: "disabled", "track", "album", "auto". [ReplayGainUtil] switches on
     * these exact strings and this returns them unchanged.
     *
     * "disabled" was the permanent value rather than a default until
     * [setReplayGainEnabled] existed, so the whole gain pipeline -- an audio
     * processor installed unconditionally in the sink, and five call sites --
     * never did anything.
     */
    @JvmStatic
    fun getReplayGainMode(): String? {
        return App.getInstance().preferences.getString(REPLAY_GAIN_MODE, "disabled")
    }

    /**
     * The boolean view of the four-way mode, for the Settings row.
     *
     * Any mode but "disabled" reads as on, so a "track" or "album" written by a
     * future chooser -- or by hand -- is not silently reported as off by a screen
     * that cannot yet express it.
     */
    @JvmStatic
    fun isReplayGainEnabled(): Boolean {
        return getReplayGainMode() != "disabled"
    }

    /**
     * On means "auto": album gain when the adjacent track shares an album title,
     * track gain otherwise. That is a superset of the other two modes -- album
     * behaviour inside an album, track behaviour across a shuffle -- which is why
     * one switch can stand in for the enum without lying about it.
     */
    @JvmStatic
    fun setReplayGainEnabled(enabled: Boolean) {
        App.getInstance().preferences.edit()
            .putString(REPLAY_GAIN_MODE, if (enabled) "auto" else "disabled")
            .apply()
    }

    /**
     * Defaults to true. Nothing writes this key -- the Settings screen behind
     * the car's gear offers no row for it -- so the default is the effective
     * value. Now that [setReplayGainEnabled] gives the mode above a writer,
     * this key's writer-less status is the exception on this screen rather
     * than the rule.
     */
    @JvmStatic
    fun isReplayGainPreventClipping(): Boolean {
        return App.getInstance().preferences.getBoolean(REPLAY_GAIN_PREVENT_CLIPPING, true)
    }

    /**
     * Defaults to 0 dB. Nothing writes this key either, same as
     * [isReplayGainPreventClipping] just above -- no row, so the default is
     * the effective value.
     */
    @JvmStatic
    fun getLoudnessPreamp(): Float {
        return App.getInstance().preferences.getInt(LOUDNESS_PREAMP, 0).toFloat()
    }

    @JvmStatic
    fun getStreamingCacheStoragePreference(): Int {
        return App.getInstance().preferences.getString(STREAMING_CACHE_STORAGE, "0")!!.toInt()
    }

    @JvmStatic
    fun setStreamingCacheStoragePreference(streamingCachePreference: Int) {
        return App.getInstance().preferences.edit().putString(
                STREAMING_CACHE_STORAGE,
                streamingCachePreference.toString()
        ).apply()
    }

    @JvmStatic
    fun isScrobblingEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SCROBBLING, true)
    }

    @JvmStatic
    fun getSongPreloadBuffer(): Int {
        return App.getInstance().preferences.getString(SONG_PRELOAD_BUFFER, "60")!!.toInt()
    }

    @JvmStatic
    fun getPrecacheTracksCount(): Int {
        return App.getInstance().preferences.getString(PRECACHE_TRACKS_COUNT, "0")!!.toInt()
    }

    @JvmStatic
    fun isPrecacheWifiOnly(): Boolean {
        return App.getInstance().preferences.getBoolean(PRECACHE_WIFI_ONLY, true)
    }

    /**
     * Off unless asked for, and #72 is why. There was no writer and no settings
     * surface, so `true` was not a default but the value: reaching the end of a
     * queue is not a request for more music, and an eight-track album quietly
     * became a ~56-track queue. There is a writer now -- the Settings toggle
     * behind the car's gear -- so this is a default again rather than a verdict.
     *
     * [isFallbackToRandomTracksEnabled] below carries the opposite note about a
     * neighbouring key and still means it: that one has no writer.
     */
    @JvmStatic
    fun isContinuousPlayEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(CONTINUOUS_PLAY, false)
    }

    @JvmStatic
    fun setContinuousPlayEnabled(enabled: Boolean) {
        App.getInstance().preferences.edit().putBoolean(CONTINUOUS_PLAY, enabled).apply()
    }

    /**
     * Whether the Artists tab groups into Plex's first-character buckets --
     * true, the default -- or into #87's offset windows.
     *
     * Defaults to true rather than leaving an existing install alone, because
     * there is no such install: #87 was unreleased when this landed, so no user
     * had ever seen a window row. See
     * docs/decisions/2026-08-10-artists-by-initial-design.md.
     */
    @JvmStatic
    fun isArtistsByInitialEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(ARTISTS_BY_INITIAL, true)
    }

    @JvmStatic
    fun setArtistsByInitialEnabled(enabled: Boolean) {
        App.getInstance().preferences.edit().putBoolean(ARTISTS_BY_INITIAL, enabled).apply()
    }

    @JvmStatic
    fun getNumberOfTracksKeepInQueue(): Int {
        return App.getInstance().preferences.getInt(NUMBER_TRACKS_KEEP_IN_QUEUE, 30) - 1
    }

    /**
     * Defaults to true. Nothing writes this key -- the Settings screen behind
     * the car's gear offers no row for it -- so the default is the effective
     * value, and leaving the old `false` meant continuous play could never fall
     * back to random tracks on a library without sonic analysis, silently doing
     * nothing once the similar-tracks tier ran dry. The preference itself is
     * kept for a row that may yet be added; only the default flips.
     */
    @JvmStatic
    fun isFallbackToRandomTracksEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(FALLBACK_TO_RANDOM_TRACKS, true)
    }
    @JvmStatic
    fun setLastInstantMix() {
        App.getInstance().preferences.edit().putLong(LAST_INSTANT_MIX, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isInstantMixUsable(): Boolean {
        return App.getInstance().preferences.getLong(
                LAST_INSTANT_MIX, 0
        ) + 10000 < System.currentTimeMillis()
    }
    @JvmStatic
    fun isEqualizerEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(EQUALIZER_ENABLED, false)
    }

    @JvmStatic
    fun getSelectedEqualizer(): Int {
        return App.getInstance().preferences.getString(SELECTED_EQUALIZER, "0")!!.toInt()
    }
    @JvmStatic
    fun getEqualizerBandLevels(bandCount: Short): ShortArray {
        val str = App.getInstance().preferences.getString(EQUALIZER_BAND_LEVELS, null)
        if (str.isNullOrBlank()) {
            return ShortArray(bandCount.toInt())
        }
        val parts = str.split(",")
        if (parts.size < bandCount) return ShortArray(bandCount.toInt())
        return ShortArray(bandCount.toInt()) { i -> parts[i].toShortOrNull() ?: 0 }
    }
    @JvmStatic
    fun getTheme(): String {
        return App.getInstance().preferences.getString(THEME, "default") ?: "default"
    }
    @JvmStatic
    fun getDarkThemeStyle(): String {
        return App.getInstance().preferences.getString(DARK_THEME_STYLE, "standard") ?: "standard"
    }
}
