package com.cappielloantonio.tempo.ui.fragment

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.DebugCreditsBinding
import com.cappielloantonio.tempo.databinding.FragmentDebugAboutBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The debug screen's About tab, and the tab it opens on: which build this is,
 * when it landed and what installed it, and a Credits button pointing at
 * siskinapp.com/credits. See docs/decisions/2026-10-25-debug-about-tab-design.md.
 */
class DebugAboutFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugAboutBinding.inflate(inflater, container, false)

        // Not prose, as in Settings' version line.
        //noinspection SetTextI18n
        bind.aboutVersion.text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        val updated = packageInfo().lastUpdateTime
        val ago = DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val date = DateFormat.getDateFormat(requireContext()).format(updated)
        val time = DateFormat.getTimeFormat(requireContext()).format(updated)
        val at = "$date $time"
        bind.aboutInstall.text = getString(R.string.debug_about_install, "$ago ($at)", installer())

        addChoice(bind.choiceContainer, getString(R.string.debug_about_credits)) { showCredits() }

        return bind.root
    }

    private fun packageInfo(): PackageInfo {
        val packages = requireContext().packageManager
        val name = requireContext().packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
        } else {
            // The flags overload is API 33; this is the only form older head units have.
            @Suppress("DEPRECATION")
            packages.getPackageInfo(name, 0)
        }
    }

    /**
     * The raw package name: com.android.vending is Play, and an `adb install`
     * records none. Only API 30 and up have a non-deprecated way to ask.
     */
    private fun installer(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return getString(R.string.debug_about_installer_unknown)
        val context = requireContext()
        return context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            ?: getString(R.string.debug_about_no_installer)
    }

    private fun showCredits() {
        val body = DebugCreditsBinding.inflate(layoutInflater)
        body.creditsUrl.text = CREDITS_URL
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.debug_about_credits)
            .setView(body.root)
            .setPositiveButton(R.string.debug_credits_close, null)
            .show()
    }
}

/** What qr_credits encodes, which QrDrawableTest holds it to. */
internal const val CREDITS_URL = "https://siskinapp.com/credits"
