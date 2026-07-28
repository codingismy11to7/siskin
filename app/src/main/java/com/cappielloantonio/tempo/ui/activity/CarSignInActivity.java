package com.cappielloantonio.tempo.ui.activity;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.helper.ThemeHelper;
import com.cappielloantonio.tempo.interfaces.LoginHost;
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator;
import com.cappielloantonio.tempo.ui.fragment.LoginFragment;

/**
 * Sign-in screen for Android Automotive OS head units.
 *
 * Launched by the resolution PendingIntent attached to the browse error when no
 * usable credentials exist. Hosts the app's existing LoginFragment rather than
 * duplicating the login UI.
 *
 * Deliberately NOT marked distractionOptimized in the manifest: AAOS restricts
 * keyboard input while driving, so a sign-in form cannot be distraction-optimized
 * in a compliant way. Without that metadata the platform blocks this screen while
 * the car is moving, which is exactly the behavior we want.
 */
@UnstableApi
public class CarSignInActivity extends AppCompatActivity implements LoginHost {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeHelper.applyActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_sign_in);
        setTitle(R.string.car_sign_in_title);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.car_sign_in_container, new LoginFragment())
                    .commit();
        }
    }

    @Override
    public void onLoginSuccess() {
        BrowseTreeInvalidator.INSTANCE.invalidateRoot();
        finish();
    }
}
