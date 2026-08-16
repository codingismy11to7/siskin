package com.cappielloantonio.tempo.util

/**
 * Which browse destinations are root tabs, and in what order.
 *
 * Deliberately free of Android types: this holds every decision the feature
 * makes about order, so keeping it a pure function over two lists is what lets
 * all of it be tested without a Context. Serialization lives in [Preferences]
 * and tree construction in
 * [com.cappielloantonio.tempo.service.MediaBrowserTree]; neither repeats any
 * logic from here.
 *
 * See docs/decisions/2026-08-14-customizable-browse-tabs-design.md.
 */
object BrowseTabOrder {

    /**
     * The car renders four root children and silently drops a fifth, and the
     * fourth is always More -- so three are the user's to choose.
     */
    const val ROOT_TAB_COUNT = 3

    /**
     * Today's browse root, exactly: Playlists, Artists and Albums as tabs, with
     * Decades under More. An install that never opens the reorder screen must
     * see no change, so this list is not a preference about what is nicest --
     * it is a reproduction of the shipped behaviour.
     *
     * A destination added later is appended here *and* picked up by [resolve]'s
     * append rule for anyone who already saved an order.
     */
    val DEFAULT_ORDER: List<String> = listOf(
        Constants.PLAYLIST_ID,
        Constants.ARTISTS_ID,
        Constants.ALBUMS_ID,
        Constants.DECADES_ID
    )

    /**
     * Reconciles what was saved with what this build actually knows.
     *
     * One rule covers all three ways the two can disagree, which is why there
     * is no version field and no migration code:
     *
     * - **First run.** Nothing saved, so every known id is appended in default
     *   order and the result *is* the default.
     * - **A destination added in a later release.** Unknown to the save, so
     *   appended last -- it lands under More and cannot displace a tab the user
     *   chose. This is deliberate; see the spec.
     * - **A destination removed in a later release.** Still in the save but no
     *   longer known, so dropped.
     *
     * Duplicates in a corrupt save collapse to the first occurrence.
     */
    fun resolve(saved: List<String>, known: List<String> = DEFAULT_ORDER): List<String> {
        val kept = saved.distinct().filter { known.contains(it) }
        return kept + known.filterNot { kept.contains(it) }
    }

    /** The first [ROOT_TAB_COUNT] ids, or fewer if the pool is smaller. */
    fun rootTabs(resolved: List<String>): List<String> = resolved.take(ROOT_TAB_COUNT)

    /** Everything below the line, in order. Empty when the pool is small. */
    fun moreRows(resolved: List<String>): List<String> = resolved.drop(ROOT_TAB_COUNT)
}
