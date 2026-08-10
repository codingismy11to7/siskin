package com.cappielloantonio.tempo.util

/**
 * The payload of a [ConstantsAA.DECADE_ID] row: which library, then which
 * decade.
 *
 * A decade row is the one browsable row in this app whose identity is not
 * server-specific. Every other row is keyed by a Plex ratingKey, so switching
 * servers changes every album, artist and playlist id and the car sees a browse
 * list of entirely new rows. A decade's key is the first year -- "1980" -- and
 * is the same string on every server, which had two consequences. A stale
 * `[decadeID]1980` tapped after a switch queried the *new* server and happened
 * to work only because the filter value means the same thing there. And, worse,
 * `com.android.car.media` kept its adapter across the switch, diffed eight
 * decades against five, and found one row *changed* rather than removed --
 * because the row's artworkUri now carries the library while its id did not.
 * The car binds a change notification directly, at a position valid for the old
 * list, against the already-latched shorter one, and crashes with an
 * IndexOutOfBoundsException out of `BrowseAdapter.onBindViewHolder`. Binding
 * off a change like that is a defect in the car's app and not ours to fix; what
 * is ours is to never hand it a change event. Putting the library in the id
 * makes a server switch a pure remove-and-insert, which is what every other row
 * type already gets for free.
 *
 * [scope] is [com.cappielloantonio.tempo.provider.DecadeCompositeArt.scopeOf]'s
 * string and nothing else -- the same one definition of "which library" the
 * artwork URI and the provider's guard compare against, so there is no second
 * encoding of it to drift.
 *
 * **Pipe rather than colon, following [ConstantsAA.PICK_LIBRARY_ID]'s
 * convention**, and safe for the same kind of reason it is there: neither side
 * can contain one. A scope is a normalised machine identifier -- letters and
 * digits, or a hyphenated sentinel -- then `-`, then a section key, which is an
 * integer. That side is genuinely constrained, by
 * `DecadeCompositeArt.isSafeCacheIdentifier`.
 *
 * The decade side is not validated here, and it is worth being exact about why
 * it is still safe. The provider's `\d{4}` guard runs on the *read* path, on an
 * incoming URI -- it never sees this string being minted. What actually holds is
 * that the decade arrives from Plex's `/library/sections/{key}/decade` index,
 * which returns years. [decadeIn] takes the *last* pipe regardless, so a stray
 * one in the scope could not corrupt the split even if one appeared.
 *
 * ### This kills the change event on a server switch, not every change event
 *
 * The artwork URI also carries an hour bucket, and the id does not. So the first
 * browse after the hour rolls hands the car N change events for N unchanged ids.
 * That is safe on its own -- the list is the same size, so every index the car
 * binds is valid -- and it is why the bucket is deliberately *not* in the id:
 * putting it there would churn every decade row hourly and stale every persisted
 * id. The invariant this object establishes is therefore "no change event
 * *alongside a removal*", which is the shape that crashes. A library gaining or
 * losing a decade in the same refresh as an hour roll would still reach it, and
 * is rare enough to accept.
 *
 * The composite is opaque everywhere except [decadeIn], which
 * `PlexBrowseRepository.decadeTracks` calls to build the Plex filter. That is
 * deliberate: the shuffle row `getDecadeTracks` puts at index 0 and the guard
 * `MediaLibraryServiceCallback.cachedDecadeTracks` matches it against are both
 * built from the whole key, so they agree by construction rather than by two
 * call sites parsing it the same way.
 */
object DecadeKey {

    private const val SEPARATOR = '|'

    /** The row id payload for [decade] in the library [scope] names. */
    @JvmStatic
    fun of(scope: String, decade: String): String = "$scope$SEPARATOR$decade"

    /**
     * The bare decade to filter Plex on.
     *
     * Getting this wrong is silent: measured against PMS 1.43.3, a decade
     * filter the server does not recognise answers HTTP 200 with an empty
     * container, so a composite key reaching the query renders as an empty
     * decade rather than as an error.
     *
     * A key with no separator is its own decade. That is not a defensive
     * flourish -- it is exactly the shape this app minted before the library
     * moved into the id, so an id the car persisted across the upgrade still
     * queries the right decade instead of the whole string.
     */
    @JvmStatic
    fun decadeIn(key: String): String = key.substringAfterLast(SEPARATOR)
}
