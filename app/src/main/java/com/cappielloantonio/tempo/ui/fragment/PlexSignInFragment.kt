package com.cappielloantonio.tempo.ui.fragment

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
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

private const val TAG = "PlexSignInFragment"

/**
 * The Plex PIN sign-in screen: a QR code and a short code, then a server picker
 * and a music-library picker.
 *
 * Both pickers render even for a single candidate. [CarSettingsFragment], where
 * you land once signed in, offers no way to switch server or library afterwards,
 * so a wrong auto-pick here would mean redoing the whole PIN flow to fix.
 *
 * This fragment renders every state except
 * [PlexSignInState.Connected] -- [CarHostActivity] routes that one to
 * [CarSettingsFragment] instead.
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

        // Starts disabled -- matching the ViewModel's own initial state,
        // Disconnected, which viewModel.handlesBackPress reports false for --
        // and is re-armed per state below rather than left always-enabled.
        // An always-enabled callback would intercept Disconnected's presses
        // and swallow them, since backPressed() does nothing for it; disabling
        // it there instead lets the dispatcher fall through to its default
        // (finish the activity), which is the whole point of gating it the way
        // CarHostActivity's back button already does.
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                viewModel.backPressed()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewModel.state.observe(viewLifecycleOwner) {
            backCallback.isEnabled = viewModel.handlesBackPress(it)
            render(it)
        }

        return bind!!.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bind = null
    }

    @OptIn(UnstableApi::class)
    private fun render(state: PlexSignInState) {
        val bind = this.bind ?: return

        bind.progress.visibility = View.GONE
        bind.approvalGroup.visibility = View.GONE
        bind.choiceContainer.removeAllViews()
        bind.errorText.visibility = View.GONE
        bind.retryButton.visibility = View.GONE

        bind.stepHeading.setText(
            when (state) {
                is PlexSignInState.ChoosingServer -> R.string.plex_sign_in_choose_server
                is PlexSignInState.ChoosingLibrary -> R.string.plex_sign_in_choose_library
                else -> R.string.plex_sign_in_connect
            }
        )

        applyArrangement(
            isOpenEndedList = state is PlexSignInState.ChoosingServer ||
                state is PlexSignInState.ChoosingLibrary
        )

        when (state) {
            is PlexSignInState.Disconnected -> {
                bind.errorText.visibility = View.VISIBLE
                bind.errorText.setText(R.string.car_sign_in_required)
                bind.retryButton.visibility = View.VISIBLE
                bind.retryButton.setText(R.string.car_sign_in_action)
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
                    addChoice(bind.choiceContainer, server.name.orEmpty()) {
                        viewModel.chooseServer(server)
                    }
                }
            }

            is PlexSignInState.ChoosingLibrary -> state.sections.forEach { section ->
                addChoice(bind.choiceContainer, section.title.orEmpty()) {
                    viewModel.chooseLibrary(section)
                }
            }

            is PlexSignInState.Failed -> {
                bind.errorText.visibility = View.VISIBLE
                bind.errorText.setText(state.messageRes)
                bind.retryButton.visibility = View.VISIBLE
                bind.retryButton.setText(R.string.plex_sign_in_retry)
            }

            is PlexSignInState.Done -> (requireActivity() as LoginHost).onLoginSuccess()

            // Settings, which is CarSettingsFragment's screen and not this
            // one's. Still reachable here for one pass: CarHostActivity
            // observes the same LiveData and registered first, so it has
            // already committed the swap by the time this runs -- but a
            // FragmentTransaction is not synchronous, so this fragment is still
            // the one on screen for this emission. Drawing anything would be
            // drawing a screen about to be replaced; the cleared views above
            // are the right thing to leave behind.
            is PlexSignInState.Connected -> Unit
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
     * An open-ended list pins the headings to the top and scrolls only the list
     * beneath them: the heading stays on screen while you work under it, and
     * the first tap target sits in the same place whether the list holds one
     * item or eight. The server and library pickers qualify because an account
     * can have any number of either.
     *
     * Every other state here is a single short block, and those read best as one
     * centred composition -- headings included. So the scroll view shrinks to
     * its content and the whole column centres, which is only safe because
     * these states are known to be short enough never to need scrolling.
     *
     * Settings used to be the third case that wanted the list arrangement, and
     * it still wants it -- `fragment_car_settings` just declares it statically
     * now, because that screen has only ever had the one arrangement to choose.
     *
     * Pinning the headings to the top is also what puts them under
     * activity_car_host's back button, which overlays this fragment at
     * top|start: only the pinned arrangement needs the offset that clears it.
     */
    private fun applyArrangement(isOpenEndedList: Boolean) {
        val bind = this.bind ?: return
        val params = bind.contentScroll.layoutParams as LinearLayout.LayoutParams

        if (isOpenEndedList) {
            params.height = 0
            params.weight = 1f
        } else {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.weight = 0f
        }
        bind.contentScroll.layoutParams = params

        val gravity =
            if (isOpenEndedList) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        bind.root.gravity = gravity
        bind.scrollContent.gravity = gravity
    }
}
