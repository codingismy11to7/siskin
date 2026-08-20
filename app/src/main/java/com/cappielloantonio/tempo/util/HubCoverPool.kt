package com.cappielloantonio.tempo.util

/**
 * The candidate covers behind a Discover row, as the one path segment both
 * ends of a `hubArt` URI agree about.
 *
 * One segment rather than one per thumb because the pool is variable length
 * and `UriMatcher` has no repeating wildcard: six near-identical `addURI`
 * rules would work, but the cap on how many covers a caller may demand would
 * then be emergent from the rule set rather than stated in code. Query
 * parameters read better still and were rejected for being unproven -- nothing
 * shows they survive the media3 -> car image-loader round trip, and being
 * wrong about that is artwork that silently never appears. A percent-encoded
 * multi-segment path is proven, because `AlbumArtContentProvider.openAlbumArt`
 * has always shipped on it.
 *
 * **The delimiter is deliberately not load-bearing for safety**, in either
 * direction. A real Plex thumb is `/library/metadata/{ratingKey}/thumb/{ts}` --
 * digits and slashes, never a comma. A caller who sends commas gets a split
 * into components that fail `MediaUrlBuilder.isServerRelativePath` and a
 * refused URI; a genuine thumb that somehow carried one would corrupt its own
 * pool, be refused the same way, and cost that row its tile and nothing else.
 * [decode] therefore repairs nothing and drops nothing.
 */
object HubCoverPool {
    /**
     * The most covers a pool may hold, which is the hub listing's own item
     * count.
     *
     * Six is a ceiling rather than a preference: `?count=` is not a page size
     * on `/hubs/sections/{key}` but a different lottery ticket -- each distinct
     * value re-rolls the whole hub set, and the hubs design measured a vault
     * hub returning six items at the default and zero at `count=30`. So there
     * is no way to ask for a bigger pool, which is one of the reasons the grid
     * is 2x2 and not 3x3.
     */
    const val MAX = 6

    private const val SEPARATOR = ","

    /**
     * [thumbs] as one URI path segment.
     *
     * Never called with an empty list: a hub with no thumb-bearing item mints
     * no artwork URI at all, which is what its rows do today.
     */
    @JvmStatic
    fun encode(thumbs: List<String>): String = thumbs.joinToString(SEPARATOR)

    /** The components of [pool], exactly as they arrived. */
    @JvmStatic
    fun decode(pool: String): List<String> = pool.split(SEPARATOR)
}
