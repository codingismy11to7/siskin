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
            .putString("plex_music_section_key", "4")
            .commit()

        // Robolectric's cacheDir is real and persists across test methods within
        // a run; servesACachedCompositeWithoutBuildingOne writes into it
        // directly, so it is cleared here rather than assumed empty --
        // DecadeCompositeArtCacheTest resets the same way for the same reason.
        DecadeCompositeArt.cacheDir(context).deleteRecursively()

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
     * This asserts the property at the exported boundary -- that this provider
     * will not fetch a non-relative path -- rather than that any one layer
     * rejects it. Deleting *only* the guard in openFile leaves these cases
     * passing, because MediaUrlBuilder.artworkUrl then returns null and openFile
     * throws anyway; that is the defence in depth working, not a gap. The
     * builder's own guard is pinned separately by
     * MediaUrlBuilderTest.artworkUrlRefusesAThumbPathThatIsNotServerRelative.
     * Removing both is what makes these cases fail.
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

    // ── the decade composite path ─────────────────────────────

    @Test
    fun refusesADecadeSegmentThatIsNotAPlausibleDecade() {
        // This segment becomes part of a cache filename, so the check is what
        // keeps a decoded `/` out of cacheDir -- and it narrows the filename
        // space to 200 values (1900-2099) rather than the 10,000 a bare
        // \d{4} would admit.
        val live = CompositeArtBucket.current(System.currentTimeMillis())

        listOf(
            "..", "../..", "198", "19800", "abcd", "19 0", "", "%2e%2e",
            // The one that actually escapes: Uri.getPathSegments() decodes
            // percent-escapes, so this arrives as the single segment
            // "/../../evil" -- the UriMatcher's `*` accepts a segment
            // containing a decoded separator, and unguarded that would put
            // "/../../evil" straight into the cache filename, resolving
            // above cacheDir.
            "%2f..%2f..%2fevil",
        ).forEach { hostile ->
            assertThrows(hostile, FileNotFoundException::class.java) {
                provider.openFile(
                    Uri.parse(
                        "content://${AlbumArtContentProvider.AUTHORITY}/" +
                            "${AlbumArtContentProvider.DECADE_ART}/$hostile/$live"
                    ),
                    "r"
                )
            }
        }
    }

    @Test
    fun refusesABucketOutsideTheLiveWindow() {
        // Every miss is a Plex request made with the user's token. Without this,
        // any app on the head unit could walk bucket values to force unlimited
        // misses.
        val live = CompositeArtBucket.current(System.currentTimeMillis())

        listOf(live - 2, live + 1, 0L).forEach { bucket ->
            assertThrows("bucket=$bucket", FileNotFoundException::class.java) {
                provider.openFile(
                    AlbumArtContentProvider.decadeContentUri("1980", bucket), "r"
                )
            }
        }
    }

    @Test
    fun servesACachedCompositeWithoutBuildingOne() {
        // The hit path must not touch the network: eight decades scroll into
        // view at once against an executor sized max(2, cores / 2), and a build
        // holds its thread for a round trip plus four cover fetches.
        //
        // A descriptor alone does not pin that: a miss also returns one, the
        // read end of a pipe. A hit returns ParcelFileDescriptor.open on the
        // real file instead, so statSize reports the file's actual length --
        // 3, the size of what was written below -- where a pipe's is 0. That
        // is what fails if the fast path is removed and every request pipes.
        val context = App.getContext()
        val bucket = CompositeArtBucket.current(System.currentTimeMillis())
        val file = DecadeCompositeArt.cacheFile(context, "4", "1980", bucket)
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))

        val descriptor = provider.openFile(
            AlbumArtContentProvider.decadeContentUri("1980", bucket), "r"
        )

        assertNotNull(descriptor)
        assertEquals(3L, descriptor!!.statSize)
        descriptor.close()
    }

    @Test
    fun decadeContentUriRoundTripsTheDecadeAndBucket() {
        val uri = AlbumArtContentProvider.decadeContentUri("1980", 487234L)

        assertEquals(
            listOf(AlbumArtContentProvider.DECADE_ART, "1980", "487234"),
            uri.pathSegments
        )
    }
}
