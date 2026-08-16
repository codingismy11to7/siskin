package com.cappielloantonio.tempo.interfaces;

/**
 * Implemented by any activity that can host PlexSignInFragment.
 *
 * CarHostActivity is the only implementation: on success it finishes and hands
 * the user back to the car's media browser.
 */
public interface LoginHost {
    void onLoginSuccess();

    /**
     * Signing out invalidates more than this screen: playback is streaming on
     * credentials that no longer exist, and the browse tree is showing a library
     * the app can no longer reach.
     */
    void onSignedOut();
}
