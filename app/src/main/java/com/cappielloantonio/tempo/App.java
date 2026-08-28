package com.cappielloantonio.tempo;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.car.VehicleInfoReader;
import com.cappielloantonio.tempo.helper.ThemeHelper;
import com.cappielloantonio.tempo.util.Preferences;

public class App extends Application {
    private static App instance;
    private static SharedPreferences preferences;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();

        SharedPreferences sharedPreferences =
                getApplicationContext()
                        .getSharedPreferences(
                                getApplicationContext().getPackageName() + "_preferences",
                                Context.MODE_PRIVATE);
        String themePref = sharedPreferences.getString(Preferences.THEME, ThemeHelper.DEFAULT_MODE);
        ThemeHelper.applyTheme(themePref);

        instance = this;
        Context applicationContext = getApplicationContext();
        preferences =
                applicationContext.getSharedPreferences(
                        applicationContext.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        clearStaleCredentialKeys(preferences);
        VehicleInfoReader.start(applicationContext);
    }

    /**
     * Both Plex tokens moved to the system account, and the keys they used to occupy are not read
     * anywhere after that move. Deleting them is hygiene rather than migration: existing installs
     * sign in again by design, and a dead account token left in a preferences file outlives the
     * reason it was there -- including into any backup that file is ever part of.
     *
     * <p>Runs on every start rather than behind a one-shot flag. It is two removes against keys
     * that are absent after the first time, which is cheaper than a flag that has to be correct
     * forever.
     */
    static void clearStaleCredentialKeys(SharedPreferences preferences) {
        preferences.edit().remove("plex_token").remove("plex_server_token").apply();
    }

    /**
     * Fails rather than fabricates, and {@link #getContext()} inherits that by going through here.
     * The field is assigned in {@link #onCreate()}, so a null here means someone reached for the
     * Application before the system built it -- and the only thing this class can hand back at that
     * point is an object that is not the Application. That is what it used to do: {@code new App()}
     * has no base context attached, so every Context method on it throws NPE on {@code mBase}. It
     * stayed hidden because nearly every caller immediately reads {@code preferences}, a static
     * field that answers correctly whichever instance returns it.
     */
    public static App getInstance() {
        if (instance == null) {
            throw new IllegalStateException("App.getInstance() before Application.onCreate()");
        }

        return instance;
    }

    public static Context getContext() {
        return getInstance().getApplicationContext();
    }

    public SharedPreferences getPreferences() {
        if (preferences == null) {
            Context applicationContext = getApplicationContext();
            preferences =
                    applicationContext.getSharedPreferences(
                            applicationContext.getPackageName() + "_preferences",
                            Context.MODE_PRIVATE);
        }

        return preferences;
    }
}
