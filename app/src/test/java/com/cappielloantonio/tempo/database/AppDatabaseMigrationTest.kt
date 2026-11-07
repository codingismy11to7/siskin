package com.cappielloantonio.tempo.database

import androidx.media3.common.util.UnstableApi
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room's first real migration. The database has no destructive fallback, so a
 * missing or broken one crashes the media service on open -- which presents as
 * a restart loop, nowhere near its cause. See AppDatabase's KDoc.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun aQueueSavedBeforeRadioSurvivesTheMigration() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO queue (id, track_order, last_play, playing_changed, part_key, hearted) " +
                    "VALUES ('42', 0, 0, 0, '/library/parts/9', 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true)

        db.query("SELECT id, part_key, play_queue_id, play_queue_item_id, station_artist_id FROM queue").use {
            assertTrue(it.moveToFirst())
            assertEquals("42", it.getString(0))
            assertEquals("/library/parts/9", it.getString(1))
            assertTrue(it.isNull(2))
            assertTrue(it.isNull(3))
            assertTrue(it.isNull(4))
        }
    }

    @Test
    fun aSavedArtistStationSurvivesTheMigration() {
        helper.createDatabase(DB, 2).apply {
            execSQL(
                "INSERT INTO queue (id, track_order, last_play, playing_changed, part_key, hearted, " +
                    "play_queue_id, play_queue_item_id, station_artist_id) " +
                    "VALUES ('42', 0, 0, 0, '/library/parts/9', 0, 32194, 1381657, '270597')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 3, true)

        db.query("SELECT play_queue_id, station_artist_id, station_key FROM queue").use {
            assertTrue(it.moveToFirst())
            assertEquals(32194L, it.getLong(0))
            assertEquals("270597", it.getString(1))
            assertTrue(it.isNull(2))
        }
    }

    @Test
    fun aSavedLibraryStationSurvivesTheMixMigration() {
        helper.createDatabase(DB, 3).apply {
            execSQL(
                "INSERT INTO queue (id, track_order, last_play, playing_changed, part_key, hearted, " +
                    "play_queue_id, play_queue_item_id, station_key) " +
                    "VALUES ('42', 0, 0, 0, '/library/parts/9', 0, 32194, 1381657, '/library/sections/7/stations/2')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true)

        db.query("SELECT station_key, station_artist_id, station_mix_key FROM queue").use {
            assertTrue(it.moveToFirst())
            assertEquals("/library/sections/7/stations/2", it.getString(0))
            assertTrue(it.isNull(1))
            assertTrue(it.isNull(2))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
