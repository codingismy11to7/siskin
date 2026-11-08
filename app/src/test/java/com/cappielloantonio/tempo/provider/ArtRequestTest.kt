package com.cappielloantonio.tempo.provider

import android.net.Uri
import arrow.core.Either
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.util.HubCoverPool
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which rule refuses which URI.
 *
 * AlbumArtContentProviderTest pins the exported boundary -- that a hostile URI
 * is refused at all. This pins the reason, which the boundary cannot see: every
 * refusal there is the same FileNotFoundException, so a URI refused by the
 * wrong rule would pass it. Robolectric for a real Uri, for the reason that
 * test's class doc gives.
 */
@RunWith(RobolectricTestRunner::class)
class ArtRequestTest {
    private val session =
        PlexSession(
            accountToken = "account-token",
            serverUri = "https://plex.example",
            musicSectionKey = SectionKey("4"),
            serverToken = null,
            machineIdentifier = "abc123def456",
            owned = true,
        )
    private val scope = CompositeArt.scopeOf(session)
    private val nowMs = 100 * CompositeArtBucket.BUCKET_MS
    private val live = CompositeArtBucket.current(nowMs)
    private val pool = listOf("/library/metadata/51/thumb/1", "/library/metadata/77/thumb/2")

    @Test
    fun eachRuleRefusesWithItsOwnReason() {
        val cases: Map<String, Pair<Uri, PlexSession?>> =
            mapOf(
                "unrecognised" to (raw("elsewhere/1") to session),
                "album, absolute" to (AlbumArtContentProvider.contentUri("https://evil.example/x") to session),
                "album, signed out" to (AlbumArtContentProvider.contentUri("/library/metadata/1/thumb/1") to null),
                "decade, not digits" to (raw("${AlbumArtContentProvider.DECADE_ART}/$scope/abcd/$live") to session),
                "decade, bucket overflows" to
                    (raw("${AlbumArtContentProvider.DECADE_ART}/$scope/1980/99999999999999999999") to session),
                "decade, stale" to (AlbumArtContentProvider.decadeContentUri(scope, "1980", live - 2) to session),
                "decade, other library" to (AlbumArtContentProvider.decadeContentUri("f00dcafe-9", "1980", live) to session),
                "decade, signed out" to (AlbumArtContentProvider.decadeContentUri(scope, "1980", live) to null),
                "hub, stale" to (AlbumArtContentProvider.hubContentUri(scope, live + 1, pool) to session),
                "hub, other library" to (AlbumArtContentProvider.hubContentUri("f00dcafe-9", live, pool) to session),
                "hub, pool too big" to
                    (
                        AlbumArtContentProvider.hubContentUri(
                            scope,
                            live,
                            (0..HubCoverPool.MAX).map { "/library/metadata/$it/thumb/1" },
                        ) to session
                    ),
                "hub, bad entry" to (AlbumArtContentProvider.hubContentUri(scope, live, pool + "//evil.example/x") to session),
            )

        // Collected rather than asserted one at a time, so a reordered or
        // mis-wired rule reports every case it moved.
        val refused = cases.mapValues { (_, input) -> ArtRequest.parse(input.first, { input.second }, nowMs).leftOrNull() }

        assertEquals(
            mapOf(
                "unrecognised" to "Unrecognised artwork URI",
                "album, absolute" to "Not a Plex artwork path",
                "album, signed out" to "Signed out",
                "decade, not digits" to "Not a decade",
                "decade, bucket overflows" to "Not a bucket",
                "decade, stale" to "Stale composite bucket",
                "decade, other library" to "Not this library's composite",
                "decade, signed out" to "Not this library's composite",
                "hub, stale" to "Stale composite bucket",
                "hub, other library" to "Not this library's composite",
                "hub, pool too big" to "Not a cover pool",
                "hub, bad entry" to "Not a Plex artwork path",
            ),
            refused,
        )
    }

    @Test
    fun eachRouteParsesToItsRequest() {
        assertEquals(
            Either.Right(ArtRequest.Album("/library/metadata/1/thumb/1", session)),
            ArtRequest.parse(AlbumArtContentProvider.contentUri("/library/metadata/1/thumb/1"), { session }, nowMs),
        )
        assertEquals(
            Either.Right(ArtRequest.Decade("1890", live, session)),
            ArtRequest.parse(AlbumArtContentProvider.decadeContentUri(scope, "1890", live), { session }, nowMs),
        )
        assertEquals(
            Either.Right(ArtRequest.Hub(pool, live - 1, session)),
            ArtRequest.parse(AlbumArtContentProvider.hubContentUri(scope, live - 1, pool), { session }, nowMs),
        )
    }

    /**
     * A session read is several binder calls into AccountManager, and any app
     * on the head unit can send this exported provider junk. So a URI the
     * rules can refuse on its own is refused before the session is read, and
     * one that passes reads it exactly once.
     */
    @Test
    fun readsTheSessionOnlyForAUriThatPassesEveryRuleOfItsOwn() {
        val refusedOnSight =
            listOf(
                raw("elsewhere/1"),
                AlbumArtContentProvider.contentUri("https://evil.example/x"),
                raw("${AlbumArtContentProvider.DECADE_ART}/$scope/abcd/$live"),
                raw("${AlbumArtContentProvider.DECADE_ART}/$scope/1980/99999999999999999999"),
                AlbumArtContentProvider.decadeContentUri(scope, "1980", live - 2),
                AlbumArtContentProvider.hubContentUri(scope, live + 1, pool),
                AlbumArtContentProvider.hubContentUri(scope, live, (0..HubCoverPool.MAX).map { "/library/metadata/$it/thumb/1" }),
                AlbumArtContentProvider.hubContentUri(scope, live, pool + "//evil.example/x"),
            )
        val served =
            listOf(
                AlbumArtContentProvider.contentUri("/library/metadata/1/thumb/1"),
                AlbumArtContentProvider.decadeContentUri(scope, "1980", live),
                AlbumArtContentProvider.hubContentUri(scope, live, pool),
            )

        val reads = (refusedOnSight + served).associateWith { uri -> readsWhileParsing(uri) }

        assertEquals(refusedOnSight.associateWith { 0 } + served.associateWith { 1 }, reads)
    }

    private fun readsWhileParsing(uri: Uri): Int {
        var reads = 0
        ArtRequest.parse(uri, { session.also { reads++ } }, nowMs)
        return reads
    }

    private fun raw(path: String): Uri = Uri.parse("content://${AlbumArtContentProvider.AUTHORITY}/$path")
}
