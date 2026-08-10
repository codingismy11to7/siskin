package com.cappielloantonio.tempo.util

object ConstantsAA {
    // Android Auto max items
    const val MAX_ITEMS = 500

    // Android Root function
    const val ROOT_ID = "[rootID]"

    // Android Auto browse tabs
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
     * Prefixes of the synthetic shuffle rows, each carrying the ratingKey of the
     * thing to shuffle after it -- or, for [SHUFFLE_DECADE_ID], the same
     * [DecadeKey] payload [DECADE_ID] carries, whole and unsplit. That is what
     * lets `MediaLibraryServiceCallback.cachedDecadeTracks` rebuild the row's id
     * from what the car sends back and match it against the browse list it
     * cached. Nothing on the server answers to these ids: they are playable
     * rows with no stream, and MediaLibrarySessionCallback swaps one for its
     * subject's tracks when it is tapped.
     *
     * Three prefixes rather than one plus an embedded kind, because the prefix is
     * what the callback dispatches on -- it decides which repository call
     * supplies the tracks.
     */
    const val SHUFFLE_ARTIST_ID = "[shuffleArtistID]"
    const val SHUFFLE_PLAYLIST_ID = "[shufflePlaylistID]"
    const val SHUFFLE_DECADE_ID = "[shuffleDecadeID]"

    // Android Auto Source tag
    const val QUEUE_CACHED_SOURCE = "[aaQueueCachedSource]"
}
