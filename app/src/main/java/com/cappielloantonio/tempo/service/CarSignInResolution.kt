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
import com.cappielloantonio.tempo.ui.activity.CarSignInActivity
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
        @StringRes labelRes: Int
    ): LibraryResult<ImmutableList<MediaItem>> {
        val label = context.getString(labelRes)
        return LibraryResult.ofError(
            SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, label),
            MediaLibraryService.LibraryParams.Builder()
                .setExtras(resolutionExtras(context, label))
                .build()
        )
    }

    private fun resolutionExtras(context: Context, label: String): Bundle = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, label)
        putParcelable(
            MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
            signInPendingIntent(context)
        )
    }

    private fun signInPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CarSignInActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
