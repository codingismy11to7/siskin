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

    // Android Auto System functions
    const val ALBUM_ID = "[albumID]"
    const val ARTIST_ID = "[artistID]"
    const val JUMP_TO_ALBUMS_ID = "[jumpToAlbumsID]"
    const val JUMP_TO_ARTISTS_ID = "[jumpToArtistsID]"
    const val ARTISTS_BY_ALBUMS_ID = "[artistsByAlbumsID]"

    // Android Auto Source tag
    const val QUEUE_CACHED_SOURCE = "[aaQueueCachedSource]"

    // TRANSITIONAL -- referenced by AutomotiveRepository, MadeForYouBuilder and
    // InstantMixBuilder, all of which are deleted or reduced in Tasks 3-4. Three of
    // these constants (INSTANTMIX_SOURCE, MADE_FOR_YOU_SOURCE and
    // NUMBER_OF_TRACKS_IN_SMALL_MIX) are also referenced by
    // service/MediaLibraryServiceCallback.kt and service/TracksChangedExtension.kt,
    // which hold the mix-playback handlers for those sources; removing those
    // handlers is part of the later task that deletes the builders.
    // This block must be empty by the end of Task 4.
    const val MAX_SHUFFLE_ITEMS = 100
    const val NUMBER_OF_DISPLAYED_ALBUMS = 15
    const val NUMBER_OF_DISPLAYED_PODCASTS = 100
    const val NUMBER_OF_DISPLAYED_RECENT_TRACKS = 100
    const val HOME_ID = "[homeID]"
    const val LAST_PLAYED_ID = "[lastPlayedID]"
    const val MOST_PLAYED_ID = "[mostPlayedID]"
    const val PODCAST_ID = "[podcastID]"
    const val RADIO_ID = "[radioID]"
    const val RECENTLY_ADDED_ID = "[recentlyAddedID]"
    const val MADE_FOR_YOU_ID = "[madeForYouID]"
    const val STARRED_BUNDLE_ID = "[starredBundleID]"
    const val STARRED_TRACKS_ID = "[starredTracksID]"
    const val STARRED_ALBUMS_ID = "[starredAlbumsID]"
    const val STARRED_ARTISTS_ID = "[starredArtistsID]"
    const val RANDOM_ID = "[randomID]"
    const val FOLDER_ID = "[folderID]"
    const val GENRES_ID = "[genresID]"
    const val TRACKS_ID = "[TracksID]"
    const val RECENT_TRACKS_ID = "[recentTracksID]"
    const val DOWNLOADED_ID = "[downloadedID]"
    const val INDEX_ID = "[indexID]"
    const val DIRECTORY_ID = "[directoryID]"
    const val JUMP_TO_STARRED_ALBUMS_ID = "[jumpToStarredAlbumsID]"
    const val JUMP_TO_STARRED_ARTISTS_ID = "[jumpToStarredArtistsID]"
    const val QUICKMIX_ID = "[quickmixID]"
    const val MYMIX_ID = "[mymixID]"
    const val DISCOVERYMIX_ID = "[discoverymixID]"
    const val INSTANTMIX_SOURCE = "[instantMixSource]"
    const val MADE_FOR_YOU_SOURCE = "[madeForYouSource]"
    const val MIN_TRACKS_SMALL_MIX = 20
    const val MIN_TRACKS_MEDIUM_MIX = 30
    const val MIN_TRACKS_LARGE_MIX = 40
    const val NUMBER_OF_TRACKS_IN_SMALL_MIX = 12
    const val NUMBER_OF_TRACKS_IN_MEDIUM_MIX = 15
    const val NUMBER_OF_TRACKS_IN_LARGE_MIX = 18
    const val NUMBER_OF_RECENT_ALBUMS_FOR_MIX = 20
    const val NUMBER_OF_RECENT_TRACKS_FOR_MIX = 50
}
