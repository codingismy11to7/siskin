package com.cappielloantonio.tempo.repository

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaBrowserTree
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class LibraryPickerCommitTest {

    private val api = PlexApi()
    private lateinit var player: Player

    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across methods, so every
        // field this test depends on is reset rather than assumed absent.
        api.accountToken = "acct"
        api.session = PlexSession("acct", "http://pms:32400", SectionKey("3"), null, "abc123")
        // Every successful selectLibrary in this file now writes this key via
        // addressBook.adopt(), so it needs the same reset as accountToken/session
        // above -- otherwise a list stored by one test method would still be
        // readable by the next.
        api.serverCandidates = null
        // QueueRepository's dbExecutor is a static single-threaded executor shared
        // across every test method in this run, so this queues ahead of whatever
        // any individual test submits next; FIFO ordering (see the barrier helpers
        // below) is what makes that safe rather than a race.
        QueueRepository().deleteAll()

        // A live session is what makes selectLibrary's invalidateRoot() and
        // stopPlayback() do anything at all -- both return early without one, so
        // every assertion about them would pass vacuously.
        player = mock()
        val session = mock<MediaLibrarySession>()
        whenever(session.player).thenReturn(player)
        MediaBrowserTree.initialize(App.getContext(), mock())
        BrowseTreeInvalidator.attach(session)
    }

    @After
    fun tearDown() {
        // BrowseTreeInvalidator is a process-wide singleton; leaving a mock
        // attached would leak into whatever test class runs next.
        BrowseTreeInvalidator.detach()
    }

    private fun resource(id: String, accessToken: String? = null) = Resource().apply {
        name = "Basement"
        clientIdentifier = id
        this.accessToken = accessToken
        // adopt() derives the address list from this, not from the uri passed to
        // primeCandidateForTest/selectLibrary -- so a resource with no
        // connections would make the address-list test pass vacuously.
        connections = listOf(Connection().apply { uri = "https://$id.example:32400" })
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
    fun `a successful commit records the chosen server's address list`() {
        // The More tab's half of the address-book write: chooseLibrary's sibling
        // in PlexSignInViewModelTest (chooseLibraryRecordsTheServersOtherAddresses)
        // covers sign-in; this is the path a driver reaches mid-drive, and the one
        // the address book exists to spare from a plex.tv round trip.
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://newserver:32400",
            resource = resource(id = "xyz999", accessToken = "srv-tok"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )

        repo.selectLibrary("xyz999|7").get()

        val stored = ServerAddressBook.newForTest(api).storedCandidates("xyz999")
        assertNotNull("selectLibrary must record the address list", stored)
        assertTrue(
            "the addresses the resource advertises must be stored",
            stored!!.direct.isNotEmpty()
        )
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
    fun `switching to a different server stops playback and empties the timeline`() {
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://otherserver:32400",
            resource = resource(id = "xyz999"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )
        repo.selectLibrary("xyz999|7").get()

        // stopPlayback posts to the main thread, and Robolectric's looper is
        // paused, so without this the queued Player calls never run and the
        // verifications below would fail whether or not the fix is present.
        shadowOf(Looper.getMainLooper()).idle()

        // Deleting the Room queue is not enough on its own: ExoPlayer's timeline
        // still holds the old server's stream URLs.
        verify(player).stop()
        verify(player).clearMediaItems()
    }

    @Test
    fun `switching library on the same server leaves playback alone`() {
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://pms:32400",
            resource = resource(id = "abc123"),
            sectionKey = "7",
            libraryName = "Soundtrack"
        )
        repo.selectLibrary("abc123|7").get()
        shadowOf(Looper.getMainLooper()).idle()

        // Rating keys are server-wide, so the queue -- and what is playing from
        // it -- is still entirely valid.
        verify(player, never()).stop()
        verify(player, never()).clearMediaItems()
    }

    @Test
    fun `a candidate survives entering a second server and backing out to the first`() {
        val repo = LibraryPickerRepository()
        repo.primeCandidateForTest(
            uri = "http://first:32400",
            resource = resource(id = "aaa111", accessToken = "a-tok"),
            sectionKey = "1",
            libraryName = "A Music"
        )
        repo.primeCandidateForTest(
            uri = "http://second:32400",
            resource = resource(id = "bbb222", accessToken = "b-tok"),
            sectionKey = "2",
            libraryName = "B Music"
        )

        // The car caches a browse list and does not re-fetch it on the way back,
        // so the tap on the first server's list arrives after the second server
        // has already been entered.
        val result = repo.selectLibrary("aaa111|1").get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val session = api.session
        assertEquals("http://first:32400", session?.serverUri)
        assertEquals("aaa111", session?.machineIdentifier)
        assertEquals(SectionKey("1"), session?.musicSectionKey)
        assertEquals("a-tok", session?.serverToken)
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
    fun `a plex tv failure narrows to this node and does not clear the session`() {
        val before = api.session

        // Robolectric has no route to plex.tv, and if a build machine does, an
        // unauthenticated /resources answers 401 -- plexCall turns either into
        // the same PlexTransportFailure, so this is deterministic.
        val result = LibraryPickerRepository().getServers().get()

        // Deliberately not a LibraryResult error. media3 replicates only -102 and
        // -105 to a legacy MediaBrowserCompat client, which com.android.car.media
        // is, so a SessionError(ERROR_IO, msg) reaches the car as a bare null and
        // the sentence is lost. A row is the only thing the user can read.
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val row = requireNotNull(result.value).single()
        assertEquals(
            App.getContext().getString(R.string.aa_library_picker_offline),
            row.mediaMetadata.title?.toString()
        )
        assertEquals(true, row.mediaMetadata.isBrowsable)
        assertEquals(false, row.mediaMetadata.isPlayable)

        // The car can be on a LAN with a working Plex server and no internet;
        // failing to list servers must not sign it out.
        assertEquals(before, api.session)
        assertEquals("acct", api.accountToken)
    }

    @Test
    fun `a library list that cannot be built says why instead of erroring`() {
        val result = LibraryPickerRepository().getLibraries("some-other-server").get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(
            App.getContext().getString(R.string.aa_library_picker_offline),
            requireNotNull(result.value).single().mediaMetadata.title?.toString()
        )
    }

    @Test
    fun `plex tv and the media server fail in different words`() {
        val context = App.getContext()
        // Three causes the car would otherwise render identically. If two of
        // these ever collapse into one string, the picker is back to a generic
        // failure screen.
        val offline = context.getString(R.string.aa_library_picker_offline)
        val gone = context.getString(R.string.aa_library_picker_server_gone)
        val unreachable = context.getString(R.string.aa_library_picker_server_unreachable)
        assertNotEquals(offline, gone)
        assertNotEquals(offline, unreachable)
        assertNotEquals(gone, unreachable)
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
        // Names the server too: a library called "Music" is ambiguous across an
        // account, and "Basement" is the candidate's server name.
        val expectedTitle = App.getContext()
            .getString(R.string.aa_now_browsing, "Big Music Library", "Basement")
        assertEquals(expectedTitle, item.mediaMetadata.title?.toString())
        assertEquals("Now browsing: Big Music Library (Basement)", expectedTitle)
    }
}
