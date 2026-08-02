package com.cappielloantonio.tempo.ui.activity;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.helper.ThemeHelper;
import com.cappielloantonio.tempo.interfaces.LoginHost;
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator;
import com.cappielloantonio.tempo.ui.fragment.PlexSignInFragment;
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel;

/**
 * Sign-in screen for Android Automotive OS head units.
 *
 * Reachable two ways: the resolution PendingIntent attached to the browse error
 * when no usable credentials exist, and the car's settings gear
 * (APPLICATION_PREFERENCES). EXTRA_FORCE_SIGN_IN is how onCreate tells them
 * apart. Hosts PlexSignInFragment, which runs the Plex PIN flow: a QR code and
 * short code approved on a phone.
 *
 * Deliberately NOT marked distractionOptimized in the manifest: the pickers are
 * tap-only, but sign-in is still not something to do while driving. Without that
 * metadata the platform blocks this screen while the car is moving, which is
 * exactly the behavior we want.
 */
@UnstableApi
public class CarSignInActivity extends AppCompatActivity implements LoginHost {

    /**
     * Set by CarSignInResolution's PendingIntent. Absent when the car's settings
     * gear starts this activity, which is how the two entry points are told
     * apart.
     */
    public static final String EXTRA_FORCE_SIGN_IN =
            "us.codingismy11to7.siskin.extra.FORCE_SIGN_IN";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Pinned dark rather than following system night mode. A head unit is a
        // large screen at eye level, so a full-white panel at night is genuinely
        // unpleasant, and Plex's own apps are dark throughout -- matching them
        // makes this read as a Plex sign-in. Following the system would be the
        // better default if the user could override it, but the three-tab sweep
        // removed the settings screen, so Preferences.getTheme() is frozen at
        // "default" and no one can. Scoped to this activity's delegate, not
        // AppCompatDelegate's process-wide default, so it does not fight
        // ThemeHelper.
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        ThemeHelper.applyActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_sign_in);

        if (savedInstanceState == null) {
            boolean forceSignIn = getIntent() != null
                    && getIntent().getBooleanExtra(EXTRA_FORCE_SIGN_IN, false);
            new ViewModelProvider(this)
                    .get(PlexSignInViewModel.class)
                    .open(forceSignIn);

            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.car_sign_in_container, new PlexSignInFragment())
                    .commit();
        }
    }

    @Override
    public void onLoginSuccess() {
        BrowseTreeInvalidator.INSTANCE.invalidateRoot();
        finish();
    }
}
