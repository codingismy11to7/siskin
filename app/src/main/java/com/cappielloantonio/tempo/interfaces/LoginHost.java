package com.cappielloantonio.tempo.interfaces;

/**
 * Implemented by any activity that can host LoginFragment.
 *
 * CarSignInActivity is the only implementation: on success it finishes and hands
 * the user back to the car's media browser.
 */
public interface LoginHost {
    void onLoginSuccess();
}
