package com.cappielloantonio.tempo.provider

import android.content.UriMatcher
import android.net.Uri
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider.Companion.ALBUM_ART
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider.Companion.AUTHORITY
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider.Companion.DECADE_ART
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider.Companion.HUB_ART
import com.cappielloantonio.tempo.util.HubCoverPool

/**
 * One artwork URI that [AlbumArtContentProvider] has agreed to serve, and the
 * session it was checked against.
 *
 * The provider is exported -- the car reads artwork through it -- so every URI
 * is attacker-controlled: any app on the head unit can ask for any URI under
 * this authority. [parse] is where all of that input is refused, and a refusal
 * is a reason, which the provider turns into FileNotFoundException and the car
 * into its placeholder icon.
 *
 * **The session travels with the request.** Everything downstream -- the album
 * URL, the cache lookup, the build -- answers from the snapshot the scope was
 * compared against. A fresh read anywhere after [parse] would let a library
 * switch landing in between answer one library's URI out of another's cache.
 */
sealed interface ArtRequest {
    val session: PlexSession

    data class Album(
        val thumbPath: String,
        override val session: PlexSession,
    ) : ArtRequest

    data class Decade(
        val decade: String,
        val bucket: Long,
        override val session: PlexSession,
    ) : ArtRequest

    data class Hub(
        val pool: List<String>,
        val bucket: Long,
        override val session: PlexSession,
    ) : ArtRequest

    companion object {
        private const val MATCH_ALBUM_ART = 1
        private const val MATCH_DECADE_ART = 2
        private const val MATCH_HUB_ART = 3

        private val uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply {
                addURI(AUTHORITY, "$ALBUM_ART/*", MATCH_ALBUM_ART)
                // scope / decade / bucket. The arity is the guard: decade() reads
                // each of the three by index, so a URI of any other shape must not
                // reach it at all.
                addURI(AUTHORITY, "$DECADE_ART/*/*/#", MATCH_DECADE_ART)
                // scope / bucket / pool. Fixed arity again, and the pool is one segment
                // rather than one per thumb: UriMatcher has no repeating wildcard, so
                // the alternative was six near-identical rules with the cap on how many
                // covers a caller may demand emerging from the rule set instead of
                // being stated in hub(). See HubCoverPool.
                addURI(AUTHORITY, "$HUB_ART/*/#/*", MATCH_HUB_ART)
            }

        /**
         * A decade key must be four digits. It becomes part of a cache filename, and what the guard
         * buys is exactly two properties: the segment is digits only, so no decoded `/`, no `..` and no
         * separator of any kind reaches the filename CompositeArt.cacheFile interpolates; and it is a
         * fixed-width run, so there is no length to play with either. matches() anchors the whole
         * segment rather than a prefix, which is what makes both properties hold of the segment and not
         * merely of a prefix of it.
         *
         * Deliberately not narrowed to `(19|20)\d{2}`. The smaller filename space that buys does no
         * work: nothing is ever cached for a decade that yields no albums, so the bogus names are never
         * written, and a caller wanting to burn Plex queries can loop the 200 allowed-but-absent values
         * as happily as 10,000. Meanwhile it refuses a genuine pre-1900 key -- an 1890s classical or
         * historical album -- which a real library can produce.
         *
         * The residual is the same either way, and is named here rather than oversold: a well-formed
         * but absent decade still costs one Plex query per open, because DecadeCompositeArt.build()
         * returns null before writing anything and so leaves nothing cached to answer the next request.
         */
        private val DECADE = Regex("\\d{4}")

        /** [uri] as a request, or why it is refused.
         *
         * [session] is called at most once, and only after everything the URI
         * alone can refuse has been checked: a read is several binder calls into
         * AccountManager, and an exported provider would otherwise let any app
         * on the head unit buy them with junk URIs. The result is the one the request carries;
         * see [ArtRequest]. */
        fun parse(
            uri: Uri,
            session: () -> PlexSession?,
            nowMs: Long,
        ): Either<String, ArtRequest> =
            either {
                when (uriMatcher.match(uri)) {
                    MATCH_ALBUM_ART -> album(uri, session)
                    MATCH_DECADE_ART -> decade(uri, session, nowMs)
                    MATCH_HUB_ART -> hub(uri, session, nowMs)
                    else -> raise("Unrecognised artwork URI")
                }
            }

        private fun Raise<String>.album(
            uri: Uri,
            session: () -> PlexSession?,
        ): Album {
            // The last path segment is a Plex thumb path such as
            // /library/metadata/12345/thumb/1699999999. contentUri() percent-encodes its
            // separators and getLastPathSegment() decodes them, so the multi-segment path
            // arrives whole rather than truncated to its final component.
            //
            // MediaUrlBuilder.artworkUrl embeds the segment as `url=` on Plex's photo
            // transcoder, which will fetch whatever that names, absolute URLs on other
            // hosts included. Unvalidated, that makes the user's Plex server a proxy the
            // caller can point at anything reachable from it, authenticated with the
            // user's own token. A genuine Plex thumb is always a server-relative path, so
            // anything else is refused the same way an absent one is.
            val thumbPath =
                ensureNotNull(uri.lastPathSegment?.takeIf(MediaUrlBuilder::isServerRelativePath)) {
                    "Not a Plex artwork path"
                }
            return Album(thumbPath, ensureNotNull(session()) { "Signed out" })
        }

        /**
         * The decade composite. Its validation differs from [album]'s, and deliberately: no
         * caller-supplied path reaches MediaUrlBuilder on *this* route -- the covers come from our own
         * Plex response -- so the open-proxy hazard that path guards against cannot arise here. That is
         * a property of this route and not of this provider: [hub] takes its covers from the URI, and
         * guards them the way [album] guards its thumb. What this path needs instead is a bound on how
         * much work a caller can ask for, because every cache miss is a Plex request made with the
         * user's token, and an answer to "is this URI even about the library we are pointed at now".
         */
        private fun Raise<String>.decade(
            uri: Uri,
            session: () -> PlexSession?,
            nowMs: Long,
        ): Decade {
            val segments = uri.pathSegments
            val decade = segments[2]
            ensure(DECADE.matches(decade)) { "Not a decade" }
            val bucket = liveBucket(segments[3], nowMs)
            return Decade(decade, bucket, inScope(session, segments[1]))
        }

        /**
         * The hub composite. Its validation differs from both routes above.
         *
         * Cheaper to serve than a decade tile and cheaper to abuse: there is no Plex request on this
         * route at all, so a hostile open costs cover fetches and no metadata query -- the opposite of
         * the residual [decade] concedes, where a well-formed but absent decade costs one query per
         * open forever.
         *
         * What it does need is a bound on amplification. One open here can trigger seven
         * authenticated fetches where an album open triggers one -- up to HubCoverPool.MAX candidate
         * loads, plus the full-edge re-request CompositeArt's degraded branch makes when a pool of four
         * or more lands fewer than four covers and the layout falls back to one cell -- which is what
         * the pool cap is for. The open-proxy hazard itself is [album]'s, not a new one: any app on the
         * head unit has always been able to ask this authority for any server-relative Plex path,
         * behind this same guard.
         *
         * One residual is worth naming rather than leaving for someone to rediscover: a hostile
         * caller can mint many distinct valid pools out of real thumb paths -- reordering six real
         * thumbs alone is hundreds of pools -- and cause a small JPEG per pool to be written under this
         * bucket, since evictStale reaps only stale buckets, not a live bucket's volume. That is
         * app-private storage in a system-evictable cacheDir, no worse than the same app filling its
         * own cache directly, so it is not treated as a bug here -- just recorded so it reads as
         * considered rather than missed.
         */
        private fun Raise<String>.hub(
            uri: Uri,
            session: () -> PlexSession?,
            nowMs: Long,
        ): Hub {
            val segments = uri.pathSegments
            val bucket = liveBucket(segments[2], nowMs)

            val pool = HubCoverPool.decode(segments[3])
            // pool.isEmpty() is not reachable today -- HubCoverPool.decode is
            // split(",") and never returns an empty list, not even for "", which
            // HubCoverPoolTest pins as decode("") == listOf(""). It is kept as
            // defence against a future decode() that could return one, not as a
            // condition this guard is currently relied on to catch.
            ensure(pool.isNotEmpty() && pool.size <= HubCoverPool.MAX) { "Not a cover pool" }
            // One bad entry refuses the whole URI rather than being filtered
            // out: filtering would let a caller pair one real thumb with five
            // probes and still be handed a tile, and refusing whole costs real
            // traffic nothing, because a pool this app minted is valid in every
            // position by construction.
            ensure(pool.all(MediaUrlBuilder::isServerRelativePath)) { "Not a Plex artwork path" }

            return Hub(pool, bucket, inScope(session, segments[1]))
        }

        private fun Raise<String>.liveBucket(
            segment: String,
            nowMs: Long,
        ): Long {
            // `#` restricts the segment to digits but not to a digit run that
            // fits in a long, which is the case the null is for.
            val bucket = ensureNotNull(segment.toLongOrNull()) { "Not a bucket" }
            ensure(CompositeArtBucket.isLive(bucket, nowMs)) { "Stale composite bucket" }
            return bucket
        }

        /**
         * [session], if [scope] names its library.
         *
         * A URI minted for a server the user has since left. Serving it would hand back the previous
         * library's mosaic -- the bug this segment exists to fix, arriving from the other direction --
         * and the tile it actually asks for is one we can no longer build. Signed out is the same
         * answer for the same reason: no session, nothing to honour a scope against and nothing to
         * draw.
         *
         * Unlike the decade, this segment never reaches a filename: it is compared for equality
         * against a locally computed string and used for nothing else, and the composite is still
         * named from the session rather than from anything the caller sent. That is what makes a
         * charset guard unnecessary here rather than merely absent.
         */
        private fun Raise<String>.inScope(
            session: () -> PlexSession?,
            scope: String,
        ): PlexSession =
            ensureNotNull(session()?.takeIf { CompositeArt.scopeOf(it) == scope }) {
                "Not this library's composite"
            }
    }
}
