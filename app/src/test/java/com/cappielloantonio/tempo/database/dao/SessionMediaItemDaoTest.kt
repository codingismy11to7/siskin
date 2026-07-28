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

    @Test
    fun getByTimestampReturnsSiblingsInIndexOrderNotInsertionOrder() {
        // Insertion order is deliberately scrambled relative to index order.
        // resolveQueueForItem (MediaLibraryServiceCallback) treats this method's
        // return order as the play order and computes the tapped item's start
        // index against it -- see the DAO's comment on this query. Without
        // "ORDER BY `index` ASC" a plain table scan could return these rows in
        // any order SQLite happens to store them in.
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
}
