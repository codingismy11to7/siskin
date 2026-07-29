package com.cappielloantonio.tempo.provider

import android.content.Context
import android.net.Uri
import com.cappielloantonio.tempo.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException

/**
 * What this exported provider will and will not ask the user's Plex server to
 * fetch.
 *
 * Robolectric rather than plain JUnit because the whole input is a Uri: under
 * this module's unitTests.returnDefaultValues, Uri.parse answers null and
 * getLastPathSegment answers null with it, so every request would take the
 * rejection path and these assertions would pass against a provider that
 * validated nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
class AlbumArtContentProviderTest {

    private lateinit var provider: AlbumArtContentProvider

    @Before
    fun setUp() {
        // Signed in, so that a missing server or token cannot be what rejects a
        // request -- the artwork URL would come back null for that reason too,
        // and then a provider with no validation would still throw and pass.
        val context = App.getContext()
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("plex_server_uri", "https://plex.example")
            .putString("plex_token", "tok123")
            .commit()

        provider = Robolectric.buildContentProvider(AlbumArtContentProvider::class.java).create().get()
    }

    /**
     * The attack this provider has to refuse.
     *
     * `android:exported="true"` is not optional here -- the car reads artwork
     * through this provider -- so any app on the head unit can open any URI
     * under this authority. The path segment is handed to
     * MediaUrlBuilder.artworkUrl as `url=` on Plex's photo transcoder, and that
     * endpoint fetches whatever the parameter names, absolute URLs on other
     * hosts included. Without the check this test pins, a caller could reach
     * anything the Plex server can reach, with the user's token, and read the
     * bytes back out of the returned file descriptor.
     *
     * Deleting the isServerRelativePath guard in openFile makes every case here
     * fail: the provider would return a pipe rather than throw.
     */
    @Test
    fun refusesToFetchAThumbPathThatIsNotServerRelative() {
        listOf(
            "http://internal-host/secret",
            "https://internal-host/secret",
            "//internal-host/secret",
            "library/metadata/42/thumb/1"
        ).forEach { hostile ->
            assertThrows(hostile, FileNotFoundException::class.java) {
                provider.openFile(AlbumArtContentProvider.contentUri(hostile), "r")
            }
        }
    }

    @Test
    fun refusesAUriWithNoPathSegmentAtAll() {
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(Uri.parse("content://${AlbumArtContentProvider.AUTHORITY}"), "r")
        }
    }

    /**
     * The guard must not be a blanket refusal: rejecting everything would pass
     * the tests above while blanking every cover in the car. A real Plex thumb
     * path -- multi-segment, percent-encoded into one segment by contentUri()
     * and decoded back by getLastPathSegment() -- has to survive it.
     */
    @Test
    fun acceptsARealPlexThumbPath() {
        val descriptor = provider.openFile(
            AlbumArtContentProvider.contentUri("/library/metadata/1234/thumb/1699999999"),
            "r"
        )

        assertNotNull(descriptor)
        descriptor!!.close()
    }

    @Test
    fun contentUriKeepsTheWholeThumbPathInOneSegment() {
        // The round trip openFile depends on: appendPath escapes the separators
        // so a multi-segment Plex path arrives whole rather than truncated to
        // its final component.
        val uri = AlbumArtContentProvider.contentUri("/library/metadata/1234/thumb/1699999999")

        assertEquals("/library/metadata/1234/thumb/1699999999", uri.lastPathSegment)
    }
}
