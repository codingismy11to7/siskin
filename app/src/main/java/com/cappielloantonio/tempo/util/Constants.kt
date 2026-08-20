package com.cappielloantonio.tempo.util

object Constants {
    const val MEDIA_TYPE_MUSIC = "music"
    const val MEDIA_TYPE_PODCAST = "podcast"

    const val PLAYABLE_MEDIA_LIMIT = 100
    const val PRE_PLAYABLE_MEDIA = 15

    const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON = "android.media3.session.demo.SHUFFLE_ON"
    const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF = "android.media3.session.demo.SHUFFLE_OFF"
    const val CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF = "android.media3.session.demo.REPEAT_OFF"
    const val CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE = "android.media3.session.demo.REPEAT_ONE"
    const val CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL = "android.media3.session.demo.REPEAT_ALL"

    // ─────────────────────────────────────────────────────────────
    // The browse tree the car draws
    // ─────────────────────────────────────────────────────────────

    // Most items a browse node serves.
    const val MAX_ITEMS = 500

    // The browse tree's root.
    const val ROOT_ID = "[rootID]"

    // The browse tabs.
    const val ALBUMS_ID = "[albumsID]"
    const val ARTISTS_ID = "[artistsID]"
    const val PLAYLIST_ID = "[playlistID]"

    /**
     * How many items a browse window holds.
     *
     * The car keeps roughly the first 227KB of a browse list and silently drops
     * the rest -- measured at 293 artists (774B each) and 282 albums (806B
     * each), two surfaces agreeing within 0.2%. That is the backstop, not the
     * target: a list long enough to hit it is already unusable in a car, so this
     * is set for legibility while driving and the byte ceiling never comes near.
     */
    const val WINDOW_SIZE = 50

    /** Prefix; the remainder is the window's first index, e.g. "50". */
    const val ARTIST_WINDOW_ID = "[artistWindowID]"

    /** Prefix; the remainder is the window's first index, e.g. "50". */
    const val ALBUM_WINDOW_ID = "[albumWindowID]"

    /**
     * Prefix; the remainder is a first-character bucket key exactly as the
     * server gave it -- "A", "%23" for the symbol bucket, "∆".
     *
     * Kept percent-encoded on purpose. It is spliced straight back into the
     * request path with `@Path(encoded = true)`, so decoding it here would send
     * "%2523" and address a bucket that does not exist -- answered as an empty
     * list, with no error anywhere.
     */
    const val ARTIST_LETTER_ID = "[artistLetterID]"

    // More tab
    const val MORE_ID = "[moreID]"
    const val SELECT_LIBRARY_ID = "[selectLibraryID]"

    const val DECADES_ID = "[decadesID]"

    /**
     * Prefix; the remainder is a [DecadeKey] -- "<scope>|<decade>", e.g.
     * "abc123def456-4|1980".
     *
     * The library is in there and the decade's first year alone is not, because
     * a decade key is the same string on every server. That made a decade row
     * the one row whose id survived a server switch unchanged, which crashed
     * `com.android.car.media`'s browse adapter; [DecadeKey] tells that story.
     */
    const val DECADE_ID = "[decadeID]"

    const val DISCOVER_ID = "[discoverID]"

    /**
     * Prefix; the remainder is a [HubKey] -- "<scope>|<the hub's own key>".
     *
     * The server's query rides in the id because a hub's parameters are rolled
     * server-side: nothing in this app knows that a given vault row means five
     * months rather than nine, so there is nothing to rebuild it from. See the
     * 2026-08-14 hubs browse design.
     */
    const val HUB_ID = "[hubID]"

    /** Prefix; the remainder is the server's machine identifier. */
    const val PICK_SERVER_ID = "[pickServerID]"

    /**
     * Prefix; the remainder is "<machineIdentifier>|<sectionKey>". Pipe rather
     * than colon because a machine identifier is hex and a section key is an
     * integer, so neither can contain one.
     */
    const val PICK_LIBRARY_ID = "[pickLibraryID]"

    /**
     * Prefix; the remainder is the message text itself.
     *
     * The text rides in the id so a tap on the row can return the same row
     * without a second in-memory map to keep in sync -- see
     * `LibraryPickerRepository.messageRow`, which explains why the picker
     * reports failures as rows rather than as errors.
     */
    const val PICK_MESSAGE_ID = "[pickMessageID]"

    // Android Auto System functions
    const val ALBUM_ID = "[albumID]"
    const val ARTIST_ID = "[artistID]"

    /**
     * Prefixes of the synthetic Mix rows, each carrying the ratingKey of the
     * thing to mix after it -- or, for [MIX_DECADE_ID] and [MIX_HUB_ID], the same
     * [DecadeKey] or [HubKey] payload [DECADE_ID] and [HUB_ID] carry, whole and
     * unsplit. That is what lets `MediaLibrarySessionCallback.cachedDecadeTracks`
     * rebuild the row's id from what the car sends back and match it against
     * the browse list it cached. Nothing on the server answers to these ids:
     * they are playable rows with no stream, and MediaLibrarySessionCallback
     * swaps one for its subject's tracks when it is tapped.
     *
     * Four prefixes rather than one plus an embedded kind, because the prefix is
     * what the callback dispatches on -- it decides which repository call
     * supplies the tracks.
     *
     * **The values still read "shuffle" while the rows are called Mix, and that
     * is deliberate.** An id is a wire format, not a label. The car caches a
     * browse node and echoes its ids back on a tap, so an installed build holds
     * these exact strings -- changing them would leave every cached row
     * unrecognised until the car re-fetched, and a row the callback cannot
     * dispatch on is a playable row with no stream.
     */
    const val MIX_ARTIST_ID = "[shuffleArtistID]"
    const val MIX_PLAYLIST_ID = "[shufflePlaylistID]"
    const val MIX_DECADE_ID = "[shuffleDecadeID]"
    const val MIX_HUB_ID = "[shuffleHubID]"

    // Source tag for the most recent browse list.
    const val QUEUE_CACHED_SOURCE = "[aaQueueCachedSource]"
}
