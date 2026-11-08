package com.cappielloantonio.tempo.ui.fragment

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.SettingsAboutBinding
import com.cappielloantonio.tempo.databinding.SettingsCreditsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Settings' About category: which build this is, when it landed, and Credits.
 * Views in Settings' pane rather than a child fragment, because Settings'
 * child fragment manager is the pane screens' back stack. See
 * docs/decisions/2026-11-07-settings-about-design.md.
 */
internal class SettingsAbout(
    container: ViewGroup,
    dialogs: FragmentManager,
    openDebug: () -> Unit,
) {
    private val bind = SettingsAboutBinding.inflate(LayoutInflater.from(container.context), container, true)

    // Read once: an update replaces the process, so it cannot change while
    // this is alive, and the read is a binder call on the main thread.
    private val updated = packageInfo(container.context).lastUpdateTime

    init {
        bind.aboutVersion.text = versionLabel
        bind.aboutVersion.setOnClickListener { openDebug() }
        // A click makes a TextView clickable but not focusable, and a rotary
        // controller stops only on focusable views: without this the debug
        // screen is unreachable on a rotary-only head unit.
        bind.aboutVersion.isFocusable = true
        applyPressFeedback(bind.aboutVersion)
        addChoice(bind.aboutChoices, container.context.getString(R.string.car_settings_about_credits)) {
            CreditsDialogFragment().show(dialogs, CreditsDialogFragment.TAG)
        }
    }

    /** Run each time About is shown, since "3 minutes ago" goes stale. */
    fun render() {
        val context = bind.root.context
        // A head unit's clock can lag until GNSS or the network sets it, which
        // would otherwise read as "In 5 minutes".
        val now = maxOf(System.currentTimeMillis(), updated)
        val ago = DateUtils.getRelativeTimeSpanString(updated, now, DateUtils.MINUTE_IN_MILLIS)
        val date = DateFormat.getDateFormat(context).format(updated)
        val time = DateFormat.getTimeFormat(context).format(updated)
        bind.aboutUpdated.text = context.getString(R.string.car_settings_about_updated, ago, date, time)
    }
}

private fun packageInfo(context: Context): PackageInfo {
    val packages = context.packageManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        // The flags overload is API 33; this is the only form older head units have.
        @Suppress("DEPRECATION")
        packages.getPackageInfo(context.packageName, 0)
    }
}

/**
 * A DialogFragment rather than a bare dialog so that it survives the activity
 * being recreated, which a day/night switch does.
 */
class CreditsDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
        // The builder's context carries the dialog's theme overlay; the
        // fragment's own inflater would style the body for the activity.
        //noinspection UseGetLayoutInflater
        val body = SettingsCreditsBinding.inflate(LayoutInflater.from(builder.context))
        body.creditsUrl.text = CREDITS_URL
        return builder
            .setTitle(R.string.car_settings_about_credits)
            .setView(body.root)
            .setPositiveButton(R.string.car_settings_about_credits_close, null)
            .create()
    }

    companion object {
        const val TAG = "credits"
    }
}

/** What qr_credits encodes, which QrDrawableTest holds it to. */
internal const val CREDITS_URL = "https://siskinapp.com/credits"
