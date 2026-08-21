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
    // No reader in app/src any more -- the instant-mix branch of
    // onCustomCommand held the only `service.` call in this class, and it is
    // gone from the overflow. Kept rather than deleted: it is `protected` and
    // every construction site already supplies it, so removing it means
    // touching BaseMediaService's factory, MediaLibrarySessionCallback, and
    // four test files -- a wider refactor than this change asked for.
    protected val service: BaseMediaService,
) : MediaLibraryService.MediaLibrarySession.Callback {
    // ─────────────────────────────────────────────────────────────
    // CommandButtons
    // ─────────────────────────────────────────────────────────────

    // ...ModeOn is the button shown while shuffle is *off*: buildMediaButtonPreferences
    // offers the action you can take, not the state you are in, and the command and the
    // label are both named for that action. The icon is the one part that reads the other
    // way -- ...ModeOn draws ICON_SHUFFLE_OFF -- because an icon depicts the state you are
    // in while a label says what tapping does.
    //
    // The labels were ExoPlayer's exo_controls_shuffle_{on,off}_description until #74.
    // Those are named for the state, so pairing them with these action-named properties by
    // matching name gave each button the opposite of its own action, which is how the bug
    // got written; the ids say enable/disable now so there is nothing left to match up
    // wrongly. The repeat buttons below keep the state naming, because their strings are
    // worded as state -- the two sets are not meant to line up.

    private val customCommandToggleShuffleModeOn =
        CommandButton
            .Builder(CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(context.getString(R.string.shuffle_enable_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleShuffleModeOff =
        CommandButton
            .Builder(CommandButton.ICON_SHUFFLE_ON)
            .setDisplayName(context.getString(R.string.shuffle_disable_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleRepeatModeOff =
        CommandButton
            .Builder(CommandButton.ICON_REPEAT_OFF)
            .setDisplayName(context.getString(R.string.exo_controls_repeat_off_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleRepeatModeOne =
        CommandButton
            .Builder(CommandButton.ICON_REPEAT_ONE)
            .setDisplayName(context.getString(R.string.exo_controls_repeat_one_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private val customCommandToggleRepeatModeAll =
        CommandButton
            .Builder(CommandButton.ICON_REPEAT_ALL)
            .setDisplayName(context.getString(R.string.exo_controls_repeat_all_description))
            .setSessionCommand(SessionCommand(Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    // No rating button. The car draws its own control left of transport, because
    // PlexMediaMapper publishes a HeartRating -- see the 2026-08-02 car star
    // rating design. It arrives here through onSetRating, not onCustomCommand.

    // Transport controls are deliberately *not* declared here -- see
    // buildMediaButtonPreferences for why the car draws its own.

    private val customLayoutCommandButtons =
        listOf(
            customCommandToggleShuffleModeOn,
            customCommandToggleShuffleModeOff,
            customCommandToggleRepeatModeOff,
            customCommandToggleRepeatModeOne,
            customCommandToggleRepeatModeAll,
        )

    private val playerListener =
        object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                currentSession?.let { updateMediaNotificationCustomLayout(it) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                currentSession?.let { updateMediaNotificationCustomLayout(it) }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                currentSession?.let { updateMediaNotificationCustomLayout(it) }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
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
    fun handlePlayerChanged(
        oldPlayer: Player?,
        newPlayer: Player,
    ) {
        oldPlayer?.removeListener(playerListener)
        if (currentSession != null) {
            newPlayer.addListener(playerListener)
        }
    }

    @OptIn(UnstableApi::class)
    val mediaNotificationSessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
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
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        // Reset listener on every AA connection to avoid stale state after reconnection.
        // AA may call onConnect multiple times (double gearhead event observed in logs).
        if (currentSession == null ||
            session.isAutomotiveController(controller) ||
            session.isAutoCompanionController(controller)
        ) {
            Log.d(TAG, "onConnect: remove and add listener")
            currentSession?.let { session.player.removeListener(playerListener) }
            currentSession = session
            session.player.addListener(playerListener)
        }

        if (session.isMediaNotificationController(controller) ||
            session.isAutomotiveController(controller) ||
            session.isAutoCompanionController(controller)
        ) {
            return MediaSession.ConnectionResult
                .AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(mediaNotificationSessionCommands)
                .setMediaButtonPreferences(buildMediaButtonPreferences(session.player))
                .build()
        }

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
    }

    // ─────────────────────────────────────────────────────────────
    // Custom layout
    // ─────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    protected fun updateMediaNotificationCustomLayout(session: MediaSession) {
        val controller = session.mediaNotificationControllerInfo ?: return
        session.setMediaButtonPreferences(
            controller,
            buildMediaButtonPreferences(session.player),
        )
    }

    /**
     * Only the custom buttons, all of them SLOT_OVERFLOW.
     *
     * Transport used to be pinned in front of them -- previous, play/pause, next --
     * which upstream added for the Android 13 *notification* (#663, #787). This fork
     * has no phone audience, and in the car that pinning broke the mini player.
     *
     * `com.android.car.media` is a legacy MediaControllerCompat client, and media3
     * publishes these preferred buttons into the PlaybackState custom-action list
     * (verified: `dumpsys media_session` listed custom actions literally named
     * "Previous" and "Next"). The mini player then filled its two side slots from
     * that list instead of drawing transport, so it showed the car's own rating
     * widget on the left, "Previous" on the right, and no next button at all.
     *
     * Left out, the car draws its own transport -- the browse UI reports
     * skip_prev / play_pause / skip_next view ids -- from the PlaybackState
     * `actions` bitmask, which already advertises play/pause and both skips from
     * the player's available commands. The custom buttons stay in the overflow.
     *
     * The risk #663 described -- a custom button taking a transport slot on the last
     * track -- does not apply once transport is not competing for those slots.
     *
     * Nothing about that row is ours to arrange, and three experiments say so:
     *
     *  - Reordering the pinned buttons changed nothing. Sending them as
     *    [next, playPause, previous] produced a row identical to
     *    [previous, playPause, next]. The car's arrangement is fixed, not derived
     *    from the order we send.
     *  - Slots do not promote a custom button into the row. SLOT_FORWARD_SECONDARY
     *    and declaring no slots at all were both tried; the row was unchanged
     *    either way. The row takes *player-command* buttons, which reach a legacy
     *    client as standard transport actions; a setSessionCommand button is a
     *    custom action, and the car files those under the overflow.
     *  - The star left of transport is not ours, and that is the point. It is the
     *    car's own rating widget, drawn because PlexMediaMapper publishes a
     *    HeartRating, and it reaches onSetRating directly. Nothing here can put a
     *    button in that slot while it is displayed, and nothing needs to.
     *
     * The order used to come from two preferences inherited from tempo, which had a
     * settings screen to write them. This fork deleted that screen, so both always
     * returned their defaults and the order was fixed anyway -- just spelled through
     * a preference read and a string-id dispatch rather than stated here.
     */
    protected fun buildMediaButtonPreferences(player: Player): ImmutableList<CommandButton> =
        ImmutableList.of(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> customCommandToggleRepeatModeOne
                Player.REPEAT_MODE_ALL -> customCommandToggleRepeatModeAll
                else -> customCommandToggleRepeatModeOff
            },
            if (player.shuffleModeEnabled) {
                customCommandToggleShuffleModeOff
            } else {
                customCommandToggleShuffleModeOn
            },
        )

    // ─────────────────────────────────────────────────────────────
    // Rating (heart)
    // ─────────────────────────────────────────────────────────────

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        rating: Rating,
    ): ListenableFuture<SessionResult> {
        // The car rates the current track and nothing else, but it is the only
        // caller now that the heart button is gone, so an empty queue reaches this
        // as a plain null rather than as anything we control.
        val mediaId =
            session.player.currentMediaItem?.mediaId
                ?: return Futures.immediateFuture(
                    SessionResult(SessionError(SessionError.ERROR_INVALID_STATE, "No current track to rate")),
                )

        return onSetRating(session, controller, mediaId, rating)
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaId: String,
        rating: Rating,
    ): ListenableFuture<SessionResult> {
        // A checked cast, because the car is the only source of these now. It sends
        // back the type we publish -- a HeartRating, since that is the one the car
        // draws a control for -- but an unchecked cast turns any other into a
        // ClassCastException on a car-driven path, which fails as a crash rather
        // than as a session error.
        if (rating !is HeartRating) {
            return Futures.immediateFuture(
                SessionResult(
                    SessionError(SessionError.ERROR_NOT_SUPPORTED, "Unsupported rating: ${rating.javaClass.simpleName}"),
                ),
            )
        }

        val isStarring = rating.isHeart
        val future = SettableFuture.create<SessionResult>()

        scope.launch {
            val result =
                try {
                    // Plex rates 0-10; 10 is the five stars it collects into its
                    // heart-named playlist, which is why the car shows a heart for a
                    // field Plex renders as stars everywhere else.
                    SearchClient(PlexApi())
                        .rate(
                            RatingKey(mediaId),
                            if (isStarring) SearchClient.RATING_HEARTED else SearchClient.RATING_CLEARED,
                        ).fold(
                            { failure -> sessionResultFor(failure) },
                            {
                                applyRatingToQueue(session, mediaId, isStarring)
                                SessionResult(SessionResult.RESULT_SUCCESS)
                            },
                        )
                } catch (failure: Throwable) {
                    // The rate call's own failure is a value now, so this only covers
                    // applyRatingToQueue and anything else that still throws. Outside
                    // any `either { }`, so there is no `raise` to swallow.
                    SessionResult(
                        SessionError(SessionError.ERROR_UNKNOWN, "Transport failure: ${failure.message}"),
                    )
                }

            // No layout rebuild here. It used to be unconditional because the heart
            // button was rebuilt from the rating; the overflow no longer varies with
            // it, and the car's control redraws from the metadata applyRatingToQueue
            // writes, which is a different channel from button preferences.
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
     * completing the future, leaving the rating's future to hang and the uncaught
     * exception to reach the main thread's default handler. See
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
    private fun sessionResultFor(failure: PlexTransportFailure): SessionResult =
        when (failure) {
            is PlexTransportFailure.Http -> {
                val code =
                    when (failure.code) {
                        401, 403 -> SessionError.ERROR_PERMISSION_DENIED
                        else -> SessionError.ERROR_UNKNOWN
                    }
                SessionResult(SessionError(code, "HTTP ${failure.code}"))
            }

            else -> {
                SessionResult(
                    SessionError(SessionError.ERROR_UNKNOWN, "Transport failure: $failure"),
                )
            }
        }

    /**
     * Carries the new rating into the queue so the car's control survives a track
     * change: it is drawn from `player.mediaMetadata.userRating`, and without this
     * the item the player holds still says what Plex used to think.
     *
     * Load-bearing rather than housekeeping. It is the only thing that updates the
     * control after a tap, now that nothing about the button preferences depends on
     * the rating.
     */
    private fun applyRatingToQueue(
        session: MediaSession,
        mediaId: String,
        isStarring: Boolean,
    ) {
        for (i in 0 until session.player.mediaItemCount) {
            val mediaItem = session.player.getMediaItemAt(i)
            if (mediaItem.mediaId == mediaId) {
                val newMetadata =
                    mediaItem.mediaMetadata
                        .buildUpon()
                        .setUserRating(HeartRating(isStarring))
                        .build()
                session.player.replaceMediaItem(
                    i,
                    mediaItem.buildUpon().setMediaMetadata(newMetadata).build(),
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
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        Log.d(TAG, "onCustomCommand: ${customCommand.customAction}")

        return when (customCommand.customAction) {
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
            Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL,
            -> {
                val nextMode =
                    when (session.player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                session.player.repeatMode = nextMode
                updateMediaNotificationCustomLayout(session)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            else -> {
                Futures.immediateFuture(
                    SessionResult(SessionError(SessionError.ERROR_NOT_SUPPORTED, customCommand.customAction)),
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // onAddMediaItems — basic version (without AA)
    // should be override in MediaLibrarySessionCallback
    // ─────────────────────────────────────────────────────────────

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "onAddMediaItems")
        val updatedMediaItems =
            mediaItems.map { mediaItem ->
                val mediaMetadata = mediaItem.mediaMetadata
                val newMetadata =
                    mediaMetadata
                        .buildUpon()
                        .setArtist(
                            mediaMetadata.artist
                                ?: mediaMetadata.extras?.getString(PlexMediaMapper.EXTRA_URI)
                                ?: "",
                        ).build()
                mediaItem
                    .buildUpon()
                    .setUri(mediaItem.requestMetadata.mediaUri)
                    .setMediaMetadata(newMetadata)
                    .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                    .build()
            }
        return Futures.immediateFuture(updatedMediaItems)
    }
}
