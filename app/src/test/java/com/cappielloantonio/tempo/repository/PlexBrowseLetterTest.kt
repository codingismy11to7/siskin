package com.cappielloantonio.tempo.repository

import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.util.ConstantsAA
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Artists tab with "Show artists by initial" on.
 *
 * Robolectric because PlexApi reads App.getInstance().preferences for the
 * session, exactly as PlexBrowseWindowTest does next door.
 */
@RunWith(RobolectricTestRunner::class)
class PlexBrowseLetterTest {

    private val fixture = PlexBrowseTestServer()

    @Before fun startServer() { fixture.start() }
    @After fun stopServer() { fixture.stop() }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /** A bucket index body, shaped the way PMS shapes one. */
    private fun index(vararg buckets: Triple<String, String, Int?>): String {
        val dirs = buckets.joinToString(",") { (key, title, size) ->
            val count = if (size == null) "" else """"size":$size,"""
            """{$count"key":"$key","title":"$title"}"""
        }
        return """{"MediaContainer":{"size":${buckets.size},"Directory":[$dirs]}}"""
    }

    /** An artist listing body. */
    private fun listing(vararg titles: String): String {
        val items = titles.mapIndexed { i, t ->
            """{"ratingKey":"$i","type":"artist","title":"$t"}"""
        }.joinToString(",")
        return """{"MediaContainer":{"size":${titles.size},"totalSize":${titles.size},"offset":0,"Metadata":[$items]}}"""
    }

    @Test
    fun anIndexTooLargeToFlattenBecomesOneRowPerBucket() = runTest {
        // 12 + 79 = 91, over WINDOW_SIZE, so the tab is buckets -- and it costs
        // exactly the one index request, with no boundary-title fan-out.
        fixture.server.enqueue(ok(index(Triple("%23", "#", 12), Triple("A", "A", 79))))

        val result = PlexBrowseRepository()
            .getArtistLetters(ConstantsAA.ARTIST_LETTER_ID, ConstantsAA.ARTIST_ID).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(
            listOf("[artistLetterID]%23", "[artistLetterID]A"),
            result.value!!.map { it.mediaId }
        )
        assertEquals(listOf("#", "A"), result.value!!.map { it.mediaMetadata.title })
        assertEquals(1, fixture.server.requestCount)

        // The index asks for artists too, and this is the only test that drives
        // LibraryClient.getFirstCharacters' hardcoded PlexItemType.ARTIST.
        assertEquals("8", fixture.server.takeRequest().requestUrl!!.queryParameter("type"))
    }

    @Test
    fun eachBucketRowCarriesItsCount() = runTest {
        fixture.server.enqueue(ok(index(Triple("A", "A", 79), Triple("B", "B", 1))))

        val rows = PlexBrowseRepository()
            .getArtistLetters(ConstantsAA.ARTIST_LETTER_ID, ConstantsAA.ARTIST_ID).get().value!!

        // The singular is a real case, not a hypothetical: the reference library
        // has a "∆" bucket of one.
        assertEquals("79 artists", rows[0].mediaMetadata.artist)
        assertEquals("1 artist", rows[1].mediaMetadata.artist)
    }

    @Test
    fun aBucketWithNoCountGetsNoSecondLine() = runTest {
        fixture.server.enqueue(ok(index(Triple("A", "A", null), Triple("B", "B", 60))))

        val rows = PlexBrowseRepository()
            .getArtistLetters(ConstantsAA.ARTIST_LETTER_ID, ConstantsAA.ARTIST_ID).get().value!!

        assertNull(rows[0].mediaMetadata.artist)
    }

    @Test
    fun anIndexWhoseCountsFitReturnsTheArtistsFlat() = runTest {
        // 3 + 2 = 5 artists. Five letter rows for five artists is a worse tab
        // than a list of five, and this keeps a small library behaving the same
        // under both settings.
        fixture.server.enqueue(ok(index(Triple("A", "A", 3), Triple("B", "B", 2))))
        fixture.server.enqueue(ok(listing("Aa", "Ab", "Ac", "Ba", "Bb")))

        val result = PlexBrowseRepository()
            .getArtistLetters(ConstantsAA.ARTIST_LETTER_ID, ConstantsAA.ARTIST_ID).get()

        assertEquals(
            listOf("[artistID]0", "[artistID]1", "[artistID]2", "[artistID]3", "[artistID]4"),
            result.value!!.map { it.mediaId }
        )
        assertEquals(2, fixture.server.requestCount)

        fixture.server.takeRequest() // the index
        // sort=title on the flat list, matching what the windowed path returns
        // for a library this size, so both settings agree. Asserted through the
        // parsed parameter because "titleSort" contains "sort=title".
        assertEquals("title", fixture.server.takeRequest().requestUrl!!.queryParameter("sort"))
    }

    @Test
    fun aFailedFlatFetchFallsBackToTheBucketRows() = runTest {
        // The letter rows are already in hand and cost nothing to build, and they
        // are a working tab -- every artist is still two taps away. An empty list
        // or an error would both be worse than that.
        fixture.server.enqueue(ok(index(Triple("A", "A", 3), Triple("B", "B", 2))))
        fixture.server.enqueue(MockResponse().setResponseCode(500))

        val result = PlexBrowseRepository()
            .getArtistLetters(ConstantsAA.ARTIST_LETTER_ID, ConstantsAA.ARTIST_ID).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(
            listOf("[artistLetterID]A", "[artistLetterID]B"),
            result.value!!.map { it.mediaId }
        )
    }

    @Test
    fun a401OnTheIndexOffersTheSignInAffordance() = runTest {
        fixture.server.enqueue(MockResponse().setResponseCode(401))

        val result = PlexBrowseRepository()
            .getArtistLetters(ConstantsAA.ARTIST_LETTER_ID, ConstantsAA.ARTIST_ID).get()

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun aBucketIsFetchedByPathWithItsEncodingIntact() = runTest {
        fixture.server.enqueue(ok(listing("$" + "uicideboy$", "3epkano")))

        PlexBrowseRepository().getArtistLetter("%23", ConstantsAA.ARTIST_ID).get()

        val request = fixture.server.takeRequest()
        // pathSegments decodes, so this pins that the server sees a segment
        // meaning "#". Double-encoding would make it the literal "%23" and the
        // bucket would come back empty with no error anywhere.
        assertEquals("#", request.requestUrl!!.pathSegments.last())
        assertFalse(request.path!!.contains("%2523"))
        // Never the query form: /all?firstCharacter=D answers 200 with the whole
        // library.
        assertFalse(request.path!!.contains("firstCharacter="))
        assertNull(request.requestUrl!!.queryParameter("sort"))
        assertEquals("0", request.getHeader("X-Plex-Container-Start"))
        assertEquals(ConstantsAA.MAX_ITEMS.toString(), request.getHeader("X-Plex-Container-Size"))
        // type=8 is honoured by the server and changes the answer -- on bucket
        // "Q", type=9 returns 3 albums where no type returns 4 artists. This is
        // the first test anywhere that drives LibraryClient's hardcoded
        // PlexItemType.ARTIST, mirroring how PlexBrowseRepositoryTest is what
        // pins getDecades' hardcoded ALBUM.
        assertEquals("8", request.requestUrl!!.queryParameter("type"))
    }

    @Test
    fun aBucketReturnsItsArtistsAsItems() = runTest {
        fixture.server.enqueue(ok(listing("Daft Punk", "Bob Dylan")))

        val result = PlexBrowseRepository().getArtistLetter("D", ConstantsAA.ARTIST_ID).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("[artistID]0", "[artistID]1"), result.value!!.map { it.mediaId })
        // Bob Dylan files under D on titleSort while displaying a B. That is
        // Plex's filing and it is deliberate; 426 of the reference library's
        // 1204 artists do something like it.
        assertEquals(listOf("Daft Punk", "Bob Dylan"), result.value!!.map { it.mediaMetadata.title })
    }
}
