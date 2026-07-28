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
import android.os.Bundle
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
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.ui.activity.CarSignInActivity
import com.cappielloantonio.tempo.util.*
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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

    fun updateMediaItems(player: Player) {
        Log.d(TAG, "update items")
        // Re-resolve per-network stream URLs (maxBitRate/format) for the queue WITHOUT
        // interrupting the currently-playing track. The previous implementation called
        // clearMediaItems() + setMediaItems() over the live player, which discards the
        // active item's forward buffer and forces a re-prepare on every WiFi<->cellular
        // switch — an audible ~0.5s gap (and, on some devices, the failed re-prepare that
        // #682 recovers from). Instead, replace only the non-current items, and only when
        // the resolved URI actually changed, so the active item is never touched while
        // upcoming tracks still pick up the new network's transcoding settings.

        // Threading: the heavy computation (MappingUtil) runs on a background
        // thread to avoid blocking the main thread. Only items from current+1 onward are
        // processed — already-played items are skipped. replaceMediaItem() is dispatched back
        // to the main thread via mainHandler. The guard i < player.mediaItemCount protects
        // against queue changes during the background computation.

        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return

        // read all items
        val itemsToProcess = (current + 1 until player.mediaItemCount).map { i ->
            Pair(i, player.getMediaItemAt(i))
        }
        if (itemsToProcess.isEmpty()) return

        val delegate = Executors.newSingleThreadExecutor()
        val executor = MoreExecutors.listeningDecorator(delegate)
        val future: ListenableFuture<List<Pair<Int, MediaItem>>> = executor.submit(Callable {
            itemsToProcess.mapNotNull { (i, old) ->
                val mapped = MappingUtil.mapMediaItem(old)
                if (mapped.requestMetadata.mediaUri != old.requestMetadata.mediaUri) {
                    Pair(i, mapped)
                } else null
            }
        })
        delegate.shutdown()

        Futures.addCallback(future, object : FutureCallback<List<Pair<Int, MediaItem>>> {
            override fun onSuccess(updates: List<Pair<Int, MediaItem>>) {
                mainHandler.post {
                    updates.forEach { (i, mapped) ->
                        if (i > player.currentMediaItemIndex
                            && i < player.mediaItemCount
                            && player.getMediaItemAt(i).mediaId == mapped.mediaId) {
                            player.replaceMediaItem(i, mapped)
                        }
                    }
                }
            }
            override fun onFailure(t: Throwable) {
                Log.e(TAG, "updateMediaItems failed", t)
            }
        }, MoreExecutors.directExecutor())
    }

    fun restorePlayerFromQueue(player: Player) {
        if (player.mediaItemCount > 0) return

        val queueRepository = QueueRepository()
        val storedQueue = queueRepository.media
        if (storedQueue.isNullOrEmpty()) return

        val mediaItems = MappingUtil.mapMediaItems(storedQueue)
        if (mediaItems.isEmpty()) return

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

                // --- Add for AA : Constants.AA_START_INDEX if présent ---
                val extras = mediaItem.mediaMetadata.extras
                val startIndex = extras?.getInt(Constants.AA_START_INDEX, -1) ?: -1
                if (startIndex >= 0 ) {
                    val cleanExtras = Bundle(extras).apply {
                        remove(Constants.AA_START_INDEX)
                    }
                    val newMetadata = mediaItem.mediaMetadata.buildUpon()
                        .setExtras(cleanExtras)
                        .build()
                    val currentIdx = player.currentMediaItemIndex
                    if (player is ExoPlayer && currentIdx != C.INDEX_UNSET) {
                        player.replaceMediaItem(
                            currentIdx,
                            mediaItem.buildUpon().setMediaMetadata(newMetadata).build()
                        )
                    }
                    if (startIndex in 0 until player.mediaItemCount && startIndex != currentIdx) {
                        player.seekTo(startIndex, 0L)
                    }
                }
                // --- End add for AA ---
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
                    val item = MappingUtil.mapMediaItem(currentMediaItem)
                    if (item.mediaMetadata.extras != null)
                        MediaManager.scrobble(item, false)

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
                    MediaManager.scrobble(player.currentMediaItem, false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged")
                super.onPlaybackStateChanged(playbackState)
                if (!player.hasNextMediaItem() &&
                    playbackState == Player.STATE_ENDED &&
                    player.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.scrobble(player.currentMediaItem, true)
                    MediaManager.saveChronology(player.currentMediaItem)
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
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.scrobble(oldPosition.mediaItem, true)
                        MediaManager.saveChronology(oldPosition.mediaItem)
                    }

                    if (newPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
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

    open fun onInstantMix(session: MediaSession, onComplete: Runnable? = null) {
        val player = session.player
        val currentMediaItem = player.currentMediaItem
        val currentIndex = player.currentMediaItemIndex
        val lastIndex = player.mediaItemCount - 1
        val browserFuture = MediaBrowser.Builder(
            this@BaseMediaService,
            SessionToken(this@BaseMediaService, ComponentName(this@BaseMediaService, this@BaseMediaService::class.java))
        ).buildAsync()

        if (currentIndex in 0 until lastIndex) {
            Log.d(TAG, "onInstantMix: remove range from $currentIndex to $lastIndex")
            MediaManager.removeRange(browserFuture, currentIndex + 1, lastIndex + 1)
        }

        Log.d(TAG, "onInstantMix: start Continuous Play with $currentMediaItem")
        MediaManager.continuousPlay(currentMediaItem, browserFuture) {
            Handler(Looper.getMainLooper()).post { onComplete?.run() }
        }
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
        updateMediaItems(mediaLibrarySession.player)
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

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isWifi != wasWifi) {
                wasWifi = isWifi
                mainHandler.post {
                    updateMediaItems(mediaLibrarySession.player)
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
