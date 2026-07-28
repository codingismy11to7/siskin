package com.cappielloantonio.tempo.subsonic.api.playlist;

import android.util.Log;

import com.cappielloantonio.tempo.subsonic.RetrofitClient;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import retrofit2.Call;

public class PlaylistClient {
    private static final String TAG = "BrowsingClient";

    private final Subsonic subsonic;
    private final PlaylistService playlistService;

    public PlaylistClient(Subsonic subsonic) {
        this.subsonic = subsonic;
        this.playlistService = new RetrofitClient(subsonic).getRetrofit().create(PlaylistService.class);
    }

    public Call<ApiResponse> getPlaylists() {
        Log.d(TAG, "getPlaylists()");
        return playlistService.getPlaylists(subsonic.getParams());
    }

    public Call<ApiResponse> getPlaylist(String id) {
        Log.d(TAG, "getPlaylist()");
        return playlistService.getPlaylist(subsonic.getParams(), id);
    }

}
