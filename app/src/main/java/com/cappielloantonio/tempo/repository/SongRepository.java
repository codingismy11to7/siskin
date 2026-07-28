package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SongRepository {

    public MutableLiveData<List<Child>> getContinuousMix(String trackId, String artistId, int count) {
        MutableLiveData<List<Child>> continuousMix = new MutableLiveData<>();

        if (artistId != null && !artistId.isEmpty()) {
            App.getSubsonicClientInstance(false)
                    .getBrowsingClient()
                    .getSimilarSongs2(artistId, count)
                    .enqueue(new Callback<ApiResponse>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                            List<Child> songs = extractSongs(response, "similarSongs2");
                            if (!songs.isEmpty()) {
                                continuousMix.postValue(songs);
                            } else {
                                fetchContinuousFallback(trackId, count, continuousMix);
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                            fetchContinuousFallback(trackId, count, continuousMix);
                        }
                    });
        } else {
            fetchContinuousFallback(trackId, count, continuousMix);
        }

        return continuousMix;
    }

    private void fetchContinuousFallback(String trackId, int count, MutableLiveData<List<Child>> target) {
        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSimilarSongs(trackId, count)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        target.postValue(extractSongs(response, "similarSongs"));
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        target.postValue(new ArrayList<>());
                    }
                });
    }

    private List<Child> extractSongs(Response<ApiResponse> response, String type) {
        if (response.isSuccessful() && response.body() != null) {
            SubsonicResponse res = response.body().getSubsonicResponse();
            List<Child> list = null;
            if (type.equals("similarSongs") && res.getSimilarSongs() != null) {
                list = res.getSimilarSongs().getSongs();
            } else if (type.equals("similarSongs2") && res.getSimilarSongs2() != null) {
                list = res.getSimilarSongs2().getSongs();
            }
            return (list != null) ? list : new ArrayList<>();
        }

        return new ArrayList<>();
    }

    public MutableLiveData<List<Child>> getRandomSample(int number, Integer fromYear, Integer toYear) {
        MutableLiveData<List<Child>> randomSongsSample = new MutableLiveData<>();
        App.getSubsonicClientInstance(false).getAlbumSongListClient().getRandomSongs(number, fromYear, toYear).enqueue(new Callback<ApiResponse>() {
            @Override public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                List<Child> songs = new ArrayList<>();
                if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getRandomSongs() != null) {
                    List<Child> returned = response.body().getSubsonicResponse().getRandomSongs().getSongs();
                    if (returned != null) {
                        songs.addAll(returned);
                    }
                }
                randomSongsSample.setValue(songs);
            }
            @Override public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {}
        });
        return randomSongsSample;
    }

    public void scrobble(String id, boolean submission) {
        App.getSubsonicClientInstance(false).getMediaAnnotationClient().scrobble(id, submission).enqueue(new Callback<ApiResponse>() {
            @Override public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {}
            @Override public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {}
        });
    }
}
