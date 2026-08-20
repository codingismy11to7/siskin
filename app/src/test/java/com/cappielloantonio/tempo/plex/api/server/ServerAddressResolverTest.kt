package com.cappielloantonio.tempo.plex.api.server

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric: android.net.Uri returns null under returnDefaultValues. */
@RunWith(RobolectricTestRunner::class)
class ServerAddressResolverTest {
    private lateinit var api: PlexApi

    @Before
    fun reset() {
        api = PlexApi()
        api.accountToken = "account-token"
        api.serverCandidates = null
        api.session =
            PlexSession(
                accountToken = "account-token",
                serverUri = "https://live.example:32400",
                musicSectionKey = SectionKey("5"),
                serverToken = "server-token",
                machineIdentifier = "machine-a",
            )
    }

    private fun resolver() = ServerAddressResolver(ServerAddressBook.newForTest(api))

    private fun resolve(uri: String): String = resolver().resolveDataSpec(DataSpec(Uri.parse(uri))).uri.toString()

    @Test
    fun aPartUrlGetsTheLiveOrigin() {
        assertEquals(
            "https://live.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t",
            resolve("https://dead.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t"),
        )
    }

    @Test
    fun aPartUrlAlreadyOnTheLiveOriginIsUnchanged() {
        val uri = "https://live.example:32400/library/parts/1/2/file.mp3"
        assertEquals(uri, resolve(uri))
    }

    @Test
    fun artworkIsUntouched() {
        // Artwork goes through AlbumArtContentProvider, which composes its own
        // URL at fetch time and is already address-current. Built with the
        // provider's own contentUri() rather than a hand-typed string, so this
        // pins the real shape: authority "<appId>.albumart.provider", and the
        // thumb path as a single percent-encoded segment under /albumArt/ --
        // decoded, uri.path is "/albumArt//library/metadata/1/thumb/2", not
        // "/library/metadata/1/thumb/2".
        val uri = AlbumArtContentProvider.contentUri("/library/metadata/1/thumb/2").toString()
        assertEquals(uri, resolve(uri))
    }

    @Test
    fun aNonPartPathIsUntouched() {
        val uri = "https://elsewhere.example/some/other/thing.mp3"
        assertEquals(uri, resolve(uri))
    }

    @Test
    fun withNoSessionTheUriIsLeftAlone() {
        api.session = null
        val uri = "https://dead.example:32400/library/parts/1/2/file.mp3"
        assertEquals(uri, resolve(uri))
    }

    @Test
    fun aFileUriUnderALibraryPartsPathIsLeftAlone() {
        // path.startsWith("/library/parts/") is the only discriminator besides
        // scheme, so without the scheme guard this would have been rewritten
        // into an https:// URL on the live server -- a local file is never a
        // Plex part, whatever its path looks like.
        val uri = "file:///library/parts/1/2/file.mp3"
        assertEquals(uri, resolve(uri))
    }

    @Test
    fun aFullyPopulatedDataSpecKeepsEveryFieldButTheUri() {
        val original =
            DataSpec
                .Builder()
                .setUri(Uri.parse("https://dead.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t"))
                .setUriPositionOffset(7L)
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(byteArrayOf(1, 2, 3))
                .setHttpRequestHeaders(mapOf("Range" to "bytes=100-"))
                .setPosition(100L)
                .setLength(500L)
                .setKey("cache-key")
                .setFlags(DataSpec.FLAG_ALLOW_GZIP)
                .setCustomData("custom")
                .build()

        val resolved = resolver().resolveDataSpec(original)

        assertEquals(
            "https://live.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t",
            resolved.uri.toString(),
        )
        assertEquals(original.uriPositionOffset, resolved.uriPositionOffset)
        assertEquals(original.httpMethod, resolved.httpMethod)
        assertArrayEquals(original.httpBody, resolved.httpBody)
        assertEquals(original.httpRequestHeaders, resolved.httpRequestHeaders)
        assertEquals(original.position, resolved.position)
        assertEquals(original.length, resolved.length)
        assertEquals(original.key, resolved.key)
        assertEquals(original.flags, resolved.flags)
        assertEquals(original.customData, resolved.customData)
    }
}
