package com.cappielloantonio.tempo.ui.fragment

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.cappielloantonio.tempo.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

/*
 * The rows both car screens build into their `choice_container`.
 *
 * One file rather than two because all three functions here answer the same
 * question -- what a control has to look like to be operated at arm's length,
 * in a car -- and the answer is shared even where the callers are not.
 * [addChoice] is genuinely used by both screens: the server and library pickers
 * build their lists with it, and settings builds Customize tabs and Sign out
 * with it. [addToggle] and [applyPressFeedback] are settings' alone today, and
 * live here anyway so that a reader comparing a switch row against a button row
 * finds the two decisions side by side rather than in different files.
 *
 * Free functions taking the container, rather than members of a base fragment:
 * there is nothing to inherit here beyond these three calls, and a shared
 * superclass would be a second, invisible reason the two screens are coupled --
 * which is what splitting them was meant to remove.
 */

/**
 * Buttons in a LinearLayout rather than a RecyclerView: there are one to five
 * of them, and a RecyclerView would buy either screen nothing.
 *
 * No longer a project-wide rule about the dependency -- BrowseTabOrderFragment
 * uses RecyclerView deliberately, for ItemTouchHelper's drag and auto-scroll.
 * These screens simply do not need it.
 */
internal fun addChoice(
    container: ViewGroup,
    label: String,
    onClick: () -> Unit,
): MaterialButton {
    val resources = container.resources
    val button =
        MaterialButton(container.context).apply {
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
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary),
            )
            setOnClickListener { onClick() }
        }
    container.addView(
        button,
        LinearLayout
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.plex_sign_in_choice_gap)
            },
    )
    return button
}

/**
 * A settings row: label at the start, switch at the end, and **the row is the
 * tap target, not the thumb**. Same arm's-length reasoning that zeroes
 * MaterialButton's insets in [addChoice] -- a MaterialSwitch's thumb is a
 * phone-sized target and this is a head unit.
 *
 * The switch is therefore not clickable itself, and that is what makes the
 * row the target rather than merely the larger of two.
 * `SwitchCompat.onTouchEvent` delegates to `View.onTouchEvent`, which
 * returns false for a view that is neither clickable nor long-clickable --
 * so a non-clickable switch never consumes the touch, ViewGroup dispatch
 * falls through to the row, and the row's listener runs exactly once
 * wherever the tap landed, thumb included. A switch left clickable would
 * instead consume ACTION_DOWN and flip itself, the row's listener would
 * never run, and the switch would show one thing while the preference said
 * another.
 *
 * `fragment_car_settings` pins its headings and gives the list all the
 * remaining height, which it did back when the screen held nothing but a Sign
 * out button -- precisely so that a row's arrival would cost nothing. Four have
 * since arrived: continuous play, replay gain, artists-by-initial and Customize
 * tabs, and adding each was only adding a call. Transcoding is still to come
 * and is meant to be the same.
 */
internal fun addToggle(
    container: ViewGroup,
    label: String,
    initial: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = container.context
    val resources = container.resources

    val toggle =
        MaterialSwitch(context).apply {
            isChecked = initial
            isClickable = false
            isFocusable = false
        }

    val text =
        TextView(context).apply {
            this.text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        }

    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.plex_sign_in_choice_min_height)

            // A bare LinearLayout draws nothing when pressed. At arm's length a
            // 72dp target that does not acknowledge the press reads as a dead
            // control -- and addChoice's filled button ripples right beneath
            // this one, so the row has to as well.
            applyPressFeedback(this)

            // setOnClickListener makes a view clickable but not focusable, and a
            // rotary controller stops only on focusable views -- without this it
            // skips the toggle entirely on its way to the Sign out button below.
            isFocusable = true

            // choice_container is a bare 480dp column with no padding of its
            // own, so unpadded the label would start flush at x=0 while Sign
            // out's text is centred inside its button -- two rows that do not
            // read as one list. Symmetric, so the switch is inset from the far
            // edge by the same amount.
            val pad = resources.getDimensionPixelSize(R.dimen.plex_sign_in_row_padding)
            setPaddingRelative(pad, 0, pad, 0)

            addView(
                text,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                toggle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnClickListener {
                toggle.isChecked = !toggle.isChecked
                onChange(toggle.isChecked)
            }
        }

    container.addView(
        row,
        LinearLayout
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.plex_sign_in_choice_gap)
            },
    )
}

/**
 * Resolves the theme's ripple for `selectableItemBackground` and applies it
 * as [view]'s background. Shared by the version line and the toggle rows in
 * [addToggle]: both are views a click makes clickable but that draw nothing
 * of their own when pressed, which at arm's length reads as a dead control.
 */
internal fun applyPressFeedback(view: View) {
    val ripple = TypedValue()
    view.context.theme.resolveAttribute(
        com.google.android.material.R.attr.selectableItemBackground,
        ripple,
        true,
    )
    view.setBackgroundResource(ripple.resourceId)
}
