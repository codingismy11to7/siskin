package com.cappielloantonio.tempo.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.google.common.collect.ImmutableList

/**
 * Builds the browse error that makes a car media template draw a "Sign in" button.
 *
 * A bare LibraryResult.ofError leaves the car with a dead-end. The template only
 * offers a resolution when the error carries ERROR_RESOLUTION_ACTION_LABEL and
 * ERROR_RESOLUTION_ACTION_INTENT extras.
 */
@UnstableApi
object CarSignInResolution {
    /** Arbitrary but stable, so FLAG_UPDATE_CURRENT targets the same PendingIntent. */
    private const val REQUEST_CODE = 0x5169

    fun errorResult(
        context: Context,
        @StringRes messageRes: Int,
        @StringRes actionRes: Int = R.string.car_sign_in_action,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val message = context.getString(messageRes)
        val action = context.getString(actionRes)
        return LibraryResult.ofError(
            // ERROR_SESSION_AUTHENTICATION_EXPIRED (-102) is one of exactly two codes
            // media3 1.9.2's MediaLibrarySessionImpl.isReplicationErrorCode replicates
            // to a legacy MediaBrowserCompat client (the other is
            // ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED, -105). com.android.car.media
            // is such a client, and that replication is the only route by which the
            // label and PendingIntent extras below reach the PlaybackState and become
            // a button. Swapping this code for any other SessionError silently drops
            // the button with no compile or test failure.
            SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, message),
            MediaLibraryService.LibraryParams
                .Builder()
                .setExtras(resolutionExtras(context, action))
                .build(),
        )
    }

    private fun resolutionExtras(
        context: Context,
        action: String,
    ): Bundle =
        Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, action)
            putParcelable(
                MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                signInPendingIntent(context),
            )
        }

    private fun signInPendingIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, CarHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // Every route through this object is a credentials problem, so the
                // screen must offer sign-in even when a (rejected) session object
                // still exists. The gear's launch carries no extra and decides for
                // itself.
                .putExtra(CarHostActivity.EXTRA_FORCE_SIGN_IN, true)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
