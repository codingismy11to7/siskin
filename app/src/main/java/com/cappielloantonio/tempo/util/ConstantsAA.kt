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

    // More tab
    const val MORE_ID = "[moreID]"
    const val SELECT_LIBRARY_ID = "[selectLibraryID]"

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
    const val ARTISTS_BY_ALBUMS_ID = "[artistsByAlbumsID]"

    // Android Auto Source tag
    const val QUEUE_CACHED_SOURCE = "[aaQueueCachedSource]"
}
