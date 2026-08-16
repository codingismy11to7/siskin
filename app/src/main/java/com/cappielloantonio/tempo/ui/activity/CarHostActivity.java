package com.cappielloantonio.tempo.ui.activity;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.helper.ThemeHelper;
import com.cappielloantonio.tempo.interfaces.LoginHost;
import com.cappielloantonio.tempo.plex.auth.PlexSignInState;
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator;
import com.cappielloantonio.tempo.ui.fragment.CarSettingsFragment;
import com.cappielloantonio.tempo.ui.fragment.PlexSignInFragment;
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel;

/**
 * Host for every screen the car shows outside the browse tree, and the app's
 * only activity.
 *
 * Named for hosting rather than for any one of the screens it hosts, which is
 * the whole point: it is the APPLICATION_PREFERENCES target the car's gear
 * resolves, the target of CarSignInResolution's sign-in PendingIntent, and the
 * host for the tab-order screen. Deliberately not CarSettingsActivity either --
 * that would be the same mistake one screen later, since the PendingIntent path
 * launches it specifically to sign in. EXTRA_FORCE_SIGN_IN is how onCreate tells
 * those two entry points apart.
 *
 * Deliberately NOT marked distractionOptimized in the manifest: the pickers are
 * tap-only, but sign-in is still not something to do while driving. Without that
 * metadata the platform blocks this screen while the car is moving, which is
 * exactly the behavior we want -- and it is what keeps a drag gesture off a
 * moving vehicle on the tab-order screen too, which is why any restructuring
 * here has to preserve the absence and not just the class name.
 */
@UnstableApi
public class CarHostActivity extends AppCompatActivity implements LoginHost {

    /**
     * Set by CarSignInResolution's PendingIntent. Absent when the car's settings
     * gear starts this activity, which is how the two entry points are told
     * apart.
     */
    public static final String EXTRA_FORCE_SIGN_IN =
            "us.codingismy11to7.siskin.extra.FORCE_SIGN_IN";

    /**
     * Test seam: when non-null, builds the PlexSignInViewModel instead of the
     * default factory.
     *
     * Static because onCreate populates the ViewModelStore before any test can
     * reach the instance, so there is no later moment at which a stub could be
     * installed. Nothing in production ever sets it.
     *
     * **A test that sets this MUST null it again in @After.** Robolectric keeps
     * statics across test methods and across classes, so a factory left behind
     * hands the next suite a stubbed AuthClient and fails it somewhere
     * unrelated.
     */
    @VisibleForTesting
    public static ViewModelProvider.Factory viewModelFactoryForTest = null;

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
        setContentView(R.layout.activity_car_host);

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

        PlexSignInViewModel viewModel = viewModelFactoryForTest == null
                ? new ViewModelProvider(this).get(PlexSignInViewModel.class)
                : new ViewModelProvider(this, viewModelFactoryForTest)
                        .get(PlexSignInViewModel.class);

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
            viewModel.open(forceSignIn);
        }

        // Observed after open(), not before: observe() delivers the current
        // value immediately, so the other order would route on the ViewModel's
        // initial Disconnected and commit a sign-in screen that open(false) is
        // about to replace with settings -- one wasted transaction, visible as
        // a flash of the Connect screen on every trip through the gear.
        viewModel.getState().observe(this, this::route);
    }

    /**
     * Which screen the state calls for.
     *
     * The routing lives here rather than in either fragment because neither
     * fragment owns the other: Connected is settings and everything else is the
     * PIN flow, and a fragment that pushed its own successor would be
     * responsible for a screen it does not draw. That was the shape being
     * removed -- one fragment rendering two screens -- not a shape to preserve
     * one level up.
     */
    private void route(PlexSignInState state) {
        FragmentManager fragments = getSupportFragmentManager();

        // A pushed screen owns the container, and BrowseTabOrderFragment is the
        // one that pushes. Its state is still Connected the whole time it is up,
        // so nothing re-routes while it is showing -- except on a uiMode flip,
        // where onCreate runs again, this observer fires again with that same
        // Connected, and without this guard the restored tab-order screen would
        // be replaced by settings underneath the user.
        if (fragments.getBackStackEntryCount() > 0) return;

        boolean wantSettings = state instanceof PlexSignInState.Connected;
        Fragment current = fragments.findFragmentById(R.id.car_host_container);

        // Compared by which screen is showing rather than by identity, so a
        // recreation that restored the right fragment commits nothing: the
        // FragmentManager has already put it back by the time this first runs.
        if (current != null && (current instanceof CarSettingsFragment) == wantSettings) return;

        fragments.beginTransaction()
                .replace(
                        R.id.car_host_container,
                        wantSettings ? new CarSettingsFragment() : new PlexSignInFragment())
                .commit();
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
        // dispatched from a button tap, see CarSettingsFragment's Sign out
        // row -- the root's notifyChildrenChanged fires immediately, ahead
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
