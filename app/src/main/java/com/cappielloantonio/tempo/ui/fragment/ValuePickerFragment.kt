package com.cappielloantonio.tempo.ui.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentValuePickerBinding
import com.cappielloantonio.tempo.service.QueuePreloader
import com.cappielloantonio.tempo.util.CacheSizeChoice
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.StreamingCacheChoices

/** One option as a picker shows it. */
internal class PickerOption(
    val label: String,
    val selected: Boolean,
    val choose: (Context) -> Unit,
)

/**
 * Every Settings value that is chosen rather than toggled. A new one (#174's
 * Mix track limit) is a new entry here, not a new screen.
 */
@UnstableApi
enum class ValuePicker(
    @StringRes val title: Int,
    @StringRes val explainer: Int,
) {
    CACHE_SIZE(R.string.car_settings_cache_size, R.string.car_settings_cache_size_explainer) {
        override fun options(context: Context): List<PickerOption> {
            val automatic = DownloadUtil.getAutomaticStreamingCacheSizeMegabytes(context)
            val partition = DownloadUtil.getStreamingCachePartitionMegabytes(context)
            val current = StreamingCacheChoices.current(Preferences.getStreamingCacheSizeOverrideMegabytes(), automatic)
            return StreamingCacheChoices.cacheSizeChoices(automatic, partition).map { choice ->
                PickerOption(
                    StreamingCacheChoices.cacheSizeLabel(context.resources, choice),
                    selected = choice == current,
                ) {
                    Preferences.setStreamingCacheSizeOverrideMegabytes(
                        when (choice) {
                            is CacheSizeChoice.Automatic -> null
                            is CacheSizeChoice.Fixed -> choice.megabytes
                            CacheSizeChoice.Off -> 0L
                        },
                    )
                    DownloadUtil.applyStreamingCacheSizeInBackground(it)
                    if (choice == CacheSizeChoice.Off) QueuePreloader.cancel()
                }
            }
        }
    },
    PRECACHE(R.string.car_settings_precache, R.string.car_settings_precache_explainer) {
        override fun options(context: Context): List<PickerOption> {
            val current = Preferences.getPrecacheTracksCount()
            return StreamingCacheChoices.PRECACHE_COUNTS.map { count ->
                PickerOption(StreamingCacheChoices.precacheLabel(context.resources, count), count == current) {
                    Preferences.setPrecacheTracksCount(count)
                    // A batch already running was sized by the old count; the
                    // next player event starts one at the new count.
                    QueuePreloader.cancel()
                }
            }
        }
    }, ;

    internal abstract fun options(context: Context): List<PickerOption>
}

/**
 * Pushed from Settings onto CarHostActivity's back stack, like Customize tabs,
 * so back returns there and CarHostActivity's parked-only restriction covers it.
 * Choosing an option writes it and pops; Settings rebuilds its rows from
 * preferences on the way back.
 */
@UnstableApi
class ValuePickerFragment : Fragment() {
    companion object {
        private const val ARG_PICKER = "picker"

        fun newInstance(picker: ValuePicker) =
            ValuePickerFragment().apply {
                arguments = Bundle().apply { putString(ARG_PICKER, picker.name) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val picker = ValuePicker.valueOf(requireArguments().getString(ARG_PICKER)!!)
        val bind = FragmentValuePickerBinding.inflate(inflater, container, false)
        bind.title.setText(picker.title)
        bind.explainer.setText(picker.explainer)
        for (option in picker.options(requireContext())) {
            addRadioRow(bind.choiceContainer, option.label, option.selected) {
                option.choose(requireContext().applicationContext)
                parentFragmentManager.popBackStack()
            }
        }
        return bind.root
    }
}
