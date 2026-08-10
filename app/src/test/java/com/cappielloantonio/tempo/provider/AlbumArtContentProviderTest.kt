package com.cappielloantonio.tempo.provider

import android.content.Context
import android.net.Uri
import com.cappielloantonio.tempo.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // plex_machine_identifier is written here too, not assumed absent --
        // Robolectric caches SharedPreferences statically across test methods,
        // so writeCachedComposite's cache files have to agree with whatever
        // this method last wrote.
        val context = App.getContext()
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("plex_server_uri", "https://plex.example")
            .putString("plex_token", "tok123")
            .putString("plex_music_section_key", "4")
            .putString("plex_machine_identifier", MACHINE_IDENTIFIER)
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
    fun refusesADecadeSegmentThatIsNotFourDigits() {
        // This segment becomes part of a cache filename, so what the guard has
        // to establish is that it is digits only -- no decoded `/`, no `..`, no
        // separator of any kind -- and of fixed width. Everything below fails
        // one of those two.
        val live = CompositeArtBucket.current(System.currentTimeMillis())

        val hostile = listOf(
            "..", "../..", "198", "19800", "abcd", "19 0", "", "%2e%2e",
            // The one that actually escapes: Uri.getPathSegments() decodes
            // percent-escapes, so this arrives as the single segment
            // "/../../evil" -- the UriMatcher's `*` accepts a segment
            // containing a decoded separator, and unguarded that would put
            // "/../../evil" straight into the cache filename, resolving
            // above cacheDir.
            "%2f..%2f..%2fevil",
        )

        // Collected rather than asserted one at a time, so removing the guard
        // reports every segment that got through. Only the last of these is
        // dangerous unguarded -- ".." is a harmless literal filename and
        // "../.." never reaches the regex, the UriMatcher having already
        // refused a four-segment decade -- and a fail-fast loop would stop
        // at ".." and never say whether the decoded slash still bounced.
        //
        // Built by hand rather than through decadeContentUri, because
        // appendPath would escape the `%` of "%2f..%2f..%2fevil" into "%25"
        // and disarm the one case that carries this test's weight. The scope
        // is the live one, so it is the decade guard that has to do the
        // refusing here and not the scope check.
        val accepted = hostile.filter { segment ->
            try {
                provider.openFile(
                    Uri.parse(
                        "content://${AlbumArtContentProvider.AUTHORITY}/" +
                            "${AlbumArtContentProvider.DECADE_ART}/${scope()}/$segment/$live"
                    ),
                    "r"
                )?.close()
                true
            } catch (_: FileNotFoundException) {
                false
            }
        }

        assertEquals(emptyList<String>(), accepted)
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
                    AlbumArtContentProvider.decadeContentUri(scope(), "1980", bucket), "r"
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
        val bucket = CompositeArtBucket.current(System.currentTimeMillis())
        writeCachedComposite("1980", bucket, bytes = 3)

        val descriptor = provider.openFile(
            AlbumArtContentProvider.decadeContentUri(scope(), "1980", bucket), "r"
        )

        assertNotNull(descriptor)
        assertEquals(3L, descriptor!!.statSize)
        descriptor.close()
    }

    /**
     * The guard is four digits, not the twentieth century.
     *
     * Refusals alone cannot pin that: a pattern narrowed to `(19|20)\d{2}`, or
     * mistyped as `(19)\d{2}`, still refuses every hostile segment above and
     * still serves 1980, which is the only decade any other test here opens
     * successfully. So the whole 2000s onward could break with a green suite.
     *
     * 1890 is not a hypothetical -- a classical or historical album can carry
     * it, and a library that has one would silently lose that tile.
     *
     * Each decade's cache file is written to a different length, so statSize
     * pins *which* file was opened as well as that a real file was rather than
     * the read end of a pipe.
     */
    @Test
    fun servesEveryFourDigitDecade() {
        val bucket = CompositeArtBucket.current(System.currentTimeMillis())
        val sizes = mapOf("2020" to 5, "1890" to 7, "2090" to 11)
        sizes.forEach { (decade, bytes) -> writeCachedComposite(decade, bucket, bytes) }

        // Every decade is opened before anything is asserted, so a narrowed
        // pattern names all of the decades it broke rather than only the first
        // one the loop reached.
        val served: Map<String, Any?> = sizes.keys.associateWith { decade ->
            try {
                provider.openFile(
                    AlbumArtContentProvider.decadeContentUri(scope(), decade, bucket), "r"
                )!!.use { it.statSize }
            } catch (e: FileNotFoundException) {
                "refused: ${e.message}"
            }
        }

        assertEquals(sizes.mapValues { (_, bytes) -> bytes.toLong() }, served)
    }

    @Test
    fun decadeContentUriRoundTripsTheScopeDecadeAndBucket() {
        val uri = AlbumArtContentProvider.decadeContentUri("abc123def456-4", "1980", 487234L)

        assertEquals(
            listOf(AlbumArtContentProvider.DECADE_ART, "abc123def456-4", "1980", "487234"),
            uri.pathSegments
        )
    }

    /**
     * The regression this segment exists for.
     *
     * Keying the *cache file* by machine identifier was correct and did not fix
     * the reported bug: after switching servers the car went on drawing the old
     * mosaic, and the cache directory showed composites under exactly one
     * machine identifier -- no second prefix, no failed-build placeholder. The
     * provider was never asked. A URI naming only the decade and the hour is
     * byte-identical across servers, so the car's own image cache answered from
     * what it already held. Different libraries have to mint different URIs or
     * nothing downstream of the URI can matter.
     */
    @Test
    fun twoLibrariesMintDifferentUrisForTheSameDecadeAndBucket() {
        assertNotEquals(
            AlbumArtContentProvider.decadeContentUri("serverA-4", "1980", 487234L),
            AlbumArtContentProvider.decadeContentUri("serverB-4", "1980", 487234L)
        )
    }

    /**
     * A URI minted for a library the user has since left is refused, not served
     * from whatever the current one happens to have cached.
     *
     * Both directions in one test on purpose. A guard that refused everything
     * would pass the refusal half while blanking every decade tile in the car,
     * so the accept half runs first and asserts `statSize` -- 3, the length
     * written below -- because a pipe reports 0 and a descriptor alone would
     * prove nothing. The refusal half then asks for the same decade, the same
     * bucket and the same on-disk file under a foreign scope: without the
     * guard the provider names its cache file from the current session, finds
     * that file and serves it, which is precisely the stale art being fixed.
     */
    @Test
    fun refusesACompositeMintedForAnotherLibrary() {
        val bucket = CompositeArtBucket.current(System.currentTimeMillis())
        writeCachedComposite("1980", bucket, bytes = 3)

        provider.openFile(
            AlbumArtContentProvider.decadeContentUri(scope(), "1980", bucket), "r"
        )!!.use { assertEquals(3L, it.statSize) }

        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(
                AlbumArtContentProvider.decadeContentUri("f00dcafe-9", "1980", bucket), "r"
            )
        }
    }

    /** The scope of the session setUp() wrote, computed the way the rows the
     * car receives compute it -- one definition, so a test cannot pin a format
     * the minting side has since moved off. */
    private fun scope(): String = DecadeCompositeArt.currentScope()!!

    /** A composite already on disk for [decade], of exactly [bytes] length --
     * the length being what the assertions read back off the descriptor. Keyed
     * with [MACHINE_IDENTIFIER], matching what setUp() wrote as the signed-in
     * session's, so the provider's read of the session resolves to the same
     * cache file this writes. */
    private fun writeCachedComposite(decade: String, bucket: Long, bytes: Int) {
        val file = DecadeCompositeArt.cacheFile(App.getContext(), MACHINE_IDENTIFIER, "4", decade, bucket)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    private companion object {
        /** The signed-in session's machine identifier, per setUp(). */
        const val MACHINE_IDENTIFIER = "abc123def456"
    }
}
