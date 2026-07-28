package com.cappielloantonio.tempo.subsonic.api.browsing;

import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

public interface BrowsingService {
    @GET("getArtists")
    Call<ApiResponse> getArtists(@QueryMap Map<String, String> params);

    @GET("getArtist")
    Call<ApiResponse> getArtist(@QueryMap Map<String, String> params, @Query("id") String id);

    @GET("getAlbum")
    Call<ApiResponse> getAlbum(@QueryMap Map<String, String> params, @Query("id") String id);

    @GET("getSimilarSongs")
    Call<ApiResponse> getSimilarSongs(@QueryMap Map<String, String> params, @Query("id") String id, @Query("count") int count);

    @GET("getSimilarSongs2")
    Call<ApiResponse> getSimilarSongs2(@QueryMap Map<String, String> params, @Query("id") String id, @Query("count") int count);
}
