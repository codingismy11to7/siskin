package com.cappielloantonio.tempo.subsonic.api.albumsonglist;

import android.util.Log;

import com.cappielloantonio.tempo.subsonic.RetrofitClient;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import retrofit2.Call;

public class AlbumSongListClient {
    private static final String TAG = "BrowsingClient";

    private final Subsonic subsonic;
    private final AlbumSongListService albumSongListService;

    public AlbumSongListClient(Subsonic subsonic) {
        this.subsonic = subsonic;
        this.albumSongListService = new RetrofitClient(subsonic).getRetrofit().create(AlbumSongListService.class);
    }

    public Call<ApiResponse> getAlbumList2(String type, int size, int offset, Integer fromYear, Integer toYear) {
        Log.d(TAG, "getAlbumList2()");
        return albumSongListService.getAlbumList2(subsonic.getParams(), type, size, offset, fromYear, toYear);
    }

    public Call<ApiResponse> getRandomSongs(int size, Integer fromYear, Integer toYear) {
        Log.d(TAG, "getRandomSongs()");
        return albumSongListService.getRandomSongs(subsonic.getParams(), size, fromYear, toYear);
    }

    public Call<ApiResponse> getRandomSongs(int size, Integer fromYear, Integer toYear, String genre) {
        Log.d(TAG, "getRandomSongs()");
        return albumSongListService.getRandomSongs(subsonic.getParams(), size, fromYear, toYear, genre);
    }

}
