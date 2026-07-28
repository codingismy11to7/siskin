package com.cappielloantonio.tempo.util

import androidx.media3.common.Player
import com.cappielloantonio.tempo.App

object Preferences {
    const val THEME = "theme"
    private const val SERVER = "server"
    private const val USER = "user"
    private const val PASSWORD = "password"
    private const val TOKEN = "token"
    private const val SALT = "salt"
    private const val LOW_SECURITY = "low_security"
    private const val CLIENT_CERT = "client_cert"
    private const val SERVER_ID = "server_id"
    private const val LOCAL_ADDRESS = "local_address"
    private const val IN_USE_SERVER_ADDRESS = "in_use_server_address"
    private const val PLAYBACK_SPEED = "playback_speed"
    private const val PLAYBACK_SPEED_PITCH = "playback_speed_pitch"
    private const val PLAYBACK_SPEED_MANUAL_PITCH = "playback_speed_manual_pitch"
    private const val PLAYBACK_SPEED_MANUAL_PITCH_VALUE = "playback_speed_manual_pitch_value"
    private const val SHUFFLE_MODE = "shuffle_mode"
    private const val REPEAT_MODE = "repeat_mode"
    private const val IMAGE_CACHE_SIZE = "image_cache_size"
    private const val STREAMING_CACHE_SIZE = "streaming_cache_size"
    private const val IMAGE_SIZE = "image_size"
    private const val MAX_BITRATE_WIFI = "max_bitrate_wifi"
    private const val MAX_BITRATE_MOBILE = "max_bitrate_mobile"
    private const val AUDIO_TRANSCODE_FORMAT_WIFI = "audio_transcode_format_wifi"
    private const val AUDIO_TRANSCODE_FORMAT_MOBILE = "audio_transcode_format_mobile"
    private const val DATA_SAVING_MODE = "data_saving_mode"
    private const val REPLAY_GAIN_MODE = "replay_gain_mode"
    private const val REPLAY_GAIN_PREVENT_CLIPPING = "replay_gain_prevent_clipping"
    private const val LOUDNESS_PREAMP = "loudness_preamp"
    private const val AUDIO_TRANSCODE_PRIORITY = "audio_transcode_priority"
    private const val STREAMING_CACHE_STORAGE = "streaming_cache_storage"
    private const val DOWNLOAD_STORAGE = "download_storage"
    private const val SCROBBLING = "scrobbling"
    private const val SONG_PRELOAD_BUFFER = "song_preload_buffer"
    private const val PRECACHE_TRACKS_COUNT = "precache_tracks_count"
    private const val PRECACHE_WIFI_ONLY = "precache_wifi_only"
    private const val CONTINUOUS_PLAY = "continuous_play"
    private const val NUMBER_TRACKS_KEEP_IN_QUEUE = "number_tracks_keep_in_queue"
    private const val FALLBACK_TO_RANDOM_TRACKS = "fallback_to_random_tracks"
    private const val LAST_INSTANT_MIX = "last_instant_mix"
    private const val SELECTED_EQUALIZER = "selected_equalizer"
    private const val EQUALIZER_ENABLED = "equalizer_enabled"
    private const val EQUALIZER_BAND_LEVELS = "equalizer_band_levels"
    private const val CUSTOM_COMMAND_FIRST_BUTTON = "custom_command_first_button"
    private const val CUSTOM_COMMAND_SECOND_BUTTON = "custom_command_second_button"
    private const val NETWORK_PING_TIMEOUT = "network_ping_timeout_base"
    
    private const val DARK_THEME_STYLE = "dark_theme_style"
    private const val AA_SHUFFLE_PLAYLISTS = "androidauto_shuffle_playlists"

	@JvmStatic
    fun getServer(): String? {
        return App.getInstance().preferences.getString(SERVER, null)
    }

    @JvmStatic
    fun setServer(server: String?) {
        App.getInstance().preferences.edit().putString(SERVER, server).apply()
    }

    @JvmStatic
    fun getNetworkPingTimeout(): Int {
        val timeoutString = App.getInstance().preferences.getString(NETWORK_PING_TIMEOUT, "2") ?: "2"
        return (timeoutString.toIntOrNull() ?: 2).coerceAtLeast(1)
    }
    @JvmStatic
    fun getUser(): String? {
        return App.getInstance().preferences.getString(USER, null)
    }

    @JvmStatic
    fun setUser(user: String?) {
        App.getInstance().preferences.edit().putString(USER, user).apply()
    }

    @JvmStatic
    fun getPassword(): String? {
        return App.getInstance().preferences.getString(PASSWORD, null)
    }

    @JvmStatic
    fun setPassword(password: String?) {
        App.getInstance().preferences.edit().putString(PASSWORD, password).apply()
    }

    @JvmStatic
    fun getToken(): String? {
        return App.getInstance().preferences.getString(TOKEN, null)
    }

    @JvmStatic
    fun setToken(token: String?) {
        App.getInstance().preferences.edit().putString(TOKEN, token).apply()
    }

    @JvmStatic
    fun getSalt(): String? {
        return App.getInstance().preferences.getString(SALT, null)
    }

    @JvmStatic
    fun setSalt(salt: String?) {
        App.getInstance().preferences.edit().putString(SALT, salt).apply()
    }

    @JvmStatic
    fun isLowScurity(): Boolean {
        return App.getInstance().preferences.getBoolean(LOW_SECURITY, false)
    }

    @JvmStatic
    fun setLowSecurity(isLowSecurity: Boolean) {
        App.getInstance().preferences.edit().putBoolean(LOW_SECURITY, isLowSecurity).apply()
    }

    @JvmStatic
    fun getClientCert(): String? {
        return App.getInstance().preferences.getString(CLIENT_CERT, null)
    }

    @JvmStatic
    fun setClientCert(clientCert: String?) {
        App.getInstance().preferences.edit().putString(CLIENT_CERT, clientCert).apply()
    }

    @JvmStatic
    fun getServerId(): String? {
        return App.getInstance().preferences.getString(SERVER_ID, null)
    }

    @JvmStatic
    fun setServerId(serverId: String?) {
        App.getInstance().preferences.edit().putString(SERVER_ID, serverId).apply()
    }

    @JvmStatic
    fun getLocalAddress(): String? {
        return App.getInstance().preferences.getString(LOCAL_ADDRESS, null)
    }

    @JvmStatic
    fun setLocalAddress(address: String?) {
        App.getInstance().preferences.edit().putString(LOCAL_ADDRESS, address).apply()
    }

    @JvmStatic
    fun getInUseServerAddress(): String? {
        return App.getInstance().preferences.getString(IN_USE_SERVER_ADDRESS, null)
            ?.takeIf { it.isNotBlank() }
            ?: getServer()
    }

    @JvmStatic
    fun isInUseServerAddressLocal(): Boolean {
        return getInUseServerAddress() == getLocalAddress()
    }

    @JvmStatic
    fun switchInUseServerAddress() {
        val inUseAddress = if (getInUseServerAddress() == getServer()) getLocalAddress() else getServer()
        App.getInstance().preferences.edit().putString(IN_USE_SERVER_ADDRESS, inUseAddress).apply()
    }
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
    fun getMaxBitrateWifi(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_WIFI, "0")!!
    }

    @JvmStatic
    fun getMaxBitrateMobile(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_MOBILE, "0")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatWifi(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_WIFI, "raw")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatMobile(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_MOBILE, "raw")!!
    }
    @JvmStatic
    fun isDataSavingMode(): Boolean {
        return App.getInstance().preferences.getBoolean(DATA_SAVING_MODE, false)
    }
    @JvmStatic
    fun getCustomCommandFirstButton(): String? {
        return App.getInstance().preferences.getString(CUSTOM_COMMAND_FIRST_BUTTON, "[heartID]")
    }

    @JvmStatic
    fun getCustomCommandSecondButton(): String? {
        return App.getInstance().preferences.getString(CUSTOM_COMMAND_SECOND_BUTTON, "[repeatID]")
    }
    @JvmStatic
    fun getReplayGainMode(): String? {
        return App.getInstance().preferences.getString(REPLAY_GAIN_MODE, "disabled")
    }

    @JvmStatic
    fun isReplayGainPreventClipping(): Boolean {
        return App.getInstance().preferences.getBoolean(REPLAY_GAIN_PREVENT_CLIPPING, true)
    }

    @JvmStatic
    fun getLoudnessPreamp(): Float {
        return App.getInstance().preferences.getInt(LOUDNESS_PREAMP, 0).toFloat()
    }
    @JvmStatic
    fun isServerPrioritized(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_TRANSCODE_PRIORITY, false)
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
    fun getDownloadStoragePreference(): Int {
        return App.getInstance().preferences.getString(DOWNLOAD_STORAGE, "0")!!.toInt()
    }

    @JvmStatic
    fun setDownloadStoragePreference(storagePreference: Int) {
        return App.getInstance().preferences.edit().putString(
                DOWNLOAD_STORAGE,
                storagePreference.toString()
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

    @JvmStatic
    fun isContinuousPlayEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(CONTINUOUS_PLAY, true)
    }

    @JvmStatic
    fun getNumberOfTracksKeepInQueue(): Int {
        return App.getInstance().preferences.getInt(NUMBER_TRACKS_KEEP_IN_QUEUE, 30) - 1
    }

    @JvmStatic
    fun isFallbackToRandomTracksEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(FALLBACK_TO_RANDOM_TRACKS, false)
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
    fun isAndroidAutoShufflePlaylistsEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(AA_SHUFFLE_PLAYLISTS, false)
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
