package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shaped like a real capture from PMS; the names are made up. */
class FolderRowsTest {
    private val section = SectionKey("7")

    private fun parse(json: String): PlexResponse = Gson().fromJson(json, PlexResponse::class.java)

    private fun container(rows: String) = parse("""{"MediaContainer":{"size":1,"Metadata":[$rows]}}""")

    @Test
    fun `a folder row keeps only its parent token`() {
        val rows = FolderRows.classify(container("""{"key":"/library/sections/7/folder?parent=17890","title":"[1996] Example"}"""), section)
        assertEquals(listOf(FolderRows.Row.Folder(FolderToken("17890"), "[1996] Example")), rows)
    }

    @Test
    fun `a track row is kept whole`() {
        val rows =
            FolderRows.classify(
                container(
                    """{"ratingKey":"257479","key":"/library/metadata/257479","type":"track","title":"Believe","parentTitle":"Example"}""",
                ),
                section,
            )
        val track = rows.single() as FolderRows.Row.Track
        assertEquals("257479", track.metadata.ratingKey)
        assertEquals("Example", track.metadata.parentTitle)
    }

    @Test
    fun `folders and tracks in one level keep their order`() {
        val rows =
            FolderRows.classify(
                container(
                    """{"key":"/library/sections/7/folder?parent=1","title":"CD1"},""" +
                        """{"ratingKey":"9","key":"/library/metadata/9","type":"track","title":"Intro"}""",
                ),
                section,
            )
        assertTrue(rows[0] is FolderRows.Row.Folder)
        assertTrue(rows[1] is FolderRows.Row.Track)
    }

    @Test
    fun `keys that are not this section's folder endpoint are dropped`() {
        val rows =
            FolderRows.classify(
                container(
                    listOf(
                        """{"key":"/library/sections/8/folder?parent=1","title":"Other section"}""",
                        """{"key":"/library/sections/7/folder?parent=abc","title":"Not a number"}""",
                        """{"key":"https://evil.example/library/sections/7/folder?parent=1","title":"Off host"}""",
                        """{"key":"/library/sections/7/folder?parent=1&x=y","title":"Extra query"}""",
                        """{"key":"/library/sections/7/folder?parent=1","title":""}""",
                        """{"key":"/library/sections/7/folder?parent=","title":"Empty token"}""",
                        """{"ratingKey":"3","key":"/library/metadata/3","type":"album","title":"An album"}""",
                        """{"ratingKey":"","key":"/library/metadata/4","type":"track","title":"No rating key"}""",
                    ).joinToString(","),
                ),
                section,
            )
        assertEquals(emptyList<FolderRows.Row>(), rows)
    }

    @Test
    fun `an absent list is an empty level`() {
        assertEquals(emptyList<FolderRows.Row>(), FolderRows.classify(parse("""{"MediaContainer":{"size":0}}"""), section))
        assertEquals(emptyList<FolderRows.Row>(), FolderRows.classify(null, section))
    }
}
