package com.cappielloantonio.tempo.subsonic.models

import androidx.annotation.Keep

@Keep
class SubsonicResponse {
    var error: Error? = null
    var similarSongs2: SimilarSongs2? = null
    var similarSongs: SimilarSongs? = null
    var randomSongs: Songs? = null
    var albumList2: AlbumList2? = null
    var playlist: PlaylistWithSongs? = null
    var playlists: Playlists? = null
    var searchResult3: SearchResult3? = null
    var album: AlbumWithSongsID3? = null
    var artist: ArtistWithAlbumsID3? = null
    var artists: ArtistsID3? = null
    var status: String? = null
    var version: String? = null
    var type: String? = null
    var serverVersion: String? = null
    var openSubsonic: Boolean? = null
}