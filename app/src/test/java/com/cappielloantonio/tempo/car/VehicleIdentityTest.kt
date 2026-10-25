package com.cappielloantonio.tempo.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tier ladder from the 2026-08-27 vehicle device name design, and where a
 * picked tier starts it (2026-10-25). Pure, so this runs as a plain JUnit
 * test -- no Robolectric, no framework class, and therefore nothing that
 * `unitTests.returnDefaultValues = true` can stub into a test that passes
 * while asserting nothing.
 */
class VehicleIdentityTest {
    @Test
    fun prefersWhatTheVehicleReports() {
        val identity = VehicleInputs("Cadillac", "LYRIQ", 2024, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.VEHICLE, identity.source)
        assertEquals("Cadillac", identity.make)
        assertEquals("LYRIQ", identity.model)
        assertEquals(2024, identity.year)
    }

    @Test
    fun staysOnTheVehicleTierWhenOnlyTheYearIsMissing() {
        val identity = VehicleInputs("Cadillac", "LYRIQ", null, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.VEHICLE, identity.source)
        assertNull(identity.year)
    }

    @Test
    fun ignoresAYearOfZero() {
        // VHAL answers 0 for a property it carries but has no value for, and a
        // car named "0 Cadillac LYRIQ" is worse than one with no year at all.
        val identity = VehicleInputs("Cadillac", "LYRIQ", 0, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.VEHICLE, identity.source)
        assertNull(identity.year)
    }

    @Test
    fun fallsBackToBuildWhenTheVehicleSaysNothing() {
        val identity = VehicleInputs(null, null, null, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.BUILD, identity.source)
        assertEquals("Google", identity.make)
        assertEquals("board-x86", identity.model)
        assertNull(identity.year)
    }

    @Test
    fun fallsBackToBuildWhenTheVehicleReportsOnlyTheMake() {
        // A tier is chosen as a unit, so a row never pairs a real make with a
        // placeholder model.
        val identity = VehicleInputs("Cadillac", null, 2024, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.BUILD, identity.source)
        assertEquals("Google", identity.make)
        assertEquals("board-x86", identity.model)
    }

    @Test
    fun dropsTheVehicleYearWhenTheTierDoesNotSurvive() {
        val identity = VehicleInputs(null, null, 2024, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.BUILD, identity.source)
        assertNull(identity.year)
    }

    @Test
    fun treatsBlankAndWhitespaceAsAbsent() {
        val identity = VehicleInputs("  ", "", 2024, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.BUILD, identity.source)
    }

    @Test
    fun trimsWhatItKeeps() {
        val identity = VehicleInputs(" Cadillac ", " LYRIQ ", null, null, null).resolve()

        assertEquals("Cadillac", identity.make)
        assertEquals("LYRIQ", identity.model)
    }

    @Test
    fun keepsANameThatIsNotAscii() {
        // Škoda and Citroën ship AAOS. The name goes to Plex as UTF-8 rather
        // than transliterated, so nothing here may touch it.
        val identity = VehicleInputs("Škoda", "Citroën", 2024, null, null).resolve()

        assertEquals("Škoda", identity.make)
        assertEquals("Citroën", identity.model)
    }

    @Test
    fun keepsANameOutsideLatinToo() {
        val identity = VehicleInputs("领克", "09", null, null, null).resolve()

        assertEquals("领克", identity.make)
        assertEquals("09", identity.model)
    }

    @Test
    fun stripsTheControlCharactersThatWouldInjectAHeader() {
        val identity = VehicleInputs("Cadillac\r\nX-Plex-Token: stolen", "LYRIQ", null, null, null).resolve()

        assertEquals(VehicleInfoSource.VEHICLE, identity.source)
        assertEquals("CadillacX-Plex-Token: stolen", identity.make)
        assertEquals("LYRIQ", identity.model)
    }

    @Test
    fun fallsThroughWhenControlCharactersAreAllThereWas() {
        val identity = VehicleInputs("\n\t", "LYRIQ", null, "Google", "board-x86").resolve()

        assertEquals(VehicleInfoSource.BUILD, identity.source)
    }

    @Test
    fun reportsUnknownWhenNothingAnswers() {
        val identity = VehicleInputs(null, null, null, null, "  ").resolve()

        assertEquals(VehicleInfoSource.UNKNOWN, identity.source)
        assertNull(identity.make)
        assertNull(identity.model)
        assertNull(identity.year)
    }

    @Test
    fun unknownIsTheSameShapeResolveProduces() {
        assertEquals(VehicleInputs(null, null, null, null, null).resolve(), VehicleIdentity.UNKNOWN)
    }

    private val everyTier = VehicleInputs("Cadillac", "L233", 2026, "GM", "Lyriq-HU")

    @Test
    fun startingAtBuildSkipsTheVehicle() {
        val identity = everyTier.resolve(VehicleInfoSource.BUILD)

        assertEquals(VehicleInfoSource.BUILD, identity.source)
        assertEquals("GM", identity.make)
        assertEquals("Lyriq-HU", identity.model)
        assertNull(identity.year)
    }

    @Test
    fun startingAtConstantsSendsNoCar() {
        assertEquals(VehicleIdentity.UNKNOWN, everyTier.resolve(VehicleInfoSource.UNKNOWN))
    }

    @Test
    fun startingAtVehicleIsTheDefault() {
        assertEquals(everyTier.resolve(), everyTier.resolve(VehicleInfoSource.VEHICLE))
        assertEquals(VehicleInfoSource.VEHICLE, everyTier.resolve().source)
    }

    @Test
    fun aPickedTierWithNothingFallsDownTheLadder() {
        val noBuild = VehicleInputs("Cadillac", "L233", 2026, " ", null)

        assertEquals(VehicleIdentity.UNKNOWN, noBuild.resolve(VehicleInfoSource.BUILD))
    }

    @Test
    fun eachTierAnswersAloneOrNotAtAll() {
        val noVehicle = VehicleInputs(null, "L233", 2026, "GM", "Lyriq-HU")

        assertNull(noVehicle.tier(VehicleInfoSource.VEHICLE))
        assertEquals(VehicleInfoSource.BUILD, noVehicle.tier(VehicleInfoSource.BUILD)?.source)
        assertEquals(VehicleIdentity.UNKNOWN, noVehicle.tier(VehicleInfoSource.UNKNOWN))
    }

    @Test
    fun nothingReadMeansOnlyTheConstantsAnswer() {
        assertNull(VehicleInputs.NONE.tier(VehicleInfoSource.VEHICLE))
        assertNull(VehicleInputs.NONE.tier(VehicleInfoSource.BUILD))
        assertEquals(VehicleIdentity.UNKNOWN, VehicleInputs.NONE.resolve())
    }
}
