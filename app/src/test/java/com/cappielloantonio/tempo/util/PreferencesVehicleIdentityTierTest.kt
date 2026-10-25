package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.car.VehicleInfoSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tier the identity ladder starts from; the vehicle unless picked otherwise. */
@RunWith(RobolectricTestRunner::class)
class PreferencesVehicleIdentityTierTest {
    private val prefs get() = App.getInstance().preferences

    @Before
    @After
    fun clearTier() {
        prefs.edit().remove("vehicle_identity_tier").commit()
    }

    @Test
    fun `an untouched install starts from the vehicle`() {
        assertEquals(VehicleInfoSource.VEHICLE, Preferences.getVehicleIdentityTier())
    }

    @Test
    fun `every tier round-trips`() {
        for (tier in VehicleInfoSource.entries) {
            Preferences.setVehicleIdentityTier(tier)
            assertEquals(tier, Preferences.getVehicleIdentityTier())
        }
    }

    @Test
    fun `the stored words are the ones the design names`() {
        Preferences.setVehicleIdentityTier(VehicleInfoSource.UNKNOWN)
        assertEquals("constants", prefs.getString("vehicle_identity_tier", null))

        Preferences.setVehicleIdentityTier(VehicleInfoSource.BUILD)
        assertEquals("build", prefs.getString("vehicle_identity_tier", null))
    }

    @Test
    fun `an unrecognised value reads as the vehicle`() {
        prefs.edit().putString("vehicle_identity_tier", "satellite").commit()
        assertEquals(VehicleInfoSource.VEHICLE, Preferences.getVehicleIdentityTier())
    }
}
