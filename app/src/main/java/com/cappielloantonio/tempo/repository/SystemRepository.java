package com.cappielloantonio.tempo.repository;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.github.models.LatestRelease;
import com.cappielloantonio.tempo.interfaces.CredentialStateCallback;
import com.cappielloantonio.tempo.interfaces.SystemCallback;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.OpenSubsonicExtension;
import com.cappielloantonio.tempo.subsonic.models.ResponseStatus;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.cappielloantonio.tempo.util.CredentialGate;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SystemRepository {
    public void checkUserCredential(SystemCallback callback) {
        App.getSubsonicClientInstance(false)
                .getSystemClient()
                .ping()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull retrofit2.Response<ApiResponse> response) {
                        if (response.body() != null) {
                            if (response.body().getSubsonicResponse().getStatus().equals(ResponseStatus.FAILED)) {
                                com.cappielloantonio.tempo.subsonic.models.Error apiError = response.body().getSubsonicResponse().getError();
                                callback.onError(new Exception(apiError != null ? apiError.getCode() + " - " + apiError.getMessage() : "Unknown server error"));
                            } else if (response.body().getSubsonicResponse().getStatus().equals(ResponseStatus.OK)) {
                                String password = response.raw().request().url().queryParameter("p");
                                String token = response.raw().request().url().queryParameter("t");
                                String salt = response.raw().request().url().queryParameter("s");
                                callback.onSuccess(password, token, salt);
                            } else {
                                callback.onError(new Exception("Empty response"));
                            }
                        } else {
                            callback.onError(new Exception(String.valueOf(response.code())));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        callback.onError(new Exception(t.getMessage()));
                    }
                });
    }

    /**
     * Distinguishes "the server rejected our credentials" from "we could not reach
     * the server". checkUserCredential flattens both into onError(Exception), which
     * is not enough to decide whether offering a sign-in button would help.
     */
    public void checkCredentialState(CredentialStateCallback callback) {
        App.getSubsonicClientInstance(false)
                .getSystemClient()
                .ping()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.body() == null || response.body().getSubsonicResponse() == null) {
                            callback.onResult(false);
                            return;
                        }

                        SubsonicResponse subsonicResponse = response.body().getSubsonicResponse();

                        if (!ResponseStatus.FAILED.equals(subsonicResponse.getStatus())) {
                            callback.onResult(false);
                            return;
                        }

                        com.cappielloantonio.tempo.subsonic.models.Error apiError = subsonicResponse.getError();
                        callback.onResult(CredentialGate.isAuthFailure(apiError != null ? apiError.getCode() : null));
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        // Transport failure: the server is unreachable, not refusing us.
                        Log.d("SystemRepository", "credential check could not reach the server", t);
                        callback.onResult(false);
                    }
                });
    }

    public MutableLiveData<SubsonicResponse> ping() {
        MutableLiveData<SubsonicResponse> pingResult = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getSystemClient()
                .ping()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            pingResult.postValue(response.body().getSubsonicResponse());
                        } else {
                            pingResult.postValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        pingResult.postValue(null);
                    }
                });

        return pingResult;
    }

    public MutableLiveData<List<OpenSubsonicExtension>> getOpenSubsonicExtensions() {
        MutableLiveData<List<OpenSubsonicExtension>> extensionsResult = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getSystemClient()
                .getOpenSubsonicExtensions()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            extensionsResult.postValue(response.body().getSubsonicResponse().getOpenSubsonicExtensions());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        extensionsResult.postValue(null);
                    }
                });

        return extensionsResult;
    }

    public MutableLiveData<LatestRelease> checkTempoUpdate() {
        MutableLiveData<LatestRelease> latestRelease = new MutableLiveData<>();

        App.getGithubClientInstance()
                .getReleaseClient()
                .getLatestRelease()
                .enqueue(new Callback<LatestRelease>() {
                    @Override
                    public void onResponse(@NonNull Call<LatestRelease> call, @NonNull Response<LatestRelease> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            latestRelease.postValue(response.body());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<LatestRelease> call, @NonNull Throwable t) {
                        latestRelease.postValue(null);
                    }
                });

        return latestRelease;
    }
}
