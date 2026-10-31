package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.service.VoiceLogEntry
import com.cappielloantonio.tempo.ui.fragment.DebugVoiceFragment.Companion.RowLabels
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugVoiceRowsTest {
    private val labels = RowLabels(emptyQuery = "(no query)", noFocus = "no focus", pending = "pending")

    @Test
    fun `a row reads query, then outcome, then focus and time`() {
        val entry = VoiceLogEntry("aerials", "focus", "album Toxicity (14 tracks)", 7L, null, null, null)

        val row = DebugVoiceFragment.buildRows(listOf(entry), labels) { "T$it" }.single()

        assertEquals("aerials", row.title)
        assertEquals("album Toxicity (14 tracks)", row.subtitle)
        assertEquals("focus · T7", row.detail)
    }

    @Test
    fun `an empty query and a missing focus and outcome read as labels`() {
        val row = DebugVoiceFragment.buildRows(listOf(VoiceLogEntry("", null, null, 7L, null, null, null)), labels) { "T$it" }.single()

        assertEquals("(no query)", row.title)
        assertEquals("pending", row.subtitle)
        assertEquals("no focus · T7", row.detail)
    }

    @Test
    fun `a row lists the hints Assistant sent between focus and time`() {
        val entry = VoiceLogEntry("aerials", "focus", null, 7L, "System of a Down", null, "Aerials")

        val row = DebugVoiceFragment.buildRows(listOf(entry), labels) { "T$it" }.single()

        assertEquals("focus · artist=System of a Down · title=Aerials · T7", row.detail)
    }
}
