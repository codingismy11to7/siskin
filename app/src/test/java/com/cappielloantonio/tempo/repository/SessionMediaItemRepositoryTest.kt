package com.cappielloantonio.tempo.repository

import com.cappielloantonio.tempo.model.SessionMediaItem
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.util.ConstantsAA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SessionMediaItemRepository end to end, through the real (singleton)
 * AppDatabase -- Robolectric because the assertions read the `timestamp`
 * column, which unitTests.returnDefaultValues would otherwise let default to
 * 0 for every row and make these tests pass against a broken cache().
 */
@RunWith(RobolectricTestRunner::class)
class SessionMediaItemRepositoryTest {

    private val repository = SessionMediaItemRepository()

    @Before
    fun clearTable() {
        // cache()'s writes and this delete all go through the same
        // single-threaded executor, so anything queued after this call in the
        // same test method is guaranteed to run after it completes.
        repository.deleteAll()
    }

    private fun track(ratingKey: String) = PlexMediaMapper.buildTrackMediaItem(
        ratingKey = ratingKey,
        title = "Track $ratingKey",
        albumTitle = null,
        artist = null,
        thumb = null,
        partKey = "/library/parts/$ratingKey/file.flac",
        durationMs = null,
        trackIndex = null,
        year = null,
        parentRatingKey = null,
        grandparentRatingKey = null,
        isHearted = false,
        parentId = ConstantsAA.QUEUE_CACHED_SOURCE,
        serverUri = "https://plex.example",
        token = "tok"
    )

    /** cache() writes land on a background executor; poll rather than sleep-and-hope. */
    private fun pollUntilFound(
        id: String,
        timeoutMs: Long = 3000,
        intervalMs: Long = 10
    ): SessionMediaItem {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            repository.get(id)?.let { return it }
            Thread.sleep(intervalMs)
        }
        throw AssertionError("cache() of '$id' never became visible within ${timeoutMs}ms")
    }

    @Test
    fun rapidCacheCallsNeverShareAGroup() {
        // Regression for the wall-clock sibling key: cache() used to key
        // sibling groups on System.currentTimeMillis(), so two browse nodes
        // cached inside the same millisecond would silently merge into one
        // sibling group. Four calls, issued back to back with no delay -- well
        // under the 5-group retention bound, so none of them get pruned before
        // the assertions run -- to exercise exactly that window.
        val ids = (1..4).map { "rapid-$it" }
        ids.forEach { repository.cache(listOf(track(it))) }

        val timestamps = ids.map { pollUntilFound(it).timestamp!! }

        assertEquals("every call must land in its own group", ids.size, timestamps.toSet().size)
        assertEquals("groups must be recorded in call order", timestamps, timestamps.sorted())
    }

    @Test
    fun retentionKeepsOnlyTheFiveMostRecentGroups() {
        val ids = (1..6).map { "group-$it" }
        ids.forEach { repository.cache(listOf(track(it))) }

        // Waiting for the newest group also waits for the whole FIFO queue --
        // including its prune step -- to flush, since cache() enqueues insert
        // and prune together per call.
        pollUntilFound(ids.last())

        assertNull("the oldest group must have been pruned", repository.get(ids.first()))
        for (id in ids.drop(1)) {
            assertTrue("recent group '$id' must survive", repository.get(id) != null)
        }
    }
}
