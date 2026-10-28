package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.service.FailedTrack
import com.cappielloantonio.tempo.service.PlaybackFailurePolicy
import com.cappielloantonio.tempo.ui.fragment.DebugFailuresFragment.Companion.FailureRow
import com.cappielloantonio.tempo.ui.fragment.DebugFailuresFragment.Companion.RowLabels
import com.cappielloantonio.tempo.ui.fragment.DebugFailuresFragment.Companion.buildRows
import com.cappielloantonio.tempo.ui.fragment.DebugFailuresFragment.Companion.limitLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugFailuresRowsTest {
    private val labels = RowLabels(unknown = "(unknown)", skipped = "skipped", stopped = "stopped")

    private fun entry(
        title: String? = "Twister",
        artist: String? = "Weird Al",
        album: String? = "Squeeze Box",
        skipped: Boolean = true,
    ) = FailedTrack("1", title, artist, album, "HTTP 404", skipped, 7L, "abc123")

    @Test
    fun `a row reads title, then artist and album, then reason, outcome and time`() {
        val row = buildRows(listOf(entry()), labels) { "T$it" }.single()
        assertEquals(FailureRow("Twister", "Weird Al · Squeeze Box", "HTTP 404 · skipped · T7"), row)
    }

    @Test
    fun `missing metadata reads as unknown and a stop says stopped`() {
        val row = buildRows(listOf(entry(title = null, artist = null, album = null, skipped = false)), labels) { "T" }.single()
        assertEquals(FailureRow("(unknown)", "(unknown)", "HTTP 404 · stopped · T"), row)
    }

    @Test
    fun `an album without an artist drops the separator`() {
        val row = buildRows(listOf(entry(artist = null)), labels) { "T" }.single()
        assertEquals("Squeeze Box", row.subtitle)
    }

    @Test
    fun `limit labels`() {
        assertEquals("3", limitLabel(3, never = "Never"))
        assertEquals("Never", limitLabel(PlaybackFailurePolicy.NEVER, never = "Never"))
    }
}
