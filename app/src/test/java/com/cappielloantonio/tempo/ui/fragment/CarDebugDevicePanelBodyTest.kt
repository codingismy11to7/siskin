package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.car.VehicleIdentity
import com.cappielloantonio.tempo.car.VehicleInfoSource
import com.cappielloantonio.tempo.plex.PlexIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Debug screen's Device section, kept framework-free for
 * the same reason [CarDebugAddressPanelBodyTest] is: `returnDefaultValues =
 * true` stubs android.jar, so a test that only touched framework classes could
 * pass while asserting nothing.
 */
class CarDebugDevicePanelBodyTest {
    // Arbitrary and distinct from the real string resources on purpose -- these
    // tests are about which label lands where, not about the app's copy.
    private val noName = "NO_NAME"
    private val from = "FROM %1\$s"

    private fun body(
        vehicle: VehicleIdentity,
        sourceLabel: String,
    ) = CarDebugFragment.buildDevicePanelBody(
        headers = PlexIdentity.deviceHeaders(vehicle),
        noNameLabel = noName,
        fromLabel = from,
        sourceLabel = sourceLabel,
    )

    @Test
    fun reportsTheNameTheMakeTheModelAndTheTier() {
        val lyriq = VehicleIdentity("Cadillac", "LYRIQ", 2024, VehicleInfoSource.VEHICLE)

        assertEquals(
            "2024 Cadillac LYRIQ\nCadillac / LYRIQ\nFROM vehicle",
            body(lyriq, "vehicle"),
        )
    }

    @Test
    fun saysSoWhenNoDeviceNameIsSent() {
        val body = body(VehicleIdentity.UNKNOWN, "unknown")

        assertTrue(body.startsWith(noName))
        assertTrue(body.contains("Automotive / automotive"))
        assertTrue(body.endsWith("FROM unknown"))
    }

    @Test
    fun reportsTheBuildTierAsSuch() {
        val fromBuild = VehicleIdentity("Google", "board-x86", null, VehicleInfoSource.BUILD)

        assertEquals(
            "Google board-x86\nGoogle / board-x86\nFROM build properties",
            body(fromBuild, "build properties"),
        )
    }
}
