package com.cappielloantonio.tempo.util

/**
 * The payload of a [Constants.HUB_ID] row: which library, then the server's own
 * query for that hub.
 *
 * The scope is here for the reason [DecadeKey] carries one -- a stale row
 * tapped after a server switch must not query the new server -- and it matters
 * more here than there. A hub key names a section by number
 * (`/library/sections/7/all?...`), so the same id pointed at a different server
 * would address whatever section 7 happens to be on *that* machine, which may
 * not be music at all.
 *
 * **Pipe rather than colon**, following [Constants.PICK_LIBRARY_ID] and
 * [DecadeKey]. A scope is a normalised machine identifier then `-` then an
 * integer section key, so it cannot contain one; [keyIn] takes the *first* pipe
 * rather than the last, because unlike a decade the key side is a URL and has
 * no such guarantee.
 */
object HubKey {

    private const val SEPARATOR = '|'

    /** The row id payload for the hub [key] in the library [scope] names. */
    @JvmStatic
    fun of(scope: String, key: String): String = "$scope$SEPARATOR$key"

    /**
     * The bare server path to follow.
     *
     * A payload with no separator is its own key, which keeps an id minted by
     * an older build queryable rather than sending the whole composite as a
     * path.
     */
    @JvmStatic
    fun keyIn(payload: String): String =
        if (payload.contains(SEPARATOR)) payload.substringAfter(SEPARATOR) else payload
}
