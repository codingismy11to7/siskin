package com.cappielloantonio.tempo.provider

import android.content.Context
import com.cappielloantonio.tempo.util.HubCoverPool
import java.io.File
import java.security.MessageDigest

/**
 * The 2x2 cover mosaic behind a Discover row.
 *
 * Thinner than [DecadeCompositeArt] by exactly one thing: it fetches nothing.
 * A hub listing already returns six items with their thumbs -- that is what the
 * row's existence is decided from -- so the covers ride the artwork URI and
 * this path makes no Plex request at all.
 *
 * The row stays a list row. A hub is a proposition -- "Haven't played in 5
 * months" -- and four covers cannot tell it from "Most Played in April", so the
 * tile decorates the row rather than promoting Discover to a grid. See the
 * Discover node in `MediaBrowserTree.buildTree`.
 */
object HubCompositeArt {
    /**
     * How much of the digest becomes the filename. Sixteen hex characters is
     * 64 bits, against a cache holding tens of files: a collision would cost
     * one wrong tile for at most an hour, and there is nothing to be gained by
     * spending the other 24 characters on it.
     */
    private const val ID_LENGTH = 16

    /**
     * The cache id for [pool].
     *
     * A digest rather than an identifier because the pool is variable length
     * and cannot itself be a filename, and rather than `hashCode()` because
     * `AlbumArtContentProvider` is exported and a 32-bit collision is
     * craftable -- the impact would only be a wrong tile, but a digest costs
     * nothing to prefer.
     *
     * **This is what keeps caller-shaped strings out of the filesystem.** The
     * decade path buys that property with the provider's `\d{4}` rule; here
     * the id is derived rather than received, and hex is filename-safe by
     * construction, so there is no charset guard on this route to get wrong.
     * Order is part of the digest because cells are filled in pool order.
     */
    @JvmStatic
    fun idFor(pool: List<String>): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(HubCoverPool.encode(pool).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(ID_LENGTH)

    /** @see CompositeArt.cached */
    @JvmStatic
    fun cached(
        context: Context,
        pool: List<String>,
        bucket: Long,
    ): File? = CompositeArt.cached(context, idFor(pool), bucket)

    /**
     * @see CompositeArt.build
     *
     * The cover source ignores its session arguments because the covers are
     * already in hand. It is still a lambda, and still evaluated inside the
     * lock, because that is the shape the decade path needs; costing this path
     * nothing is the reason one core can serve both.
     */
    @JvmStatic
    fun build(
        context: Context,
        pool: List<String>,
        bucket: Long,
    ): File? = CompositeArt.build(context, idFor(pool), bucket) { _, _ -> pool }
}
