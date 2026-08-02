package com.cappielloantonio.tempo.ui.fragment

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentPlexSignInBinding
import com.cappielloantonio.tempo.interfaces.LoginHost
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

private const val TAG = "PlexSignInFragment"

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

        bind?.retryButton?.setOnClickListener {
            when (viewModel.state.value) {
                is PlexSignInState.Disconnected -> viewModel.connect()
                else -> viewModel.retry()
            }
        }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }

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
        bind.choiceContainer.removeAllViews()
        bind.errorText.visibility = View.GONE
        bind.retryButton.visibility = View.GONE

        // The tagline above this is constant; this line names the step.
        // Disconnected, Working and Failed all keep the connect wording:
        // Disconnected is before connecting has started, and Working and
        // Failed are moments inside it -- none of the three is a step of its
        // own the way choosing a server or a library is.
        bind.stepHeading.setText(
            when (state) {
                is PlexSignInState.Connected -> R.string.car_settings_title
                is PlexSignInState.ChoosingServer -> R.string.plex_sign_in_choose_server
                is PlexSignInState.ChoosingLibrary -> R.string.plex_sign_in_choose_library
                else -> R.string.plex_sign_in_connect
            }
        )

        applyArrangement(
            isPicker = state is PlexSignInState.ChoosingServer ||
                state is PlexSignInState.ChoosingLibrary
        )

        when (state) {
            is PlexSignInState.Disconnected -> {
                bind.errorText.visibility = View.VISIBLE
                bind.errorText.setText(R.string.car_sign_in_required)
                bind.retryButton.visibility = View.VISIBLE
                bind.retryButton.setText(R.string.car_sign_in_action)
            }

            // Reachable via open(forceSignIn = false) when a session already
            // exists -- the gear's entry point. addChoice is reused rather than
            // a new button: it already applies the oversized head-unit tap
            // target and the colorOnPrimary fix.
            is PlexSignInState.Connected -> addChoice(getString(R.string.car_settings_sign_out)) {
                viewModel.signOut()
                (requireActivity() as LoginHost).onSignedOut()
            }

            is PlexSignInState.Working -> bind.progress.visibility = View.VISIBLE

            is PlexSignInState.AwaitingApproval -> {
                bind.codeText.text = state.code

                // Show the code-only form straight away, even when a QR is
                // coming: the code works the moment it exists, so there is no
                // reason to make someone wait on an image to start typing it.
                // The QR is an upgrade that arrives, not a precondition. It also
                // means the card is never on screen empty -- a large white
                // rectangle on a dark screen reads as a broken image.
                showApproval(withQr = false)

                if (state.qrUrl != null) {
                    Glide.with(this)
                        .load(state.qrUrl)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                // Nothing to do: the code-only form is already up
                                // and it signs the user in on its own. Logged
                                // because a persistent failure here is worth
                                // knowing about -- it means Plex changed the qr
                                // field, which this design assumes.
                                Log.d(TAG, "QR image failed to load", e)
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                showApproval(withQr = true)
                                return false
                            }
                        })
                        .into(bind.qrImage)
                }
            }

            is PlexSignInState.ChoosingServer -> {
                // Set only when the user is back here because the server they
                // picked was unusable. retryButton stays hidden: the list is
                // the recovery, and there is nothing to retry -- only a
                // different choice to make.
                state.messageRes?.let {
                    bind.errorText.visibility = View.VISIBLE
                    bind.errorText.setText(it)
                }
                state.servers.forEach { server ->
                    addChoice(server.name.orEmpty()) { viewModel.chooseServer(server) }
                }
            }

            is PlexSignInState.ChoosingLibrary -> state.sections.forEach { section ->
                addChoice(section.title.orEmpty()) { viewModel.chooseLibrary(section) }
            }

            is PlexSignInState.Failed -> {
                bind.errorText.visibility = View.VISIBLE
                bind.errorText.setText(state.messageRes)
                bind.retryButton.visibility = View.VISIBLE
                bind.retryButton.setText(R.string.plex_sign_in_retry)
            }

            is PlexSignInState.Done -> (requireActivity() as LoginHost).onLoginSuccess()
        }
    }

    /**
     * Reveals the approval step in one of its two forms.
     *
     * Without the QR: the code alone, and instructions that do not mention a QR
     * that is not on screen. This is what shows first in every case -- while the
     * image loads, and permanently if it never does. With the QR: the card
     * appears and the instructions grow the "scan this" half.
     */
    private fun showApproval(withQr: Boolean) {
        val bind = this.bind ?: return
        bind.progress.visibility = View.GONE
        bind.approvalGroup.visibility = View.VISIBLE
        bind.qrCard.visibility = if (withQr) View.VISIBLE else View.GONE
        bind.instructions.setText(
            if (withQr) R.string.plex_sign_in_instructions
            else R.string.plex_sign_in_instructions_no_qr
        )
        // Beside the QR the text is a left-aligned block, so the code sits under
        // the instruction line's left edge and belongs there. With no QR that
        // block becomes the whole content and is centred, and a short code still
        // pinned to a long line's left edge is then the one thing on screen that
        // is off-centre.
        (bind.codeText.layoutParams as LinearLayout.LayoutParams).gravity =
            if (withQr) Gravity.START else Gravity.CENTER_HORIZONTAL
        bind.codeText.requestLayout()
    }

    /**
     * The screen has two arrangements, and they are structural rather than a
     * matter of gravity.
     *
     * A picker is an open-ended list, so the headings pin to the top and only
     * the list scrolls beneath them: the question stays on screen while you
     * answer it, and the first tap target sits in the same place whether the
     * account has one server or eight.
     *
     * Every other state is a single short block, and those read best as one
     * centred composition -- headings included. So the scroll view shrinks to
     * its content and the whole column centres, which is only safe because
     * these states are known to be short enough never to need scrolling.
     */
    private fun applyArrangement(isPicker: Boolean) {
        val bind = this.bind ?: return
        val params = bind.contentScroll.layoutParams as LinearLayout.LayoutParams

        if (isPicker) {
            params.height = 0
            params.weight = 1f
        } else {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.weight = 0f
        }
        bind.contentScroll.layoutParams = params

        val gravity =
            if (isPicker) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        (bind.root as LinearLayout).gravity = gravity
        bind.scrollContent.gravity = gravity
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
            // setTextAppearance carries its own textColor, which clobbers the
            // colorOnPrimary a filled button would otherwise use -- leaving pale
            // text on a pale fill. Restore it after, not before.
            setTextColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
            )
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
