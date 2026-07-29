package com.cappielloantonio.tempo.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.api.search.SearchClient
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "BaseSessionCallback"

@UnstableApi
open class BaseSessionCallback(
    protected val context: Context,
    protected val service: BaseMediaService) :
    MediaLibraryService.MediaLibrarySession.Callback {

    // ─────────────────────────────────────────────────────────────
    // CommandButtons
    // ─────────────────────────────────────────────────────────────

    private val customCommandToggleShuffleModeOn =
        CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(context.getString(R.string.exo_controls_shuffle_on_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleShuffleModeOff =
        CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
            .setDisplayName(context.getString(R.string.exo_controls_shuffle_off_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleRepeatModeOff =
        CommandButton.Builder(CommandButton.ICON_REPEAT_OFF)
            .setDisplayName(context.getString(R.string.exo_controls_repeat_off_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleRepeatModeOne =
        CommandButton.Builder(CommandButton.ICON_REPEAT_ONE)
            .setDisplayName(context.getString(R.string.exo_controls_repeat_one_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleRepeatModeAll =
        CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
            .setDisplayName(context.getString(R.string.exo_controls_repeat_all_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    @Suppress("DEPRECATION")
    private val customCommandToggleHeartOn =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(context.getString(R.string.exo_controls_heart_on_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_HEART_ON, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_favorite)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    @Suppress("DEPRECATION")
    private val customCommandToggleHeartOff =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(context.getString(R.string.exo_controls_heart_off_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_HEART_OFF, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_favorites_outlined)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    @Suppress("DEPRECATION")
    private val customCommandToggleHeartLoading =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(context.getString(R.string.exo_controls_heart_loading_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_HEART_LOADING, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_bookmark_sync)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    @Suppress("DEPRECATION")
    private val customCommandInstantMixOn =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("instantmix on")
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_INSTANT_MIX_ON, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_instantmix_on)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    @Suppress("DEPRECATION")
    private val customCommandInstantMixOff =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("instantmix off")
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_INSTANT_MIX_OFF, Bundle.EMPTY))
            .setIconResId(R.drawable.media3_icon_minus_circle_unfilled)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    // Standard transport controls. Kept pinned in setMediaButtonPreferences so the custom
    // overflow buttons can't take their place on the last track (see #663).
    private val previousButton =
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName("Previous")
            .build()

    private val playPauseButton =
        CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName("Play/Pause")
            .build()

    private val nextButton =
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName("Next")
            .build()

    private val customLayoutCommandButtons = listOf(
        customCommandToggleShuffleModeOn,
        customCommandToggleShuffleModeOff,
        customCommandToggleRepeatModeOff,
        customCommandToggleRepeatModeOne,
        customCommandToggleRepeatModeAll,
        customCommandToggleHeartOn,
        customCommandToggleHeartOff,
        customCommandToggleHeartLoading,
        customCommandInstantMixOn,
        customCommandInstantMixOff
    )

    private val playerListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            currentSession?.let { updateMediaNotificationCustomLayout(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            currentSession?.let { updateMediaNotificationCustomLayout(it) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            currentSession?.let { updateMediaNotificationCustomLayout(it) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentSession?.let { updateMediaNotificationCustomLayout(it) }
        }
    }

    private var currentSession: MediaSession? = null

    /**
     * Where the rating call runs.
     *
     * Main, not IO, and that is load-bearing rather than a default: the
     * continuation touches `session.player`, and ExoPlayer's
     * `verifyApplicationThread` throws "Player is accessed on the wrong thread"
     * for anything else -- an uncaught crash on every heart tap, confirmed on a
     * running emulator. This is not new behaviour being chosen here, it is the
     * old behaviour being kept: Retrofit's Android platform posts `Call.enqueue`
     * callbacks through a main-thread executor, so the callback this replaced
     * already ran here. Nothing blocks the main thread as a result -- Retrofit's
     * `suspend` support is `enqueue` underneath, so the request itself still
     * runs on OkHttp's own threads and only the resumption lands here.
     *
     * SupervisorJob because two hearts tapped in quick succession are
     * independent requests -- under a plain Job the first one failing would
     * cancel the second.
     *
     * Not cancelled anywhere: this callback lives as long as the service that
     * holds it, and a rating already sent to Plex has nothing left to cancel.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Updates the player listener when the session's player changes.
     */
    fun handlePlayerChanged(oldPlayer: Player?, newPlayer: Player) {
        oldPlayer?.removeListener(playerListener)
        if (currentSession != null) {
            newPlayer.addListener(playerListener)
        }
    }

    @OptIn(UnstableApi::class)
    val mediaNotificationSessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .also { builder ->
                customLayoutCommandButtons.forEach { commandButton ->
                    commandButton.sessionCommand?.let { builder.add(it) }
                }
            }.build()

    // ─────────────────────────────────────────────────────────────
    // onConnect
    // ─────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        // Reset listener on every AA connection to avoid stale state after reconnection.
        // AA may call onConnect multiple times (double gearhead event observed in logs).
        if (currentSession == null
            || session.isAutomotiveController(controller)
            || session.isAutoCompanionController(controller)) {
            Log.d(TAG, "onConnect: remove and add listener")
            currentSession?.let { session.player.removeListener(playerListener) }
            currentSession = session
            session.player.addListener(playerListener)
        }

        if (session.isMediaNotificationController(controller) ||
            session.isAutomotiveController(controller) ||
            session.isAutoCompanionController(controller)
        ) {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(mediaNotificationSessionCommands)
                .setMediaButtonPreferences(buildMediaButtonPreferences(session.player))
                .build()
        }

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    // ─────────────────────────────────────────────────────────────
    // Custom layout
    // ─────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    protected fun updateMediaNotificationCustomLayout(
        session: MediaSession,
        isRatingPending: Boolean = false
    ) {
        val controller = session.mediaNotificationControllerInfo ?: return
        session.setMediaButtonPreferences(
            controller,
            buildMediaButtonPreferences(session.player, isRatingPending)
        )
    }

    // Pinned transport controls + the custom buttons (each in SLOT_OVERFLOW), built as one
    // media-button-preferences list so the custom buttons actually render in the notification on
    // Android 13 (#787); the deprecated setCustomLayout stopped applying them there once
    // setMediaButtonPreferences was introduced for #663.
    private fun buildMediaButtonPreferences(
        player: Player,
        isRatingPending: Boolean = false
    ): ImmutableList<CommandButton> {
        val buttons = mutableListOf(previousButton, playPauseButton, nextButton)
        buttons.addAll(buildCustomLayout(player, isRatingPending))
        return ImmutableList.copyOf(buttons)
    }

    protected fun buildCustomLayout(
        player: Player,
        isRatingPending: Boolean = false
    ): ImmutableList<CommandButton> {
        val customLayout = mutableListOf<CommandButton>()

        val allButtons = listOf(
            "[heartID]",
            "[repeatID]",
            "[shuffleID]",
            "[instantMixID]")
        val tabButton = listOfNotNull(
            Preferences.getCustomCommandFirstButton(),
            Preferences.getCustomCommandSecondButton()
        ).distinct()

        val remainingButtons = allButtons.filter { it !in tabButton }

        tabButton.forEach { id ->
            getCommandButton(id, player, isRatingPending)?.let { customLayout.add(it) }
        }

        remainingButtons.forEach { id ->
            getCommandButton(id, player, isRatingPending)?.let { customLayout.add(it) }
        }

        return ImmutableList.copyOf(customLayout)
    }

    private fun getCommandButton(id: String, player: Player, isRatingPending: Boolean): CommandButton? {
        return when (id) {
            "[heartID]" -> when {
                player.currentMediaItem == null || isRatingPending -> null
                (player.mediaMetadata.userRating as HeartRating?)?.isHeart == true -> customCommandToggleHeartOn
                else -> customCommandToggleHeartOff
            }

            "[shuffleID]" -> if (player.shuffleModeEnabled) customCommandToggleShuffleModeOff
            else customCommandToggleShuffleModeOn

            "[repeatID]" -> when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> customCommandToggleRepeatModeOne
                Player.REPEAT_MODE_ALL -> customCommandToggleRepeatModeAll
                else -> customCommandToggleRepeatModeOff
            }

            "[instantMixID]" -> if (!MediaManager.continuousPlayIsRunning.get()) customCommandInstantMixOn
            else customCommandInstantMixOff
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Rating (heart)
    // ─────────────────────────────────────────────────────────────

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        return onSetRating(
            session,
            controller,
            session.player.currentMediaItem!!.mediaId,
            rating
        )
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaId: String,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        val isStarring = (rating as HeartRating).isHeart
        val future = SettableFuture.create<SessionResult>()

        scope.launch {
            val result = try {
                // Plex rates 0-10; 10 is the five stars it collects into its
                // heart-named playlist, which is why the car shows a heart for a
                // field Plex renders as stars everywhere else.
                SearchClient(PlexApi()).rate(
                    RatingKey(mediaId),
                    if (isStarring) SearchClient.RATING_HEARTED else SearchClient.RATING_CLEARED
                ).fold(
                    { failure -> sessionResultFor(failure) },
                    {
                        applyRatingToQueue(session, mediaId, isStarring)
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                )
            } catch (failure: Throwable) {
                // The rate call's own failure is a value now, so this only covers
                // applyRatingToQueue and anything else that still throws. Outside
                // any `either { }`, so there is no `raise` to swallow.
                SessionResult(
                    SessionError(SessionError.ERROR_UNKNOWN, "Transport failure: ${failure.message}")
                )
            }

            // On every path, success included: the heart button was switched to
            // its loading icon before the request went out, so skipping this
            // leaves it spinning forever.
            updateMediaNotificationCustomLayout(session)
            future.set(result)
        }

        return future
    }

    /**
     * Maps a rating failure onto a legal SessionError.
     *
     * SessionError's constructor requires `code < 0 || code == 1`
     * (SessionError.java:209, media3 1.9.2), so passing a raw HTTP status
     * straight through -- 401, 404, 500 -- fails that precondition and throws
     * IllegalArgumentException. That used to escape the coroutine instead of
     * completing the future: the heart button stayed on its loading icon forever
     * and the uncaught exception reached the main thread's default handler. See
     * BaseSessionCallbackRatingTest for the pinned mechanism and its regression
     * test.
     *
     * 401/403 are the one case a car UI could plausibly act on differently
     * (prompt re-auth); everything else collapses to ERROR_UNKNOWN. The real HTTP
     * status still goes into the message -- it is not a legal `code`, but it is
     * the useful part for debugging. An unreachable server is worded differently
     * on purpose, so the two failure modes stay distinguishable in logs instead
     * of both reading as an HTTP error that never happened.
     */
    private fun sessionResultFor(failure: PlexTransportFailure): SessionResult = when (failure) {
        is PlexTransportFailure.Http -> {
            val code = when (failure.code) {
                401, 403 -> SessionError.ERROR_PERMISSION_DENIED
                else -> SessionError.ERROR_UNKNOWN
            }
            SessionResult(SessionError(code, "HTTP ${failure.code}"))
        }

        else -> SessionResult(
            SessionError(SessionError.ERROR_UNKNOWN, "Transport failure: $failure")
        )
    }

    /**
     * Carries the new rating into the queue so the heart survives a track
     * change: the button is rebuilt from `player.mediaMetadata.userRating`, and
     * without this the item the player holds still says what Plex used to think.
     */
    private fun applyRatingToQueue(session: MediaSession, mediaId: String, isStarring: Boolean) {
        for (i in 0 until session.player.mediaItemCount) {
            val mediaItem = session.player.getMediaItemAt(i)
            if (mediaItem.mediaId == mediaId) {
                val newMetadata = mediaItem.mediaMetadata.buildUpon()
                    .setUserRating(HeartRating(isStarring)).build()
                session.player.replaceMediaItem(
                    i,
                    mediaItem.buildUpon().setMediaMetadata(newMetadata).build()
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Custom commands dispatcher
    // ─────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        Log.d(TAG, "onCustomCommand: ${customCommand.customAction}")

        return when (customCommand.customAction) {
            Constants.CUSTOM_COMMAND_INSTANT_MIX_ON -> {
                if (!MediaManager.continuousPlayIsRunning.get() && Preferences.isInstantMixUsable()) {
                    Log.d(TAG, "onCustomCommand: start onInstantMix")
                    service.onInstantMix(session) { updateMediaNotificationCustomLayout(session) }
                }
                else
                    Log.d(TAG, "onCustomCommand: onInstantMix not usable")

                updateMediaNotificationCustomLayout(session)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON -> {
                session.player.shuffleModeEnabled = true
                updateMediaNotificationCustomLayout(session)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF -> {
                session.player.shuffleModeEnabled = false
                updateMediaNotificationCustomLayout(session)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF,
            Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE,
            Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL -> {
                val nextMode = when (session.player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                session.player.repeatMode = nextMode
                updateMediaNotificationCustomLayout(session)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            Constants.CUSTOM_COMMAND_TOGGLE_HEART_ON,
            Constants.CUSTOM_COMMAND_TOGGLE_HEART_OFF -> {
                val currentRating = session.player.mediaMetadata.userRating as? HeartRating
                val isCurrentlyLiked = currentRating?.isHeart ?: false
                updateMediaNotificationCustomLayout(session, isRatingPending = true)
                onSetRating(session, controller, HeartRating(!isCurrentlyLiked))
            }
            else -> Futures.immediateFuture(
                SessionResult(SessionError(SessionError.ERROR_NOT_SUPPORTED, customCommand.customAction))
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // onAddMediaItems — basic version (without AA)
    // should be override in MediaLibrarySessionCallback
    // ─────────────────────────────────────────────────────────────

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "onAddMediaItems")
        val updatedMediaItems = mediaItems.map { mediaItem ->
            val mediaMetadata = mediaItem.mediaMetadata
            val newMetadata = mediaMetadata.buildUpon()
                .setArtist(
                    mediaMetadata.artist
                        ?: mediaMetadata.extras?.getString(PlexMediaMapper.EXTRA_URI)
                        ?: ""
                )
                .build()
            mediaItem.buildUpon()
                .setUri(mediaItem.requestMetadata.mediaUri)
                .setMediaMetadata(newMetadata)
                .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                .build()
        }
        return Futures.immediateFuture(updatedMediaItems)
    }
}