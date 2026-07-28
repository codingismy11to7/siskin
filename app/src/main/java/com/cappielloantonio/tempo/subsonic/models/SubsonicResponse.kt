package com.cappielloantonio.tempo.subsonic.models

import androidx.annotation.Keep

@Keep
class SubsonicResponse {
    var error: Error? = null
    var topSongs: TopSongs? = null
    var similarSongs2: SimilarSongs2? = null
    var similarSongs: SimilarSongs? = null
    var artistInfo2: ArtistInfo2? = null
    var artistInfo: ArtistInfo? = null
    var albumInfo: AlbumInfo? = null
    var songsByGenre: Songs? = null
    var randomSongs: Songs? = null
    var albumList2: AlbumList2? = null
    var albumList: AlbumList? = null
    var user: User? = null
    var users: Users? = null
    var license: License? = null
    var playlist: PlaylistWithSongs? = null
    var playlists: Playlists? = null
    var searchResult3: SearchResult3? = null
    var searchResult2: SearchResult2? = null
    var searchResult: SearchResult? = null
    var nowPlaying: NowPlaying? = null
    var song: Child? = null
    var album: AlbumWithSongsID3? = null
    var artist: ArtistWithAlbumsID3? = null
    var artists: ArtistsID3? = null
    var directory: Directory? = null
    var indexes: Indexes? = null
    var status: String? = null
    var version: String? = null
    var type: String? = null
    var serverVersion: String? = null
    var openSubsonic: Boolean? = null
    var openSubsonicExtensions: List<OpenSubsonicExtension>? = null
}