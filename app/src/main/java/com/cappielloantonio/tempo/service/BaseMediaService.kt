package com.cappielloantonio.tempo.service

import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.*
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.equalizer.BuiltinBackend
import com.cappielloantonio.tempo.equalizer.EqualizerBackend
import com.cappielloantonio.tempo.equalizer.EqualizerManager
import com.cappielloantonio.tempo.equalizer.ExternalBackend
import com.cappielloantonio.tempo.equalizer.DefaultBackend
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.ui.activity.CarSignInActivity
import com.cappielloantonio.tempo.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "BaseMediaService"

@UnstableApi
open class BaseMediaService : MediaLibraryService() {
    companion object {
        const val ACTION_BIND_EQUALIZER = "com.cappielloantonio.tempo.service.BIND_EQUALIZER"
        const val ACTION_EQUALIZER_UPDATED = "com.cappielloantonio.tempo.service.EQUALIZER_UPDATED"
        const val ACTION_RELOAD_EQUALIZER = "com.cappielloantonio.tempo.service.ACTION_RELOAD_EQUALIZER"
        var activeBrowserCount = 0
    }

    protected lateinit var exoplayer: ExoPlayer
    protected lateinit var mediaLibrarySession: MediaLibrarySession
    protected var sessionCallback: MediaLibrarySession.Callback? = null
    private lateinit var bitmapLoader: SyncBitmapLoader
    private lateinit var networkCallback: CustomNetworkCallback
    private lateinit var equalizerManager: EqualizerManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val addressBook = ServerAddressBook.shared
    private val addressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD_EQUALIZER -> reloadEqualizer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    open fun playerInitHook() {
        initializeExoPlayer()
        initializeMediaLibrarySession(exoplayer)
        initializePlayerListener(exoplayer)
        setPlayer(null, exoplayer)
    }

    open fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        return BaseSessionCallback(baseContext, this)
    }

    // The Subsonic-era updateMediaItems() lived here: on every WiFi<->cellular switch
    // it rewrote each upcoming item's stream URL so the new network's maxBitRate and
    // format took effect. Plex direct-plays the part through
    // MediaUrlBuilder.streamUrl, which takes no transcoding parameters, so there is
    // nothing left to re-resolve and the network callback below now only re-evaluates
    // the precache.

    fun restorePlayerFromQueue(player: Player) {
        if (player.mediaItemCount > 0) return

        val queueRepository = QueueRepository()
        val mediaItems = queueRepository.media
        if (mediaItems.isNullOrEmpty()) return

        val lastIndex = try {
            queueRepository.lastPlayedMediaIndex
        } catch (_: Exception) {
            0
        }.coerceIn(0, mediaItems.size - 1)

        val lastPosition = try {
            queueRepository.lastPlayedMediaTimestamp
        } catch (_: Exception) {
            0L
        }.let { if (it < 0L) 0L else it }

        player.setMediaItems(mediaItems, lastIndex, lastPosition)
        player.prepare()
    }

    // Throttle for onPlayerError re-prepare recovery (see #682).
    private var lastPlayerErrorRecoveryMs = 0L
    private val playerErrorRecoveryThrottleMs = 5_000L

    fun initializePlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // A network switch (WiFi <-> mobile) surfaces here as a source/network
                // error. Without recovery the player goes idle and stays silent until the
                // app is restarted (issue #682). Re-prepare to resume from the current
                // position, but only for recoverable IO errors and throttled so a permanent
                // failure (bad URL, auth) can't spin in an endless prepare loop.
                Log.w(TAG, "onPlayerError: ${error.errorCodeName}", error)

                val recoverable = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
                    else -> false
                }
                if (!recoverable) return

                // The playback spelling of Unreachable. The re-prepare below can
                // only help once the address the URL resolves to is a live one.
                addressScope.launch {
                    addressBook.current()?.let { addressBook.reprobe(it) }
                }

                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPlayerErrorRecoveryMs >= playerErrorRecoveryThrottleMs) {
                    lastPlayerErrorRecoveryMs = now
                    player.prepare()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "onMediaItemTransition" + player.currentMediaItemIndex)
                if (mediaItem == null) return
                ReplayGainUtil.applyGain(player, mediaItem)

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    MediaManager.setLastPlayedTimestamp(mediaItem)
                }

                QueuePreloader.preload(this@BaseMediaService, player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                Log.d(TAG, "onTimelineChanged reason=$reason")
                try {
                    ReplayGainUtil.prefetchQueueGains(player)
                } catch (t: Throwable) {
                    Log.w(TAG, "prefetchQueueGains failed: $t")
                }
                QueuePreloader.preload(this@BaseMediaService, player)
                if (timeline.isEmpty) return
                val window = Timeline.Window()
                for (i in 0 until timeline.windowCount) {
                    timeline.getWindow(i, window)
                    window.mediaItem.mediaMetadata.artworkUri?.let { bitmapLoader.prewarm(it) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                Log.d(TAG, "onTracksChanged: " + player.currentMediaItemIndex)
                ReplayGainUtil.setReplayGain(player, tracks)
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem != null) {
                    if (currentMediaItem.mediaMetadata.extras != null)
                        MediaManager.scrobble(currentMediaItem, false, player.currentPosition)

                    if (player.nextMediaItemIndex == C.INDEX_UNSET) {
                        if (Preferences.isContinuousPlayEnabled()) {
                            val browserFuture = MediaBrowser.Builder(
                                this@BaseMediaService,
                                SessionToken(this@BaseMediaService, ComponentName(this@BaseMediaService, this@BaseMediaService::class.java))
                            ).buildAsync()
                            MediaManager.continuousPlay(currentMediaItem, browserFuture)
                        }
                    }
                }

                if (player is ExoPlayer) {
                    // https://stackoverflow.com/questions/56937283/exoplayer-shuffle-doesnt-reproduce-all-the-songs
                    if (MediaManager.justStarted.get()) {
                        Log.d(TAG, "update shuffle order")
                        MediaManager.justStarted.set(false)
                        val shuffledList = IntArray(player.mediaItemCount) { i -> i }
                        shuffledList.shuffle()
                        val index = shuffledList.indexOf(player.currentMediaItemIndex)
                        // swap current media index to the first index
                        if (index > -1 && shuffledList.isNotEmpty()) {
                            val tmp = shuffledList[0]
                            shuffledList[0] = shuffledList[index]
                            shuffledList[index] = tmp
                        }
                        player.shuffleOrder =
                            DefaultShuffleOrder(shuffledList, Random.nextLong())
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged " + player.currentMediaItemIndex)
                if (!isPlaying) {
                    MediaManager.setPlayingPausedTimestamp(
                        player.currentMediaItem,
                        player.currentPosition
                    )
                } else {
                    MediaManager.scrobble(player.currentMediaItem, false, player.currentPosition)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged")
                super.onPlaybackStateChanged(playbackState)
                if (!player.hasNextMediaItem() &&
                    playbackState == Player.STATE_ENDED &&
                    player.mediaMetadata.extras?.getString(PlexMediaMapper.EXTRA_TYPE) == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.scrobble(player.currentMediaItem, true, player.currentPosition)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                Log.d(TAG, "onPositionDiscontinuity reason=$reason old=${oldPosition.mediaItemIndex} new=${newPosition.mediaItemIndex}")
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)

                // Re-apply gain whenever we stay on the same track for any reason
                // except an automatic transition to the next track.
                if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                    oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                    // Clear pending gain immediately (main thread) before reapplying.
                    // This pre-empts the same-format gapless promotion in onFlush: if
                    // the decoder ran ahead (endOfStreamPending=true) before the seek,
                    // hasPendingFlushGain being false when onFlush fires ensures we
                    // restore to the correct current-track baseline instead of applying
                    // the next track's gain mid-track.
                    ReplayGainUtil.getAudioProcessor().clearPendingGain()
                    ReplayGainUtil.reapplyCurrentTrackGain(player)
                }

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString(PlexMediaMapper.EXTRA_TYPE) == Constants.MEDIA_TYPE_MUSIC) {
                        // oldPosition.positionMs, not player.currentPosition: by the time this
                        // fires the player has already moved onto the next item, so
                        // currentPosition would report the *new* track's position against the
                        // *old* track's ratingKey. positionMs is where playback actually left
                        // the old track -- exactly what a "stopped" report should carry.
                        MediaManager.scrobble(oldPosition.mediaItem, true, oldPosition.positionMs)
                    }

                    if (newPosition.mediaItem?.mediaMetadata?.extras?.getString(PlexMediaMapper.EXTRA_TYPE) == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.setLastPlayedTimestamp(newPosition.mediaItem)
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                Preferences.setShuffleModeEnabled(shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Preferences.setRepeatMode(repeatMode)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.d(TAG, "onAudioSessionIdChanged")
                equalizerManager.attach(audioSessionId)
                sendBroadcast(Intent(ACTION_EQUALIZER_UPDATED))
            }
        })
    }

    fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
        if (oldPlayer === newPlayer) return
        if (oldPlayer != null) {
            val currentQueue = getQueueFromPlayer(oldPlayer)
            val currentIndex = oldPlayer.currentMediaItemIndex
            val currentPosition = oldPlayer.currentPosition
            val isPlaying = oldPlayer.playWhenReady
            oldPlayer.stop()
            newPlayer.setMediaItems(currentQueue, currentIndex, currentPosition)
            newPlayer.playWhenReady = isPlaying
            newPlayer.prepare()
        }
        mediaLibrarySession.player = newPlayer
        (sessionCallback as? BaseSessionCallback)?.handlePlayerChanged(oldPlayer, newPlayer)
    }

    open fun releasePlayers() {
        exoplayer.release()
    }

    fun getQueueFromPlayer(player: Player): List<MediaItem> {
        return (0..player.mediaItemCount - 1).map(player::getMediaItemAt)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player

        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()

        playerInitHook()
        initializeEqualizer()
        initializeNetworkListener()
        restorePlayerFromQueue(mediaLibrarySession.player)
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        // Detach first: it must precede mediaLibrarySession.release() but must not depend
        // on any of the cleanup below succeeding. If an earlier step throws, the singleton
        // would otherwise keep pointing at a session that is about to be released.
        BrowseTreeInvalidator.detach()
        QueuePreloader.cancel()
        // Cancel before releaseNetworkCallback(): that call dereferences a lateinit
        // var with no ::isInitialized guard and throws if the callback was never
        // registered, which would otherwise take addressScope.cancel() down with it.
        // Launching into an already-cancelled scope is a silent no-op, so there is
        // no cost to cancelling first.
        addressScope.cancel()
        releaseNetworkCallback()
        equalizerManager.release(exoplayer.audioSessionId)
        ReplayGainUtil.release()
        if (::bitmapLoader.isInitialized) bitmapLoader.shutdown()
        releasePlayers()
        mediaLibrarySession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Check if the intent is for our custom equalizer binder
        if (intent?.action == ACTION_BIND_EQUALIZER) {
            return binder
        }
        // Otherwise, handle it as a normal MediaLibraryService connection
        return super.onBind(intent)
    }

    private fun initializeExoPlayer() {
        exoplayer = ExoPlayer.Builder(this)
            .setRenderersFactory(getRenderersFactory())
            .setMediaSourceFactory(getMediaSourceFactory())
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(initializeLoadControl())
            .build()

        exoplayer.shuffleModeEnabled = Preferences.isShuffleModeEnabled()
        exoplayer.repeatMode = Preferences.getRepeatMode()
        exoplayer.playbackParameters = getPlaybackParameters(Preferences.getPlaybackSpeed())
    }

    private fun getPlaybackParameters(speed: Float): PlaybackParameters {
        val pitch = if (Preferences.isPlaybackSpeedPitchEnabled()) getAdjustedPitch(speed) else 1.0f
        return PlaybackParameters(speed, pitch)
    }

    private fun getAdjustedPitch(speed: Float): Float {
        return if (Preferences.isPlaybackSpeedManualPitchEnabled()) {
            Preferences.getPlaybackSpeedManualPitch()
        } else {
            speed
        }
    }

    private fun initializeEqualizer() {

        val equalizerBackend: EqualizerBackend =
            when (Preferences.getSelectedEqualizer()) {
            1 -> BuiltinBackend()
            2 -> ExternalBackend()
            else -> DefaultBackend()
        }

        equalizerManager = EqualizerManager(equalizerBackend, baseContext)
        equalizerManager.attach(exoplayer.audioSessionId)
        sendBroadcast(Intent(ACTION_EQUALIZER_UPDATED))
    }

    fun reloadEqualizer() {
        equalizerManager.release(exoplayer.audioSessionId)

        val backend: EqualizerBackend = when (Preferences.getSelectedEqualizer()) {
            1 -> BuiltinBackend()
            2 -> ExternalBackend()
            else -> DefaultBackend()
        }

        equalizerManager = EqualizerManager(backend, baseContext)
        equalizerManager.attach(exoplayer.audioSessionId)
        sendBroadcast(Intent(ACTION_RELOAD_EQUALIZER))
    }

    private fun initializeMediaLibrarySession(player: Player) {
        Log.d(TAG, "initializeMediaLibrarySession")
        val sessionActivityPendingIntent =
            TaskStackBuilder.create(this).run {
                addNextIntent(Intent(baseContext, CarSignInActivity::class.java))
                getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
            }

        bitmapLoader = SyncBitmapLoader(applicationContext)

        mediaLibrarySession =
            MediaLibrarySession.Builder(this, player, getMediaLibrarySessionCallback())
                .setSessionActivity(sessionActivityPendingIntent)
                .setPeriodicPositionUpdateEnabled(false)
                .setBitmapLoader(bitmapLoader)
                .build()

        BrowseTreeInvalidator.attach(mediaLibrarySession)
    }

    private fun initializeNetworkListener() {
        networkCallback = CustomNetworkCallback()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            networkCallback
        )
    }

    private fun initializeLoadControl(): DefaultLoadControl {
        val preloadSec = Preferences.getSongPreloadBuffer().toLong()
        val preloadMs = TimeUnit.SECONDS.toMillis(preloadSec).toInt()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                preloadMs,
                preloadMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
    }

    private fun releaseNetworkCallback() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    }

    private fun getRenderersFactory(): DefaultRenderersFactory {
        val extensionRendererMode = if (DownloadUtil.useExtensionRenderers())
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        else
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

        return object : DefaultRenderersFactory(this) {
            init {
                setExtensionRendererMode(extensionRendererMode)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(ReplayGainUtil.getAudioProcessor()))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
    }

    private fun getMediaSourceFactory(): MediaSource.Factory = DynamicMediaSourceFactory(this)

    private inner class CustomNetworkCallback : ConnectivityManager.NetworkCallback() {
        var wasWifi = false

        init {
            val manager = getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities != null)
                wasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        /**
         * A new default network, which is when a stored server address is most
         * likely to have stopped working -- the car left the LAN it signed in on.
         *
         * Deliberately not onCapabilitiesChanged, which the preloader below uses:
         * that fires on a transport flip, so wifi-to-wifi would not trip it and
         * neither would a Plex container taking a new LAN address.
         *
         * This also fires once at registration, which gives service start a
         * re-probe for free.
         */
        override fun onAvailable(network: Network) {
            addressScope.launch {
                addressBook.current()?.let { addressBook.reprobe(it) }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isWifi != wasWifi) {
                wasWifi = isWifi
                mainHandler.post {
                    // preload() re-evaluates the network itself: it cancels any
                    // in-flight precache when the new network is not allowed and
                    // restarts it when it is.
                    QueuePreloader.preload(this@BaseMediaService, mediaLibrarySession.player)
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getEqualizerManager(): EqualizerManager {
            return equalizerManager
        }

        fun getPlayer(): ExoPlayer {
            return exoplayer
        }
    }
}
