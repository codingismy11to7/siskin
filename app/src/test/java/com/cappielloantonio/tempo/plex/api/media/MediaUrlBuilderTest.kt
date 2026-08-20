package com.cappielloantonio.tempo.plex.api.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlBuilderTest {
    private val server = "https://192-168-1-5.abc123.plex.direct:32400"
    private val token = "tok123"

    @Test
    fun artworkUrlTranscodesThroughThePhotoEndpoint() {
        val url = MediaUrlBuilder.artworkUrl(server, "/library/metadata/42/thumb/1", token, 600, 600)
        assertEquals(
            "$server/photo/:/transcode" +
                "?width=600&height=600&minSize=1" +
                "&url=%2Flibrary%2Fmetadata%2F42%2Fthumb%2F1" +
                "&X-Plex-Token=tok123",
            url,
        )
    }

    @Test
    fun artworkUrlTrimsATrailingSlashOnTheServerUri() {
        // Discovered connection URIs sometimes carry a trailing slash; doubling it 404s.
        val url = MediaUrlBuilder.artworkUrl("$server/", "/thumb", token, 100, 100)
        assertEquals(
            "$server/photo/:/transcode?width=100&height=100&minSize=1&url=%2Fthumb&X-Plex-Token=tok123",
            url,
        )
    }

    @Test
    fun artworkUrlIsNullWithoutAThumb() {
        assertNull(MediaUrlBuilder.artworkUrl(server, null, token, 600, 600))
        assertNull(MediaUrlBuilder.artworkUrl(server, "   ", token, 600, 600))
    }

    @Test
    fun artworkUrlIsNullWithoutAServerOrToken() {
        assertNull(MediaUrlBuilder.artworkUrl(null, "/thumb", token, 600, 600))
        assertNull(MediaUrlBuilder.artworkUrl(server, "/thumb", null, 600, 600))
    }

    @Test
    fun artworkUrlRefusesAThumbPathThatIsNotServerRelative() {
        // Plex's photo transcoder fetches whatever `url=` names, absolute URLs
        // on other hosts included, so composing one out of a caller-supplied
        // path turns the user's own server into an authenticated proxy for it.
        // AlbumArtContentProvider is exported, which is how a caller other than
        // this app gets to supply the path in the first place.
        //
        // Each of these is a way to name somewhere other than the Plex server:
        // an absolute URL, a protocol-relative one that still starts with a
        // slash, a bare relative path, and a backslash form that several URL
        // parsers normalise into a forward slash.
        assertNull(MediaUrlBuilder.artworkUrl(server, "http://internal-host/secret", token, 600, 600))
        assertNull(MediaUrlBuilder.artworkUrl(server, "https://internal-host/secret", token, 600, 600))
        assertNull(MediaUrlBuilder.artworkUrl(server, "//internal-host/secret", token, 600, 600))
        assertNull(MediaUrlBuilder.artworkUrl(server, "library/metadata/42/thumb/1", token, 600, 600))
        assertNull(MediaUrlBuilder.artworkUrl(server, "/\\internal-host/secret", token, 600, 600))
    }

    @Test
    fun isServerRelativePathAcceptsTheThumbShapesPlexActuallyReturns() {
        // The other half of the check above: a validator that rejected
        // everything would pass those assertions while blanking every cover in
        // the car. These two are real paths measured from a live server.
        assertTrue(MediaUrlBuilder.isServerRelativePath("/library/metadata/1234/thumb/1699999999"))
        assertTrue(MediaUrlBuilder.isServerRelativePath("/playlists/169077/composite/1781213364"))
    }

    @Test
    fun streamUrlAppendsTheTokenToThePartKey() {
        val url = MediaUrlBuilder.streamUrl(server, "/library/parts/7/file.flac", token)
        assertEquals("$server/library/parts/7/file.flac?X-Plex-Token=tok123", url)
    }

    @Test
    fun streamUrlTrimsATrailingSlashOnTheServerUri() {
        val url = MediaUrlBuilder.streamUrl("$server/", "/library/parts/7", token)
        assertEquals("$server/library/parts/7?X-Plex-Token=tok123", url)
    }

    @Test
    fun streamUrlIsNullWithoutAPartKeyOrToken() {
        assertNull(MediaUrlBuilder.streamUrl(server, null, token))
        assertNull(MediaUrlBuilder.streamUrl(server, "/library/parts/7", null))
    }
}
