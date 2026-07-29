package com.cappielloantonio.tempo.database.dao

import androidx.room.Room
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.SessionMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SessionMediaItemDao against a real, isolated in-memory Room database.
 *
 * Robolectric rather than plain JUnit, and a real (in-memory) database rather
 * than a mock: the behaviour under test is SQLite's own row ordering and
 * deletion given the DAO's @Query strings, which a mocked DAO would not
 * exercise at all.
 */
@RunWith(RobolectricTestRunner::class)
class SessionMediaItemDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SessionMediaItemDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(App.getContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionMediaItemDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun row(id: String, index: Int, timestamp: Long) = SessionMediaItem().apply {
        this.index = index
        this.id = id
        this.timestamp = timestamp
    }

    /**
     * Characterisation test, not regression coverage: `index` is
     * `@PrimaryKey(autoGenerate = true)`, i.e. this table's rowid alias, so a
     * full scan of session_media_item (there is no usable index on
     * `timestamp`, so `get(long)` is always one) already visits rows in
     * ascending `index` order on this schema -- with or without
     * "ORDER BY `index` ASC" -- see the DAO's comment on this query. The
     * insertion order below is scrambled relative to index order (30, then
     * 10, then 20) but that changes nothing: an explicit `index` value
     * becomes that row's rowid regardless of insertion sequence, so no test
     * data on this schema can make the ORDER BY clause the thing that
     * decides the outcome. The ORDER BY is kept anyway, because it makes the
     * guarantee explicit rather than contingent on `index` staying the rowid
     * alias.
     *
     * What this test still pins down is the *contract* -- ascending by
     * `index`, not descending and not some other column -- which a mistake
     * like "ORDER BY `index` DESC" or sorting by the wrong column would
     * still trip.
     */
    @Test
    fun getByTimestampReturnsSiblingsOrderedByIndexAscending() {
        dao.insertAll(
            listOf(
                row(id = "third", index = 30, timestamp = 1L),
                row(id = "first", index = 10, timestamp = 1L),
                row(id = "second", index = 20, timestamp = 1L)
            )
        )

        val siblings = dao.get(1L)

        assertEquals(listOf("first", "second", "third"), siblings.map { it.id })
    }

    @Test
    fun pruneToMostRecentGroupsDeletesOnlyTheOldestGroups() {
        // Six sibling groups, oldest to newest by timestamp -- one more than the
        // production retention bound (5).
        for (t in 1L..6L) {
            dao.insertAll(listOf(row(id = "g$t", index = 0, timestamp = t)))
        }

        dao.pruneToMostRecentGroups(5)

        assertTrue("the oldest group must be gone", dao.get(1L).isEmpty())
        for (t in 2L..6L) {
            assertEquals("group $t must survive", 1, dao.get(t).size)
        }
    }

    @Test
    fun pruneToMostRecentGroupsIsANoOpUnderTheBound() {
        for (t in 1L..3L) {
            dao.insertAll(listOf(row(id = "g$t", index = 0, timestamp = t)))
        }

        dao.pruneToMostRecentGroups(5)

        for (t in 1L..3L) {
            assertEquals(1, dao.get(t).size)
        }
    }

    // No test for the pruneToMostRecentGroups `IS NOT NULL` guards (see the DAO's
    // comment on that query): verified directly against SQLite that a stray
    // NULL-timestamp row cannot actually change this query's output either way, with
    // or without the guards, because `ORDER BY timestamp DESC` always sorts NULL
    // last -- it can only enter the `LIMIT :keepGroups` kept set once every group
    // already fits inside the bound, at which point there is nothing outside it left
    // to wrongly spare. Any test written against this table would pass identically
    // against the guarded and unguarded query, which is exactly the assertion this
    // suite's other tests are written not to write.
}
