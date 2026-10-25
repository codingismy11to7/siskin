package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.car.VehicleInfoSource
import com.cappielloantonio.tempo.car.VehicleInputs
import com.cappielloantonio.tempo.car.withVehicleInputs
import com.cappielloantonio.tempo.util.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because PlexApi reads App.getInstance().preferences. */
@RunWith(RobolectricTestRunner::class)
class PlexApiVehicleTest {
    private val inputs = VehicleInputs("Cadillac", "L233", 2026, "GM", "Lyriq-HU")

    @After
    fun resetTier() {
        Preferences.setVehicleIdentityTier(VehicleInfoSource.VEHICLE)
    }

    @Test
    fun sendsTheVehicleByDefault() {
        Preferences.setVehicleIdentityTier(VehicleInfoSource.VEHICLE)
        withVehicleInputs(inputs) {
            assertEquals("L233", PlexApi().vehicle.model)
        }
    }

    @Test
    fun sendsThePickedTier() {
        Preferences.setVehicleIdentityTier(VehicleInfoSource.BUILD)
        withVehicleInputs(inputs) {
            assertEquals(VehicleInfoSource.BUILD, PlexApi().vehicle.source)
            assertEquals("Lyriq-HU", PlexApi().vehicle.model)
        }
    }
}
