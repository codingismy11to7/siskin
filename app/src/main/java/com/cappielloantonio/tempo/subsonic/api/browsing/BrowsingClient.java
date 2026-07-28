package com.cappielloantonio.tempo.subsonic.api.browsing;

import android.util.Log;

import com.cappielloantonio.tempo.subsonic.RetrofitClient;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import retrofit2.Call;

public class BrowsingClient {
    private static final String TAG = "BrowsingClient";

    private final Subsonic subsonic;
    private final BrowsingService browsingService;

    public BrowsingClient(Subsonic subsonic) {
        this.subsonic = subsonic;
        this.browsingService = new RetrofitClient(subsonic).getRetrofit().create(BrowsingService.class);
    }

    public Call<ApiResponse> getArtists() {
        Log.d(TAG, "getArtists()");
        return browsingService.getArtists(subsonic.getParams());
    }

    public Call<ApiResponse> getArtist(String id) {
        Log.d(TAG, "getArtist()");
        return browsingService.getArtist(subsonic.getParams(), id);
    }

    public Call<ApiResponse> getAlbum(String id) {
        Log.d(TAG, "getAlbum()");
        return browsingService.getAlbum(subsonic.getParams(), id);
    }

    public Call<ApiResponse> getSimilarSongs(String id, int count) {
        Log.d(TAG, "getSimilarSongs()");
        return browsingService.getSimilarSongs(subsonic.getParams(), id, count);
    }

    public Call<ApiResponse> getSimilarSongs2(String id, int limit) {
        Log.d(TAG, "getSimilarSongs2()");
        return browsingService.getSimilarSongs2(subsonic.getParams(), id, limit);
    }
}
