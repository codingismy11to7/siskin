package com.cappielloantonio.tempo.repository

import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.plex.FolderToken
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.provider.CompositeArt
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.FolderKey
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlexBrowseFolderTest {
    private val fixture = PlexBrowseTestServer()

    @Before fun startServer() {
        fixture.start()
    }

    @After fun stopServer() {
        fixture.stop()
    }

    private val scope get() = CompositeArt.currentScope()!!

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun folder(token: Int) = """{"key":"/library/sections/1/folder?parent=$token","title":"Folder $token"}"""

    private fun track(key: Int) =
        """{"ratingKey":"$key","key":"/library/metadata/$key","type":"track","title":"Track $key",""" +
            """"Media":[{"Part":[{"key":"/library/parts/$key/file.flac"}]}]}"""

    private fun level(
        totalSize: Int,
        vararg rows: String,
    ) = """{"MediaContainer":{"size":${rows.size},"totalSize":$totalSize,"Metadata":[${rows.joinToString(",")}]}}"""

    @Test
    fun `the root lists folders and tracks with scoped ids`() {
        fixture.server.enqueue(ok(level(2, folder(17890), track(9))))

        val result = PlexBrowseRepository().getFolderRoot().get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val items = result.value!!
        assertEquals(Constants.FOLDER_ID + FolderKey.of(scope, FolderToken("17890")), items[0].mediaId)
        assertEquals("Folder 17890", items[0].mediaMetadata.title.toString())
        assertTrue(items[0].mediaMetadata.isBrowsable!!)
        assertEquals("9", items[1].mediaId)
        assertTrue(items[1].mediaMetadata.isPlayable!!)
        assertEquals("/library/sections/1/folder", fixture.server.takeRequest().path)
    }

    private fun parentOf(item: androidx.media3.common.MediaItem) =
        (item.requestMetadata.extras ?: item.mediaMetadata.extras)?.getString(PlexMediaMapper.EXTRA_PARENT_ID)

    @Test
    fun `folder tracks carry the tag every browse track carries`() {
        fixture.server.enqueue(ok(level(2, folder(1), track(9))))
        val payload = FolderKey.of(scope, FolderToken("42"))

        val items = PlexBrowseRepository().getFolder(payload).get().value!!

        assertEquals(Constants.QUEUE_CACHED_SOURCE, parentOf(items[1]))
    }

    @Test
    fun `a folder asks for its own token`() {
        fixture.server.enqueue(ok(level(1, track(9))))

        PlexBrowseRepository().getFolder(FolderKey.of(scope, FolderToken("42"))).get()

        assertEquals("/library/sections/1/folder?parent=42", fixture.server.takeRequest().path)
    }

    @Test
    fun `a level over one window becomes window rows`() {
        fixture.server.enqueue(ok(level(120, folder(1))))
        repeat(3) { fixture.server.enqueue(ok(level(120, folder(100 + it)))) }
        val payload = FolderKey.of(scope, FolderToken("42"))

        val ids =
            PlexBrowseRepository()
                .getFolder(payload)
                .get()
                .value!!
                .map { it.mediaId }

        val prefix = Constants.FOLDER_WINDOW_ID + FolderKey.windowPrefix(payload)
        assertEquals(listOf(prefix + 0, prefix + 50, prefix + 100), ids)
    }

    @Test
    fun `folder rows and folder window rows lay their children out as a list`() {
        val list = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        fixture.server.enqueue(ok(level(1, folder(17890))))
        val folderRow = PlexBrowseRepository().getFolderRoot().get().value!![0]

        fixture.server.enqueue(ok(level(120, folder(1))))
        repeat(3) { fixture.server.enqueue(ok(level(120, folder(100 + it)))) }
        val windowRow = PlexBrowseRepository().getFolder(FolderKey.of(scope, FolderToken("42"))).get().value!![0]

        for (row in listOf(folderRow, windowRow)) {
            assertEquals(list, row.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        }
    }

    @Test
    fun `a window asks for its own slice`() {
        fixture.server.enqueue(ok(level(120, track(9))))
        val remainder = FolderKey.windowPrefix(FolderKey.of(scope, FolderToken("42"))) + 50

        PlexBrowseRepository().getFolderWindow(remainder).get()

        val request = fixture.server.takeRequest()
        assertEquals("/library/sections/1/folder?parent=42", request.path)
        assertEquals("50", request.getHeader("X-Plex-Container-Start"))
        assertEquals("${Constants.WINDOW_SIZE}", request.getHeader("X-Plex-Container-Size"))
    }

    @Test
    fun `an empty level shows the empty row`() {
        fixture.server.enqueue(ok(level(0)))

        val items = PlexBrowseRepository().getFolderRoot().get().value!!

        assertEquals(1, items.size)
        assertFalse(items[0].mediaMetadata.isPlayable!!)
    }

    @Test
    fun `a folder from another library makes no request`() {
        val stale = FolderKey.of("someone-else-9", FolderToken("42"))

        val items = PlexBrowseRepository().getFolder(stale).get().value!!

        assertEquals(1, items.size)
        assertEquals(0, fixture.server.requestCount)
    }

    @Test
    fun `a malformed payload makes no request`() {
        PlexBrowseRepository().getFolder("not a payload").get()
        PlexBrowseRepository().getFolderWindow("not|a|window").get()

        assertEquals(0, fixture.server.requestCount)
    }

    @Test
    fun `an HTTP 500 is a browse error the car can show`() {
        fixture.server.enqueue(MockResponse().setResponseCode(500))

        val result = PlexBrowseRepository().getFolderRoot().get()

        assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
    }
}
