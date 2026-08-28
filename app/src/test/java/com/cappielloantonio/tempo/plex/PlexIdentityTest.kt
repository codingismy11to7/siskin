package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.car.VehicleIdentity
import com.cappielloantonio.tempo.car.VehicleInfoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexIdentityTest {
    private val lyriq = VehicleIdentity("Cadillac", "LYRIQ", 2024, VehicleInfoSource.VEHICLE)

    private fun headers(
        clientIdentifier: String = "cid-1",
        appVersion: String = "1.2.3",
        token: String? = null,
        language: String = "",
        vehicle: VehicleIdentity = VehicleIdentity.UNKNOWN,
    ) = PlexIdentity.headers(clientIdentifier, appVersion, token, language, vehicle)

    @Test
    fun includesEveryHeaderPlexRequires() {
        val headers = headers()
        assertEquals("cid-1", headers["X-Plex-Client-Identifier"])
        assertEquals("Siskin", headers["X-Plex-Product"])
        assertEquals("1.2.3", headers["X-Plex-Version"])
        assertEquals("Android", headers["X-Plex-Platform"])
        assertTrue(headers.containsKey("X-Plex-Device"))
        assertTrue(headers.containsKey("X-Plex-Model"))
    }

    @Test
    fun omitsTheTokenHeaderWhenSignedOut() {
        // Sending an empty X-Plex-Token is not the same as sending none; Plex
        // treats the empty value as a failed auth rather than an anonymous call.
        assertFalse(headers(token = null).containsKey("X-Plex-Token"))
        assertFalse(headers(token = "  ").containsKey("X-Plex-Token"))
    }

    @Test
    fun includesTheTokenHeaderWhenSignedIn() {
        assertEquals("tok123", headers(token = "tok123")["X-Plex-Token"])
    }

    @Test
    fun declaresAutomotiveAsTheDeviceWhenTheCarSaysNothing() {
        // Plex surfaces this string in the account's device list; "Android" alone
        // would be indistinguishable from the phone app.
        val headers = headers()
        assertEquals("Automotive", headers["X-Plex-Device"])
        assertEquals("automotive", headers["X-Plex-Model"])
    }

    @Test
    fun sendsNoDeviceNameWhenTheCarSaysNothing() {
        // "Automotive" as a *name* is the undifferentiated row this exists to
        // fix, so the header is omitted rather than filled with a placeholder.
        assertFalse(headers().containsKey("X-Plex-Device-Name"))
    }

    @Test
    fun namesTheCarWhenTheVehicleAnswers() {
        val headers = headers(vehicle = lyriq)

        assertEquals("2024 Cadillac LYRIQ", headers["X-Plex-Device-Name"])
        assertEquals("Cadillac", headers["X-Plex-Device"])
        assertEquals("LYRIQ", headers["X-Plex-Model"])
    }

    @Test
    fun keepsTheYearOutOfDeviceAndModel() {
        val headers = headers(vehicle = lyriq)

        assertFalse(headers["X-Plex-Device"]!!.contains("2024"))
        assertFalse(headers["X-Plex-Model"]!!.contains("2024"))
    }

    @Test
    fun dropsTheYearFromTheNameWhenTheVehicleDoesNotReportOne() {
        val headers = headers(vehicle = lyriq.copy(year = null))

        assertEquals("Cadillac LYRIQ", headers["X-Plex-Device-Name"])
    }

    @Test
    fun namesTheCarFromBuildPropertiesToo() {
        val fromBuild = VehicleIdentity("Volvo", "XC40", null, VehicleInfoSource.BUILD)

        assertEquals("Volvo XC40", headers(vehicle = fromBuild)["X-Plex-Device-Name"])
    }

    @Test
    fun sendsTheLanguageSoServerBuiltTitlesArriveTranslated() {
        assertEquals("de", headers(token = "token", language = "de")["X-Plex-Language"])
    }

    @Test
    fun omitsTheLanguageHeaderWhenThereIsNoLanguage() {
        assertFalse(headers(token = "token").containsKey("X-Plex-Language"))
    }
}
