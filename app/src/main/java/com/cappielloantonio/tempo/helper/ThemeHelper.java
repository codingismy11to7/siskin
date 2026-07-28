package com.cappielloantonio.tempo.helper;

import android.content.res.Configuration;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.android.material.color.DynamicColors;

public class ThemeHelper {
    private static final String TAG = "ThemeHelper";

    public static final String LIGHT_MODE = "light";
    public static final String DARK_MODE = "dark";
    public static final String DEFAULT_MODE = "default";
    public static final String AMOLED_MODE = "amoled";

    public static void applyTheme(@NonNull String themePref) {
        switch (themePref) {
            case LIGHT_MODE: {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            }
            case DARK_MODE:
            case AMOLED_MODE: {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            }
            default: {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
                }
                break;
            }
        }
    }

    /**
     * Applies the user's theme choice to an activity. Must be called before
     * super.onCreate(). Called by CarSignInActivity, the app's only activity.
     */
    public static void applyActivityTheme(AppCompatActivity activity) {
        String theme = Preferences.getTheme();
        String darkStyle = Preferences.getDarkThemeStyle();
        boolean isAmoled = AMOLED_MODE.equals(darkStyle);
        boolean applyAmoled = false;

        if (DARK_MODE.equals(theme) || AMOLED_MODE.equals(theme)) {
            if (isAmoled) {
                activity.setTheme(R.style.AppTheme_Amoled);
                applyAmoled = true;
            }
        } else if (DEFAULT_MODE.equals(theme)) {
            int nightModeFlags = activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES && isAmoled) {
                activity.setTheme(R.style.AppTheme_Amoled);
                applyAmoled = true;
            }
        }

        DynamicColors.applyToActivityIfAvailable(activity);
        if (applyAmoled) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_App_Amoled, true);
        }
    }
}