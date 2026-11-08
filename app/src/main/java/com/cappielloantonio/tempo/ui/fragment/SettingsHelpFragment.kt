package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.databinding.FragmentSettingsHelpBinding

/**
 * A Settings category's section of docs/web/settings-help.md, bundled by the
 * bundleSettingsHelp task in app/build.gradle. English in every locale. See
 * docs/decisions/2026-11-08-settings-help-design.md.
 */
internal class SettingsHelpFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentSettingsHelpBinding.inflate(inflater, container, false)
        val category = SettingsCategory.valueOf(requireArguments().getString(ARG_CATEGORY)!!)
        bind.settingsHelpHeading.setText(category.label)
        val markdown =
            requireContext()
                .assets
                .open(ASSET)
                .bufferedReader()
                .use { it.readText() }
        // SettingsHelpAssetTest holds every category with a Help button to a section.
        val section = CarMarkdown.section(markdown, category.helpId!!).orEmpty()
        bind.settingsHelpText.showMarkdown(CarMarkdown.parse(section))
        // Nothing in the text can take focus, so a rotary controller stops on
        // the scroll view itself, and turning it moves focus off the screen
        // unless this marker tells the car's RotaryService to scroll instead.
        // A screen reader reads the marker as the view's label, which is the
        // cost of car-ui-lib's convention.
        bind.settingsHelpScroll.contentDescription = ROTARY_VERTICALLY_SCROLLABLE
        // RotaryService scrolls by injecting AXIS_VSCROLL from a rotary
        // encoder, and ScrollView reads only AXIS_SCROLL from one, so without
        // this a turn reaches the view and moves nothing. At either end the
        // turn is left unconsumed, for RotaryService to move focus with.
        val step = ViewConfiguration.get(requireContext()).scaledVerticalScrollFactor
        bind.settingsHelpScroll.setOnGenericMotionListener { view, event ->
            val detents = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val turned =
                event.action == MotionEvent.ACTION_SCROLL &&
                    event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) &&
                    detents != 0f
            // A positive detent scrolls up, toward the top.
            val direction = if (detents > 0f) -1 else 1
            val scrolls = turned && view.canScrollVertically(direction)
            if (scrolls) view.scrollBy(0, -(detents * step).toInt())
            scrolls
        }
        return bind.root
    }

    companion object {
        const val ASSET = "settings-help.md"
        private const val ARG_CATEGORY = "category"

        // car-ui-lib's RotaryConstants, which Siskin does not depend on.
        const val ROTARY_VERTICALLY_SCROLLABLE = "com.android.car.ui.utils.VERTICALLY_SCROLLABLE"

        fun newInstance(category: SettingsCategory) =
            SettingsHelpFragment().apply { arguments = Bundle().apply { putString(ARG_CATEGORY, category.name) } }
    }
}
