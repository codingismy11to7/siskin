package com.cappielloantonio.tempo.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CredentialGateTest {

    private static final String TOKEN = "plex-account-token";
    private static final String URI = "https://192.168.1.10:32400";
    private static final String SECTION = "3";

    @Test
    public void signedInWhenAllThreeArePresent() {
        assertTrue(CredentialGate.isSignedIn(TOKEN, URI, SECTION));
    }

    @Test
    public void notSignedInWithoutAToken() {
        // AAOS exposes the browse tree straight after install: nothing is configured.
        assertFalse(CredentialGate.isSignedIn(null, URI, SECTION));
    }

    @Test
    public void notSignedInWithoutAServerUri() {
        // The PIN was approved but discovery never finished.
        assertFalse(CredentialGate.isSignedIn(TOKEN, null, SECTION));
    }

    @Test
    public void notSignedInWithoutAMusicSection() {
        // A server was chosen but the library picker was never answered, so no
        // browse call could name a section to read.
        assertFalse(CredentialGate.isSignedIn(TOKEN, URI, null));
    }

    @Test
    public void blankValuesAreTreatedAsAbsent() {
        assertFalse(CredentialGate.isSignedIn("   ", URI, SECTION));
        assertFalse(CredentialGate.isSignedIn(TOKEN, "   ", SECTION));
        assertFalse(CredentialGate.isSignedIn(TOKEN, URI, "   "));
    }
}
