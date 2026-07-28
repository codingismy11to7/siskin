package com.cappielloantonio.tempo;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.helper.ThemeHelper;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.SubsonicPreferences;
import com.cappielloantonio.tempo.util.ClientCertManager;
import com.cappielloantonio.tempo.util.Preferences;

public class App extends Application {
    private static App instance;
    private static Context context;
    private static Subsonic subsonic;
    private static SharedPreferences preferences;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();

        SharedPreferences sharedPreferences = getApplicationContext().getSharedPreferences(getApplicationContext().getPackageName() + "_preferences", Context.MODE_PRIVATE);
        String themePref = sharedPreferences.getString(Preferences.THEME, ThemeHelper.DEFAULT_MODE);
        ThemeHelper.applyTheme(themePref);

        instance = new App();
        context = getApplicationContext();
        preferences = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);

        ClientCertManager.setupSslSocketFactory(context);
    }

    public static App getInstance() {
        if (instance == null) {
            instance = new App();
        }

        return instance;
    }

    public static Context getContext() {
        if (context == null) {
            context = getInstance();
        }

        return context;
    }

    public static Subsonic getSubsonicClientInstance(boolean override) {
        if (subsonic == null || override) {
            subsonic = getSubsonicClient();
        }
        return subsonic;
    }
    
    public SharedPreferences getPreferences() {
        if (preferences == null) {
            preferences = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        }

        return preferences;
    }

    private static Subsonic getSubsonicClient() {
        SubsonicPreferences preferences = getSubsonicPreferences();

        if (preferences.getAuthentication() != null) {
            if (preferences.getAuthentication().getPassword() != null)
                Preferences.setPassword(preferences.getAuthentication().getPassword());
            if (preferences.getAuthentication().getToken() != null)
                Preferences.setToken(preferences.getAuthentication().getToken());
            if (preferences.getAuthentication().getSalt() != null)
                Preferences.setSalt(preferences.getAuthentication().getSalt());
        }

        return new Subsonic(preferences);
    }

    @NonNull
    private static SubsonicPreferences getSubsonicPreferences() {
        String server = Preferences.getInUseServerAddress();
        String username = Preferences.getUser();
        String password = Preferences.getPassword();
        String token = Preferences.getToken();
        String salt = Preferences.getSalt();
        boolean isLowSecurity = Preferences.isLowScurity();

        SubsonicPreferences preferences = new SubsonicPreferences();
        preferences.setServerUrl(server);
        preferences.setUsername(username);
        preferences.setAuthentication(password, token, salt, isLowSecurity);

        return preferences;
    }
}
