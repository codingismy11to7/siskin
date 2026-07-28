package com.cappielloantonio.tempo.subsonic.api.playlist;

import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

public interface PlaylistService {
    @GET("getPlaylists")
    Call<ApiResponse> getPlaylists(@QueryMap Map<String, String> params);

    @GET("getPlaylist")
    Call<ApiResponse> getPlaylist(@QueryMap Map<String, String> params, @Query("id") String id);

}
