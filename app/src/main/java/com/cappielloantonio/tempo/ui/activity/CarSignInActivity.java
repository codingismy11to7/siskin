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

        // Routed through the back dispatcher rather than calling finish()
        // directly: this button *is* the back button, not something that
        // imitates it, so any back handling added later (e.g. popping one
        // step of the sign-in flow instead of leaving outright) applies to
        // both the button and hardware/gesture back automatically instead of
        // the two silently drifting apart. Wired unconditionally -- outside
        // the savedInstanceState guard below -- because setContentView() just
        // above reinflates this view on every onCreate, including a
        // recreation, and the button needs its listener every time.
        findViewById(R.id.back_button).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        // Load-bearing for config changes: a day/night uiMode flip re-creates
        // this Activity with savedInstanceState != null, and the guard is what
        // stops that recreation from re-running open() over a ViewModel that
        // survived the recreation with real progress in it (e.g. ChoosingServer
        // would otherwise be clobbered back to Working or Disconnected).
        //
        // It is not, however, the right guard for process death: when the
        // process itself was killed, savedInstanceState is also non-null on the
        // way back, but the ViewModel is gone -- a fresh one is constructed and
        // there is nothing left to protect. This branch still skips open() in
        // that case, so a restore that arrived via EXTRA_FORCE_SIGN_IN silently
        // loses the force and the fresh ViewModel's default open(false) runs
        // instead, landing on Disconnected (Connect) rather than driving
        // straight into Working. Not a correctness bug -- the user just taps
        // Connect once more -- so left as a comment rather than a restructure.
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
        // invalidateTree(), not invalidateRoot(): the root's own children are
        // the same four tabs whether signed in or out (see
        // MediaBrowserTree.buildTree's KDoc), so invalidateRoot() alone sees
        // byte-identical children here and gives the car nothing to redraw.
        // The signed-out info row lives one level down, inside each tab, and
        // invalidateTree() is what reaches it -- see its KDoc on
        // BrowseTreeInvalidator.
        BrowseTreeInvalidator.INSTANCE.invalidateTree();
        finish();
    }

    @Override
    public void onSignedOut() {
        // The order these two calls are written in does not decide the order
        // their side effects run in, and safety here does not depend on it.
        // stopPlayback() posts its Runnable to the main looper
        // (Handler.post), so it only queues behind whatever is currently
        // running. invalidateTree() runs invalidateRoot()'s
        // buildTree()/notifyChildrenChanged(ROOT_ID) synchronously on the
        // calling thread (see BrowseTreeInvalidator's KDoc on
        // directExecutor) and only then posts one invalidateNode() call per
        // tab. Since this callback itself runs on the main thread -- it is
        // dispatched from a button tap, see PlexSignInFragment's Connected
        // case -- the root's notifyChildrenChanged fires immediately, ahead
        // of stopPlayback()'s already-queued Runnable: the reverse of what
        // the two lines below suggest. The four tab invalidations are the
        // opposite case: they are posted after stopPlayback() was already
        // queued, so stopPlayback()'s Runnable is guaranteed to run before
        // any of them even fire -- matching what the two lines below
        // suggest.
        //
        // Either way it is safe: everything lands on the same looper, and
        // notifyChildrenChanged only triggers the car to re-request a node
        // over IPC -- a round trip slow enough that stopPlayback() has
        // already run, for the root and for every tab, before any re-fetch
        // could reach onGetChildren while credentials are dead.
        BrowseTreeInvalidator.INSTANCE.stopPlayback();
        BrowseTreeInvalidator.INSTANCE.invalidateTree();
    }
}
