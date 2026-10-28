package com.cappielloantonio.tempo.service

import com.cappielloantonio.tempo.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FailedTrackLogTest {
    @Before
    fun setUp() {
        FailedTrackLog.clear()
        Preferences.setFailureLimit(3)
    }

    private fun track(
        key: String,
        at: Long = 0L,
    ) = FailedTrack(
        ratingKey = key,
        title = "Title $key",
        artist = "Artist",
        album = null,
        reason = "HTTP 404",
        skipped = true,
        atMillis = at,
        server = "abc123",
    )

    @Test
    fun `an added entry reads back whole`() {
        FailedTrackLog.add(track("1", at = 42L))
        assertEquals(listOf(track("1", at = 42L)), FailedTrackLog.all())
    }

    @Test
    fun `newest comes first`() {
        FailedTrackLog.add(track("1"))
        FailedTrackLog.add(track("2"))
        assertEquals(listOf("2", "1"), FailedTrackLog.all().map { it.ratingKey })
    }

    @Test
    fun `only the newest MAX are kept`() {
        repeat(FailedTrackLog.MAX + 5) { FailedTrackLog.add(track("$it")) }
        val keys = FailedTrackLog.all().map { it.ratingKey }
        assertEquals(FailedTrackLog.MAX, keys.size)
        assertEquals("${FailedTrackLog.MAX + 4}", keys.first())
        assertEquals("5", keys.last())
    }

    @Test
    fun `clear empties it`() {
        FailedTrackLog.add(track("1"))
        FailedTrackLog.clear()
        assertTrue(FailedTrackLog.all().isEmpty())
    }

    @Test
    fun `every entry is unseen until marked seen`() {
        FailedTrackLog.add(track("1", at = 10L))
        FailedTrackLog.add(track("2", at = 20L))
        assertEquals(2, FailedTrackLog.unseenCount())

        FailedTrackLog.markSeen()
        assertEquals(0, FailedTrackLog.unseenCount())
    }

    @Test
    fun `only failures after the last look are unseen`() {
        FailedTrackLog.add(track("1", at = 10L))
        FailedTrackLog.markSeen()
        FailedTrackLog.add(track("2", at = 20L))
        assertEquals(1, FailedTrackLog.unseenCount())
    }

    @Test
    fun `a failure stamped before one already seen still counts`() {
        FailedTrackLog.add(track("1", at = 1_000L))
        FailedTrackLog.markSeen()
        // The clock went backwards, e.g. a head unit booting before its time sync.
        FailedTrackLog.add(track("2", at = 10L))
        assertEquals(1, FailedTrackLog.unseenCount())
    }

    @Test
    fun `unseen never exceeds what the log holds`() {
        repeat(FailedTrackLog.MAX + 5) { FailedTrackLog.add(track("$it")) }
        assertEquals(FailedTrackLog.MAX, FailedTrackLog.unseenCount())
    }

    @Test
    fun `clear also forgets what was seen`() {
        FailedTrackLog.add(track("1", at = 10L))
        FailedTrackLog.markSeen()
        FailedTrackLog.clear()
        FailedTrackLog.add(track("2", at = 5L))
        assertEquals(1, FailedTrackLog.unseenCount())
    }

    private fun stopped(key: String) = track(key).copy(skipped = false)

    @Test
    fun `markNewestSkipped flips the newest entry when it is that track`() {
        FailedTrackLog.add(stopped("1"))
        FailedTrackLog.add(stopped("2"))
        FailedTrackLog.markNewestSkipped("2")
        assertEquals(listOf(true, false), FailedTrackLog.all().map { it.skipped })
    }

    @Test
    fun `markNewestSkipped leaves an older entry for that track alone`() {
        FailedTrackLog.add(stopped("1"))
        FailedTrackLog.add(stopped("2"))
        FailedTrackLog.markNewestSkipped("1")
        assertEquals(listOf(false, false), FailedTrackLog.all().map { it.skipped })
    }

    @Test
    fun `markNewestSkipped does not count as a new failure`() {
        FailedTrackLog.add(stopped("1"))
        FailedTrackLog.markNewestSkipped("1")
        assertEquals(1, FailedTrackLog.unseenCount())
    }

    @Test
    fun `markNewestSkipped on an empty log does nothing`() {
        FailedTrackLog.markNewestSkipped("1")
        assertTrue(FailedTrackLog.all().isEmpty())
    }

    @Test
    fun `an unreadable stored value reads as empty`() {
        com.cappielloantonio.tempo.App
            .getInstance()
            .preferences
            .edit()
            .putString(FailedTrackLog.KEY, "not json")
            .commit()
        assertTrue(FailedTrackLog.all().isEmpty())
    }

    @Test
    fun `the failure limit defaults to 3 and round-trips`() {
        com.cappielloantonio.tempo.App
            .getInstance()
            .preferences
            .edit()
            .remove("failure_limit")
            .commit()
        assertEquals(3, Preferences.getFailureLimit())
        Preferences.setFailureLimit(PlaybackFailurePolicy.NEVER)
        assertEquals(PlaybackFailurePolicy.NEVER, Preferences.getFailureLimit())
    }
}
