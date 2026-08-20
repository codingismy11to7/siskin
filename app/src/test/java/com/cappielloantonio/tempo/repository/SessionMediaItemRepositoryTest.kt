package com.cappielloantonio.tempo.repository

import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.SessionMediaItem
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SessionMediaItemRepository end to end, through the real (singleton)
 * AppDatabase -- Robolectric rather than plain JUnit for two reasons, not the
 * "timestamp defaults to 0" one an earlier version of this comment claimed
 * (unitTests.returnDefaultValues stubs android.jar methods, not a Kotlin
 * entity field, so it was never going to touch `timestamp`). The real
 * reasons: Room.databaseBuilder needs a live Context and a real SQLite
 * engine, which only exist here under Robolectric; and the `track()` fixture
 * below calls PlexMediaMapper.buildTrackMediaItem, which builds a Uri and a
 * Bundle -- android.jar stubs that returnDefaultValues would otherwise
 * silently no-op or NPE on.
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

    private fun track(ratingKey: String) =
        PlexMediaMapper.buildTrackMediaItem(
            ratingKey = ratingKey,
            title = "Track $ratingKey",
            albumTitle = null,
            artist = null,
            thumb = null,
            partKey = "/library/parts/$ratingKey/file.flac",
            durationMs = null,
            trackIndex = null,
            year = null,
            grandparentRatingKey = null,
            isHearted = false,
            parentId = Constants.QUEUE_CACHED_SOURCE,
            serverUri = "https://plex.example",
            token = "tok",
        )

    /** cache() writes land on a background executor; poll rather than sleep-and-hope. */
    private fun pollUntilFound(
        id: String,
        timeoutMs: Long = 3000,
        intervalMs: Long = 10,
        repository: SessionMediaItemRepository = this.repository,
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

    @Test
    fun survivesACrossProcessBackwardsClockStep() {
        // Regression for the cross-process clock-step bug: groupSequence (a
        // process-static AtomicLong) seeds from System.currentTimeMillis()
        // once per process, but a force-stop skips releasePlayers() ->
        // deleteAll(), so rows from the previous process are still on disk
        // when this process's repository is constructed. If the clock moved
        // backwards between processes -- a head unit cold-booting before time
        // sync, or a manual date change -- the new seed sits below the stale
        // rows' timestamps, so pruneToMostRecentGroups(RETAINED_GROUPS) keeps
        // the stale groups and deletes each freshly cached group as it is
        // written; get(id) then never finds the new node.
        //
        // groupSequence cannot literally be re-seeded lower here -- it is a
        // process-static counter, not re-created per repository instance --
        // so this reproduces the same fault a different way: five rows
        // timestamped near Long.MAX_VALUE, far above anything
        // System.currentTimeMillis() will ever produce (~1.8e12 in 2026),
        // stand in for "leftover rows from a process whose clock was ahead of
        // this one's". They outrank RETAINED_GROUPS (5) worth of real groups
        // regardless of groupSequence's actual live value -- exactly what a
        // backwards clock step does.
        //
        // The flush round trip is not incidental: it proves clearTable()'s
        // deleteAll (queued on the same single-threaded executor cache() and
        // the constructor fix both use) has actually run before the raw
        // insertAll that follows, which bypasses that executor and would
        // otherwise race it.
        repository.cache(listOf(track("flush")))
        pollUntilFound("flush")

        // Off the test thread: Robolectric runs the test method on the "main"
        // thread, and AppDatabase (unlike the isolated one in
        // SessionMediaItemDaoTest) is not built with allowMainThreadQueries().
        val dao = AppDatabase.getInstance().sessionMediaItemDao()
        Thread {
            dao.insertAll(
                (0 until 5).map { i ->
                    SessionMediaItem().apply {
                        id = "stale-$i"
                        timestamp = Long.MAX_VALUE - i
                    }
                },
            )
        }.apply {
            start()
            join()
        }

        // Simulates the next process: a brand new repository over the same
        // on-disk table, exactly as MediaService constructs one at process start.
        val freshRepository = SessionMediaItemRepository()
        freshRepository.cache(listOf(track("fresh")))

        val fresh = pollUntilFound("fresh", repository = freshRepository)
        assertEquals("fresh", fresh.id)
    }
}
