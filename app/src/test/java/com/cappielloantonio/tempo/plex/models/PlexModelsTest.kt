package com.cappielloantonio.tempo.plex.models

import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gson is a plain-JVM library, so these are real tests despite
 * unitTests.returnDefaultValues = true -- that setting only stubs android.jar.
 */
class PlexModelsTest {

    private val gson = Gson()

    @Test
    fun parsesATrackListingFromTheMediaContainerEnvelope() {
        val json = """
        {"MediaContainer":{"size":1,"Metadata":[{
          "ratingKey":"42","key":"/library/metadata/42","type":"track",
          "title":"Bohemian Rhapsody","parentTitle":"A Night at the Opera",
          "grandparentTitle":"Queen","parentRatingKey":"7","grandparentRatingKey":"3",
          "thumb":"/library/metadata/42/thumb/1","duration":354000,"index":11,
          "Media":[{"id":9,"duration":354000,"bitrate":1411,"audioCodec":"flac",
            "container":"flac","Part":[{"id":5,"key":"/library/parts/5/file.flac",
            "duration":354000,"container":"flac"}]}]
        }]}}
        """.trimIndent()

        val container = gson.fromJson(json, PlexResponse::class.java).mediaContainer
        assertEquals(1, container?.size)

        val track = container?.metadata?.single()
        assertEquals("42", track?.ratingKey)
        assertEquals("track", track?.type)
        assertEquals("Bohemian Rhapsody", track?.title)
        assertEquals("A Night at the Opera", track?.parentTitle)
        assertEquals("Queen", track?.grandparentTitle)
        assertEquals(354000L, track?.duration)
        assertEquals(11, track?.index)

        val part = track?.media?.single()?.part?.single()
        assertEquals("/library/parts/5/file.flac", part?.key)
        assertEquals("flac", part?.container)
    }

    @Test
    fun parsesLibrarySectionsFromTheDirectoryArray() {
        val json = """
        {"MediaContainer":{"size":2,"Directory":[
          {"key":"1","type":"artist","title":"Music","uuid":"abc"},
          {"key":"2","type":"movie","title":"Films","uuid":"def"}
        ]}}
        """.trimIndent()

        val sections = gson.fromJson(json, PlexResponse::class.java).mediaContainer?.directory
        assertEquals(2, sections?.size)
        assertEquals("1", sections?.first()?.key)
        // Plex calls a music library section type "artist", not "music".
        assertEquals("artist", sections?.first()?.type)
        assertEquals("Music", sections?.first()?.title)
    }

    @Test
    fun missingArraysDeserializeToNullRatherThanThrowing() {
        val container = gson.fromJson("""{"MediaContainer":{"size":0}}""", PlexResponse::class.java)
            .mediaContainer
        assertEquals(0, container?.size)
        assertNull(container?.metadata)
        assertNull(container?.directory)
    }

    @Test
    fun parsesAPinAsBareJsonWithNoEnvelope() {
        // plex.tv v2 does not use MediaContainer.
        val json = """
        {"id":123,"code":"ABCD","clientIdentifier":"cid","expiresIn":900,
         "expiresAt":"2026-07-27T12:00:00Z","authToken":null,"trusted":false,
         "qr":"https://plex.tv/api/v2/pins/qr/ABCD"}
        """.trimIndent()

        val pin = gson.fromJson(json, Pin::class.java)
        assertEquals(123L, pin.id)
        assertEquals("ABCD", pin.code)
        assertEquals("2026-07-27T12:00:00Z", pin.expiresAt)
        assertEquals("https://plex.tv/api/v2/pins/qr/ABCD", pin.qr)
        assertNull(pin.authToken)
    }

    @Test
    fun parsesResourcesAsABareArrayWithConnections() {
        val json = """
        [{"name":"Basement","clientIdentifier":"srv1","provides":"server",
          "accessToken":"atok","connections":[
            {"protocol":"https","address":"192.168.1.5","port":32400,
             "uri":"https://192-168-1-5.abc.plex.direct:32400","local":true,"relay":false}]}]
        """.trimIndent()

        val resources = gson.fromJson(json, Array<Resource>::class.java)
        assertEquals(1, resources.size)
        assertEquals("Basement", resources[0].name)
        assertEquals("server", resources[0].provides)

        val connection = resources[0].connections?.single()
        assertEquals("https://192-168-1-5.abc.plex.direct:32400", connection?.uri)
        assertEquals(true, connection?.local)
    }
}
