package com.cappielloantonio.tempo.plex.api.server

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
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
        api.session = PlexSession(
            accountToken = "account-token",
            serverUri = "https://live.example:32400",
            musicSectionKey = SectionKey("5"),
            serverToken = "server-token",
            machineIdentifier = "machine-a"
        )
    }

    private fun resolve(uri: String): String {
        val resolver = ServerAddressResolver(ServerAddressBook.newForTest(api))
        return resolver.resolveDataSpec(DataSpec(Uri.parse(uri))).uri.toString()
    }

    @Test
    fun aPartUrlGetsTheLiveOrigin() {
        assertEquals(
            "https://live.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t",
            resolve("https://dead.example:32400/library/parts/1/2/file.mp3?X-Plex-Token=t")
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
        // URL at fetch time and is already address-current.
        val uri = "content://us.codingismy11to7.siskin.debug.artwork/library/metadata/1/thumb/2"
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
}
