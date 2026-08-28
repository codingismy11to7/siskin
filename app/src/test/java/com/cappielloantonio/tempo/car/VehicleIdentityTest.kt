package com.cappielloantonio.tempo.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tier ladder from the 2026-08-27 vehicle device name design. Pure, so
 * this runs as a plain JUnit test -- no Robolectric, no framework class, and
 * therefore nothing that `unitTests.returnDefaultValues = true` can stub into
 * a test that passes while asserting nothing.
 */
class VehicleIdentityTest {
    @Test
    fun prefersWhatTheVehicleReports() {
        val identity = VehicleIdentity.resolve("Cadillac", "LYRIQ", 2024, "Google", "board-x86")

        assertEquals(VehicleInfoSource.VEHICLE, identity.source)
        assertEquals("Cadillac", identity.make)
        assertEquals("LYRIQ", identity.model)
        assertEquals(2024, identity.year)
    }

    @Test
    fun staysOnTheVehicleTierWhenOnlyTheYearIsMissing() {
        val identity = VehicleIdentity.resolve("Cadillac", "LYRIQ", null, "Google", "board-x86")

        assertEquals(VehicleInfoSource.VEHICLE, identity.source)
        assertNull(identity.year)
    }

    @Test
    fun ignoresAYearOfZero() {
        // VHAL answers 0 for a property it carries but has no value for, and a
        // car named "0 Cadillac LYRIQ" is worse than one with no year at all.
        val identity = VehicleIdentity.resolve("Cadillac", "LYRIQ", 0, "Google", "board-x86")

        assertEquals(VehicleInfoSource.VEHICLE, identity.source)
        assertNull(identity.year)
    }

    @Test
    fun fallsBackToBuildWhenTheVehicleSaysNothing() {
        val identity = VehicleIdentity.resolve(null, null, null, "Google", "board-x86")

        assertEquals(VehicleInfoSource.BUILD, identity.source)
        assertEquals("Google", identity.make)
        assertEquals("board-x86", identity.model)
        assertNull(identity.year)
    }

    @Test
    fun fallsBackToBuildWhenTheVehicleReportsOnlyTheMake() {
        // A tier is chosen as a unit, so a row never pairs a real make with a
        // placeholder model.
        val identity = VehicleIdentity.resolve("Cadillac", null, 2024, "Google", "board-x86")

        assertEquals(VehicleInfoSource.BUILD, identity.source)
        assertEquals("Google", identity.make)
        assertEquals("board-x86", identity.model)
    }

    @Test
    fun dropsTheVehicleYearWhenTheTierDoesNotSurvive() {
        val identity = VehicleIdentity.resolve(null, null, 2024, "Google", "board-x86")

        assertEquals(VehicleInfoSource.BUILD, identity.source)
        assertNull(identity.year)
    }

    @Test
    fun treatsBlankAndWhitespaceAsAbsent() {
        val identity = VehicleIdentity.resolve("  ", "", 2024, "Google", "board-x86")

        assertEquals(VehicleInfoSource.BUILD, identity.source)
    }

    @Test
    fun trimsWhatItKeeps() {
        val identity = VehicleIdentity.resolve(" Cadillac ", " LYRIQ ", null, null, null)

        assertEquals("Cadillac", identity.make)
        assertEquals("LYRIQ", identity.model)
    }

    @Test
    fun reportsUnknownWhenNothingAnswers() {
        val identity = VehicleIdentity.resolve(null, null, null, null, "  ")

        assertEquals(VehicleInfoSource.UNKNOWN, identity.source)
        assertNull(identity.make)
        assertNull(identity.model)
        assertNull(identity.year)
    }

    @Test
    fun unknownIsTheSameShapeResolveProduces() {
        assertEquals(VehicleIdentity.resolve(null, null, null, null, null), VehicleIdentity.UNKNOWN)
    }
}
