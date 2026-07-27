package com.cappielloantonio.tempo.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CredentialGateTest {

    private static final String SERVER = "https://music.example.com";

    @Test
    public void notSignedInWithoutAServer() {
        // AAOS exposes the browse tree straight after install: nothing is configured.
        assertFalse(CredentialGate.isSignedIn(null, "hunter2", null, null));
    }

    @Test
    public void blankServerIsTreatedAsAbsent() {
        assertFalse(CredentialGate.isSignedIn("   ", "hunter2", null, null));
    }

    @Test
    public void notSignedInWithServerButNoCredentials() {
        assertFalse(CredentialGate.isSignedIn(SERVER, null, null, null));
    }

    @Test
    public void signedInWithPassword() {
        assertTrue(CredentialGate.isSignedIn(SERVER, "hunter2", null, null));
    }

    @Test
    public void signedInWithTokenAndSalt() {
        assertTrue(CredentialGate.isSignedIn(SERVER, null, "tok", "salt"));
    }

    @Test
    public void tokenWithoutSaltIsNotEnough() {
        assertFalse(CredentialGate.isSignedIn(SERVER, null, "tok", null));
    }

    @Test
    public void saltWithoutTokenIsNotEnough() {
        assertFalse(CredentialGate.isSignedIn(SERVER, null, null, "salt"));
    }

    @Test
    public void wrongUsernameOrPasswordIsAnAuthFailure() {
        assertTrue(CredentialGate.isAuthFailure(40));
    }

    @Test
    public void tokenAuthNotSupportedIsAnAuthFailure() {
        assertTrue(CredentialGate.isAuthFailure(41));
    }

    @Test
    public void notAuthorizedIsAnAuthFailure() {
        assertTrue(CredentialGate.isAuthFailure(50));
    }

    @Test
    public void serverMustUpgradeIsNotAnAuthFailure() {
        // Code 30: the server is too old. Signing in again cannot fix it, so the
        // car must not be offered a Sign in button.
        assertFalse(CredentialGate.isAuthFailure(30));
    }

    @Test
    public void missingCodeIsNotAnAuthFailure() {
        assertFalse(CredentialGate.isAuthFailure(null));
    }
}
