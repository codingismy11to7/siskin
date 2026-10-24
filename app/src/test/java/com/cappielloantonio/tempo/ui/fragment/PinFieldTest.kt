package com.cappielloantonio.tempo.ui.fragment

import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.cappielloantonio.tempo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PinFieldTest {
    private val submitted = mutableListOf<String>()

    private fun field(): EditText {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        return addPinField(LinearLayout(activity)) { submitted += it }
    }

    @Test
    fun fewerThanFourDigitsDoNotSubmit() {
        field().setText("432")

        assertTrue(submitted.isEmpty())
    }

    @Test
    fun theFourthDigitSubmitsOnce() {
        val field = field()
        field.setText("432")
        field.append("1")

        assertEquals(listOf("4321"), submitted)
    }

    @Test
    fun doneWithFewerThanFourDigitsDoesNothing() {
        val field = field()
        field.setText("43")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)

        assertTrue(submitted.isEmpty())
    }

    @Test
    fun theFieldIsANumericPasswordCappedAtFour() {
        val field = field()

        assertEquals(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            field.inputType,
        )
        field.setText("123456")
        assertEquals("1234", field.text.toString())
        assertTrue(field.filters.any { it is InputFilter.LengthFilter && it.max == 4 })
    }
}
