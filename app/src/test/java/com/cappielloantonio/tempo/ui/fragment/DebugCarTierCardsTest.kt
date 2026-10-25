package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.car.VehicleInfoSource
import com.cappielloantonio.tempo.car.VehicleInputs
import com.cappielloantonio.tempo.ui.fragment.DebugCarFragment.Companion.TierLabels
import com.cappielloantonio.tempo.ui.fragment.DebugCarFragment.Companion.buildTierCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Car tab, framework-free because `returnDefaultValues =
 * true` stubs android.jar and a test touching only framework classes could pass
 * while asserting nothing.
 */
class DebugCarTierCardsTest {
    // Arbitrary and distinct from the string resources: these tests are about
    // which label lands where, not the copy.
    private val labels =
        TierLabels(
            vehicle = "VEH",
            build = "BLD",
            constants = "CON",
            constantsDetail = "CON_DETAIL",
            noName = "NO_NAME",
            nothingReported = "NOTHING",
            nothingHere = "%1\$s EMPTY",
            fallbackFrom = "%1\$s FROM %2\$s",
        )

    private val everyTier = VehicleInputs("Cadillac", "L233", 2026, "GM", "Lyriq-HU")

    @Test
    fun oneCardPerTierInLadderOrder() {
        val cards = buildTierCards(everyTier, VehicleInfoSource.VEHICLE, labels)

        assertEquals(listOf(VehicleInfoSource.VEHICLE, VehicleInfoSource.BUILD, VehicleInfoSource.UNKNOWN), cards.map { it.tier })
        assertEquals(listOf("VEH", "BLD", "CON"), cards.map { it.title })
    }

    @Test
    fun eachCardShowsWhatItsTierWouldSend() {
        val (vehicle, build, constants) = buildTierCards(everyTier, VehicleInfoSource.VEHICLE, labels)

        assertEquals("2026 Cadillac L233\nCadillac / L233", vehicle.body)
        assertEquals("GM Lyriq-HU\nGM / Lyriq-HU", build.body)
        assertEquals("NO_NAME\nAutomotive / automotive", constants.body)
    }

    @Test
    fun theRawLineQuotesWhatWasReadAndMarksWhatWasNot() {
        val inputs = VehicleInputs(" Cadillac", "L233", null, "GM", "Lyriq-HU")
        val (vehicle, build, constants) = buildTierCards(inputs, VehicleInfoSource.VEHICLE, labels)

        assertEquals("INFO_MAKE=\" Cadillac\"  INFO_MODEL=\"L233\"  INFO_MODEL_YEAR=∅", vehicle.raw)
        assertEquals("MANUFACTURER=\"GM\"  MODEL=\"Lyriq-HU\"", build.raw)
        assertEquals("CON_DETAIL", constants.raw)
    }

    @Test
    fun onlyThePickedTierIsReportedWhenItAnswers() {
        val cards = buildTierCards(everyTier, VehicleInfoSource.BUILD, labels)

        assertEquals(listOf(false, true, false), cards.map { it.reported })
    }

    @Test
    fun anEmptyPickedTierSaysSoAndTheFallbackIsReported() {
        val noVehicle = VehicleInputs(null, null, null, "GM", "Lyriq-HU")
        val (vehicle, build, _) = buildTierCards(noVehicle, VehicleInfoSource.VEHICLE, labels)

        assertFalse(vehicle.available)
        assertFalse(vehicle.reported)
        assertEquals("VEH EMPTY", vehicle.title)
        assertEquals("NOTHING", vehicle.body)
        assertTrue(build.reported)
        assertEquals("BLD FROM VEH", build.title)
    }

    @Test
    fun theConstantsAreAlwaysAvailable() {
        val cards = buildTierCards(VehicleInputs.NONE, VehicleInfoSource.VEHICLE, labels)

        assertEquals(listOf(false, false, true), cards.map { it.available })
        assertTrue(cards.last().reported)
    }
}
