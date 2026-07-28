package com.cappielloantonio.tempo.repository;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.interfaces.CredentialStateCallback;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.ResponseStatus;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SystemRepository {
    /**
     * Distinguishes "the server rejected our credentials" from "we could not reach
     * the server". Only the first justifies offering a sign-in button.
     */
    public void checkCredentialState(CredentialStateCallback callback) {
        App.getSubsonicClientInstance(false)
                .getSystemClient()
                .ping()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        ApiResponse body = response.body();
                        callback.onResult(isRejection(body == null ? null : body.getSubsonicResponse()));
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        // Transport failure: the server is unreachable, not refusing us.
                        Log.d("SystemRepository", "credential check could not reach the server", t);
                        callback.onResult(false);
                    }
                });
    }

    /**
     * Whether a ping response means the server actively rejected our credentials,
     * as opposed to any other failure. Null-safe on purpose: Retrofit puts a
     * non-2xx payload in errorBody() and leaves body() null, and an offline device
     * gets a synthesized 504 from the only-if-cached interceptor. Those must not
     * be read as a rejection, or the car would tell the user to sign in when the
     * real problem is the network.
     */
    static boolean isRejection(SubsonicResponse subsonicResponse) {
        if (subsonicResponse == null) return false;
        if (!ResponseStatus.FAILED.equals(subsonicResponse.getStatus())) return false;
        com.cappielloantonio.tempo.subsonic.models.Error apiError = subsonicResponse.getError();
        return isAuthFailure(apiError != null ? apiError.getCode() : null);
    }

    /**
     * Subsonic error codes meaning the credentials themselves were rejected, so
     * signing in again can plausibly fix it. Codes like 30 (server must upgrade)
     * are failures that a new password will not repair.
     *
     * Lived in CredentialGate until that became Plex-shaped. Dies with the rest
     * of this class when the browse tree moves to Plex, where a rejection is
     * simply HTTP 401.
     */
    static boolean isAuthFailure(Integer code) {
        return code != null && (code == 40 || code == 41 || code == 50);
    }
}
