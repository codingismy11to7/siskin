package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Plain JUnit: the encoding is pure string work and touches no framework class,
 * so nothing here can be hollowed out by unitTests.returnDefaultValues.
 */
class DecadeKeyTest {

    @Test
    fun aKeyRoundTripsToItsDecade() {
        assertEquals("1980", DecadeKey.decadeIn(DecadeKey.of("abc123-4", "1980")))
    }

    @Test
    fun twoLibrariesGiveTheSameDecadeTwoKeys() {
        // The property the crash was the absence of: two servers' 1980s rows
        // must not be one row to the car's DiffUtil.
        assertNotEquals(
            DecadeKey.of("serverA-4", "1980"),
            DecadeKey.of("serverB-4", "1980")
        )
    }

    @Test
    fun aScopeWithNoMachineIdentifierStillSplitsAtTheRightPlace() {
        // The sentinel a null machineIdentifier maps to is hyphenated, and the
        // scope joins its two fields with a hyphen too -- so the separator has
        // to be something neither can contain. Pipe, per PICK_LIBRARY_ID's
        // convention: a hyphen here would leave "no-machine-id-1" ambiguous.
        assertEquals("1980", DecadeKey.decadeIn(DecadeKey.of("no-machine-id-1", "1980")))
    }

    @Test
    fun aKeyWithNoSeparatorIsItsOwnDecade() {
        // The shape this app minted before the library moved into the id. An id
        // the car persisted across that upgrade has to keep querying its own
        // decade rather than the whole string, which Plex would answer with 200
        // and an empty container.
        assertEquals("1980", DecadeKey.decadeIn("1980"))
    }
}
