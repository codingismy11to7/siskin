package com.cappielloantonio.tempo.interfaces;

public interface CredentialStateCallback {
    /**
     * @param credentialsRejected true when the server answered and rejected the
     *                            stored credentials, false for every other outcome
     *                            (unreachable host, timeout, server too old). Only
     *                            a true here justifies offering a sign-in button.
     */
    void onResult(boolean credentialsRejected);
}
