package com.cappielloantonio.tempo.interfaces;

/**
 * Implemented by any activity that can host LoginFragment.
 *
 * MainActivity continues into the app; CarSignInActivity finishes and hands the
 * user back to the car's media browser.
 */
public interface LoginHost {
    void onLoginSuccess();
}
