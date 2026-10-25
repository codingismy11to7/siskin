package com.cappielloantonio.tempo.ui.fragment

import android.app.Dialog
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
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
    private var bind: FragmentDebugAboutBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugAboutBinding.inflate(inflater, container, false)
        this.bind = bind
        bind.aboutVersion.text = versionLabel
        addChoice(bind.choiceContainer, getString(R.string.debug_about_credits)) {
            CreditsDialogFragment().show(childFragmentManager, CreditsDialogFragment.TAG)
        }
        return bind.root
    }

    /**
     * Rendered on every showing, not once: the shell hides and shows this tab
     * rather than recreating it, and "3 minutes ago" goes stale while hidden.
     */
    override fun onResume() {
        super.onResume()
        renderInstall()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) renderInstall()
    }

    override fun onDestroyView() {
        bind = null
        super.onDestroyView()
    }

    private fun renderInstall() {
        val bind = bind ?: return
        val updated = packageInfo().lastUpdateTime
        // A head unit's clock can lag until GNSS or the network sets it, which
        // would otherwise read as "In 5 minutes".
        val now = maxOf(System.currentTimeMillis(), updated)
        val ago = DateUtils.getRelativeTimeSpanString(updated, now, DateUtils.MINUTE_IN_MILLIS)
        val date = DateFormat.getDateFormat(requireContext()).format(updated)
        val time = DateFormat.getTimeFormat(requireContext()).format(updated)
        bind.aboutInstall.text = getString(R.string.debug_about_install, "$ago ($date $time)", installer())
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

    /** The raw package name: com.android.vending is Play, and an `adb install` records none. */
    private fun installer(): String {
        val packages = requireContext().packageManager
        val name = requireContext().packageName
        val installer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packages.getInstallSourceInfo(name).installingPackageName
            } else {
                // getInstallSourceInfo is API 30; this is the only form older head units have.
                @Suppress("DEPRECATION")
                packages.getInstallerPackageName(name)
            }
        return installer ?: getString(R.string.debug_about_no_installer)
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
        val body = DebugCreditsBinding.inflate(LayoutInflater.from(builder.context))
        body.creditsUrl.text = CREDITS_URL
        return builder
            .setTitle(R.string.debug_about_credits)
            .setView(body.root)
            .setPositiveButton(R.string.debug_credits_close, null)
            .create()
    }

    companion object {
        const val TAG = "credits"
    }
}

/** What qr_credits encodes, which QrDrawableTest holds it to. */
internal const val CREDITS_URL = "https://siskinapp.com/credits"
