package com.cappielloantonio.tempo.ui.fragment

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PinPadTest {
    private fun buttons(view: View): List<MaterialButton> =
        when (view) {
            is MaterialButton -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun pad(onComplete: (String) -> Unit): ViewGroup {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(com.cappielloantonio.tempo.R.style.AppTheme)
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        addPinPad(container, "Delete", onComplete)
        return container
    }

    private fun ViewGroup.press(label: String) = buttons(this).single { it.text == label || it.contentDescription == label }.performClick()

    @Test
    fun submitsOnTheFourthDigit() {
        val entered = mutableListOf<String>()
        val pad = pad { entered += it }

        listOf("4", "3", "2").forEach { pad.press(it) }
        assertEquals(emptyList<String>(), entered)
        pad.press("1")

        assertEquals(listOf("4321"), entered)
    }

    @Test
    fun deleteRemovesTheLastDigit() {
        val entered = mutableListOf<String>()
        val pad = pad { entered += it }

        listOf("4", "3", "9").forEach { pad.press(it) }
        pad.press("Delete")
        listOf("2", "1").forEach { pad.press(it) }

        assertEquals(listOf("4321"), entered)
    }

    @Test
    fun deleteOnAnEmptyEntryDoesNothing() {
        val entered = mutableListOf<String>()
        val pad = pad { entered += it }

        pad.press("Delete")
        listOf("4", "3", "2", "1").forEach { pad.press(it) }

        assertEquals(listOf("4321"), entered)
    }
}
