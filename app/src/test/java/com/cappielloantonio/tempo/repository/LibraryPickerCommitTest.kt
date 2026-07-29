package com.cappielloantonio.tempo.repository

import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.models.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryPickerCommitTest {

    private val api = PlexApi()

    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across methods, so every
        // field this test depends on is reset rather than assumed absent.
        api.accountToken = "acct"
        api.session = PlexSession("acct", "http://pms:32400", SectionKey("3"), null, "abc123")
        // QueueRepository's dbExecutor is a static single-threaded executor shared
        // across every test method in this run, so this queues ahead of whatever
        // any individual test submits next; FIFO ordering (see the barrier helpers
        // below) is what makes that safe rather than a race.
        QueueRepository().deleteAll()
    }

    private fun resource(id: String, accessToken: String? = null) = Resource().apply {
        name = "Basement"
        clientIdentifier = id
        this.accessToken = accessToken
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
        grandparentRatingKey = null,
        isHearted = false,
        parentId = null,
        serverUri = "http://pms:32400",
        token = "tok"
    )

    private fun queueIds(): List<String?> {
        var ids: List<String?> = emptyList()
        // Off the test thread: AppDatabase is not built with
        // allowMainThreadQueries() and Robolectric runs the test method on the
        // "main" thread (see SessionMediaItemRepositoryTest for the same pattern).
        Thread { ids = AppDatabase.getInstance().queueDao().getAllSimple().map { it.id } }
            .apply { start(); join() }
        return ids
    }

    private fun pollUntilQueueContains(id: String, timeoutMs: Long = 3000, intervalMs: Long = 10) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (queueIds().contains(id)) return
            Thread.sleep(intervalMs)
        }
        throw AssertionError("'$id' never appeared in the queue within ${timeoutMs}ms")
    }

    /** Seeds the queue and blocks until the seed is actually visible. */
    private fun seedQueue(id: String) {
        QueueRepository().insertAll(listOf(track(id)), true, 0)
        pollUntilQueueContains(id)
    }

    /**
     * Submits a second write through QueueRepository's own single-threaded
     * executor and waits for it, then reads the table. insertAll and deleteAll
     * share that executor, which is single-threaded, so once this barrier row is
     * visible, any deleteAll queued by an earlier selectLibrary call -- correct or
     * buggy -- has already run. Without this, a check made right after
     * selectLibrary.get() would race a same-JVM async deleteAll and could pass
     * either way regardless of what the code actually did.
     */
    private fun queueIdsAfterBarrier(): List<String?> {
        QueueRepository().insertAll(listOf(track("barrier")), false, Int.MAX_VALUE)
        pollUntilQueueContains("barrier")
        return queueIds()
    }

    @Test
    fun `abandoning the picker leaves the session untouched`() {
        val before = api.session
        // .get() rather than fire-and-forget: getLibraries launches onto an IO
        // scope, so asserting immediately would run before the coroutine did
        // anything and pass no matter what the code does. Under Robolectric
        // there is no network, so getResources fails, the server is not found,
        // and the future completes with an error -- which is exactly the
        // abandoned-navigation path this asserts about.
        LibraryPickerRepository().getLibraries("some-other-server").get()
        assertEquals(before, api.session)
    }

    @Test
    fun `a successful commit writes every session field together`() {
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://newserver:32400",
            resource = resource(id = "xyz999", accessToken = "srv-tok"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )

        val result = repo.selectLibrary("xyz999|7").get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        // Read once so a partial write -- e.g. serverUri moved but
        // musicSectionKey left stale -- shows up as one field disagreeing with
        // the rest, not as two separate assertions that could pass independently.
        val session = api.session
        assertEquals("acct", session?.accountToken)
        assertEquals("http://newserver:32400", session?.serverUri)
        assertEquals(SectionKey("7"), session?.musicSectionKey)
        assertEquals("srv-tok", session?.serverToken)
        assertEquals("xyz999", session?.machineIdentifier)
    }

    @Test
    fun `switching library within the same server leaves the saved queue intact`() {
        seedQueue("keep-me")

        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://pms:32400",
            resource = resource(id = "abc123"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )
        repo.selectLibrary("abc123|7").get()

        assertTrue(queueIdsAfterBarrier().contains("keep-me"))
    }

    @Test
    fun `switching to a different server empties the saved queue`() {
        seedQueue("keep-me")

        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://otherserver:32400",
            resource = resource(id = "xyz999"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )
        repo.selectLibrary("xyz999|7").get()

        assertFalse(queueIdsAfterBarrier().contains("keep-me"))
    }

    @Test
    fun `a candidate held for a different server is rejected without touching the session`() {
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://otherserver:32400",
            resource = resource(id = "xyz999"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )
        val before = api.session

        // Payload names abc123, but the held candidate is for xyz999.
        val result = repo.selectLibrary("abc123|7").get()

        assertEquals(SessionError.ERROR_INVALID_STATE, result.resultCode)
        assertEquals(before, api.session)
    }

    @Test
    fun `a blank section key is rejected without touching the session`() {
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://pms:32400",
            resource = resource(id = "abc123"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )
        val before = api.session

        val result = repo.selectLibrary("abc123|").get()

        assertEquals(SessionError.ERROR_INVALID_STATE, result.resultCode)
        assertEquals(before, api.session)
    }

    @Test
    fun `a missing account token produces the sign-in resolution and leaves preferences untouched`() {
        api.accountToken = null

        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://newserver:32400",
            resource = resource(id = "xyz999", accessToken = "srv-tok"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )

        val result = repo.selectLibrary("xyz999|7").get()

        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, result.resultCode)
        // api.session itself would read null either way here, since accountToken
        // alone being null already makes PlexSession.from return null -- that
        // would pass even if the other four fields had been overwritten. Reading
        // the individual fields is what actually proves nothing was written.
        assertNull(api.accountToken)
        assertEquals("http://pms:32400", api.serverUri)
        assertEquals("3", api.musicSectionKey)
        assertNull(api.serverToken)
        assertEquals("abc123", api.machineIdentifier)
    }

    @Test
    fun `the confirmation row is browsable, not playable, and names the chosen library`() {
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://pms:32400",
            resource = resource(id = "abc123"),
            sectionKey = "7",
            libraryName = "Big Music Library"
        )

        val result = repo.selectLibrary("abc123|7").get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val item = requireNotNull(result.value).single()
        assertEquals(true, item.mediaMetadata.isBrowsable)
        assertEquals(false, item.mediaMetadata.isPlayable)
        val expectedTitle = App.getInstance().getString(R.string.aa_now_browsing, "Big Music Library")
        assertEquals(expectedTitle, item.mediaMetadata.title?.toString())
    }
}
