package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentPlexSignInBinding
import com.cappielloantonio.tempo.interfaces.LoginHost
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.button.MaterialButton

/**
 * The Plex PIN sign-in screen: a QR code and a short code, then a server picker
 * and a music-library picker.
 *
 * Both pickers render even for a single candidate. There is no settings surface,
 * so a wrong auto-pick would be unfixable short of reinstalling.
 */
class PlexSignInFragment : Fragment() {

    private var bind: FragmentPlexSignInBinding? = null
    private lateinit var viewModel: PlexSignInViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(requireActivity())[PlexSignInViewModel::class.java]
        bind = FragmentPlexSignInBinding.inflate(inflater, container, false)

        bind?.retryButton?.setOnClickListener { viewModel.retry() }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        viewModel.start()

        return bind!!.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bind = null
    }

    private fun render(state: PlexSignInState) {
        val bind = this.bind ?: return

        bind.progress.visibility = View.GONE
        bind.approvalGroup.visibility = View.GONE
        bind.choicePrompt.visibility = View.GONE
        bind.choiceContainer.removeAllViews()
        bind.errorText.visibility = View.GONE
        bind.retryButton.visibility = View.GONE

        when (state) {
            is PlexSignInState.Working -> bind.progress.visibility = View.VISIBLE

            is PlexSignInState.AwaitingApproval -> {
                bind.approvalGroup.visibility = View.VISIBLE
                bind.codeText.text = state.code
                // Hide the card, not just the image: the card supplies the white
                // the QR is drawn on, so leaving it up with no code in it shows a
                // blank white square rather than nothing.
                if (state.qrUrl != null) {
                    bind.qrCard.visibility = View.VISIBLE
                    Glide.with(this).load(state.qrUrl).into(bind.qrImage)
                } else {
                    bind.qrCard.visibility = View.GONE
                }
            }

            is PlexSignInState.ChoosingServer -> {
                bind.choicePrompt.visibility = View.VISIBLE
                bind.choicePrompt.setText(R.string.plex_sign_in_choose_server)
                state.servers.forEach { server ->
                    addChoice(server.name.orEmpty()) { viewModel.chooseServer(server) }
                }
            }

            is PlexSignInState.ChoosingLibrary -> {
                bind.choicePrompt.visibility = View.VISIBLE
                bind.choicePrompt.setText(R.string.plex_sign_in_choose_library)
                state.sections.forEach { section ->
                    addChoice(section.title.orEmpty()) { viewModel.chooseLibrary(section) }
                }
            }

            is PlexSignInState.Failed -> {
                bind.errorText.visibility = View.VISIBLE
                bind.errorText.setText(state.messageRes)
                bind.retryButton.visibility = View.VISIBLE
            }

            is PlexSignInState.Done -> (requireActivity() as LoginHost).onLoginSuccess()
        }
    }

    /**
     * Buttons in a LinearLayout rather than a RecyclerView: there are one to five
     * of them, and skipping the RecyclerView is what lets that dependency go.
     */
    private fun addChoice(label: String, onClick: () -> Unit) {
        val bind = this.bind ?: return
        val button = MaterialButton(requireContext()).apply {
            text = label
            minHeight = resources.getDimensionPixelSize(R.dimen.plex_sign_in_choice_min_height)
            // MaterialButton's default 6dp insets would shave 12dp off that
            // minHeight. Same reasoning as retry_button in the layout: the
            // oversized target is the point on a head unit.
            insetTop = 0
            insetBottom = 0
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            setOnClickListener { onClick() }
        }
        bind.choiceContainer.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.plex_sign_in_choice_gap)
            }
        )
    }
}
