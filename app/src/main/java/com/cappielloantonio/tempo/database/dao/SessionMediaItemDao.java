package com.cappielloantonio.tempo.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.SessionMediaItem;

import java.util.List;

@Dao
public interface SessionMediaItemDao {
    // Newest row wins. Rows accumulate across browse nodes -- every onGetChildren
    // inserts a fresh set and nothing prunes until the player is released -- so an
    // unordered lookup returns whichever row Room happens to store first, which is
    // the oldest. That silently resurrects stale state: a track hearted after it was
    // first browsed came back with an empty heart, because the row from the earlier
    // browse still answered this query. It also picks the wrong timestamp, which
    // rebuilds the queue from an older sibling group than the one just tapped.
    @Query("SELECT * FROM session_media_item WHERE id = :id ORDER BY timestamp DESC LIMIT 1")
    SessionMediaItem get(String id);

    // The returned order IS the play order: SessionMediaItemRepository.getSiblings
    // hands these rows straight to MediaLibraryServiceCallback.resolveQueueForItem,
    // which treats the list order as the queue order and computes the start index
    // as indexOfFirst { it.mediaId == firstItem.mediaId } against it. Without this
    // ORDER BY, an unordered scan can return siblings out of track order -- it
    // happened to work before only because SQLite scans in rowid (insertion) order,
    // which is also index order since cache() inserts a browse node's tracks in
    // sequence. `index` is the autoGenerate primary key, so ordering by it recovers
    // insertion order explicitly instead of relying on that coincidence.
    @Query("SELECT * FROM session_media_item WHERE timestamp = :timestamp ORDER BY `index` ASC")
    List<SessionMediaItem> get(long timestamp);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(SessionMediaItem sessionMediaItem);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<SessionMediaItem> sessionMediaItems);

    // Bounded retention (see SessionMediaItemRepository.cache): keeps rows for only
    // the most recent `keepGroups` sibling groups, identified by distinct timestamp
    // values, and deletes the rest. Without this the table grows for the whole
    // session and every blocking main-thread read in get()/getSiblings() scans more
    // rows than it needs to.
    @Query("DELETE FROM session_media_item WHERE timestamp NOT IN " +
            "(SELECT timestamp FROM session_media_item GROUP BY timestamp ORDER BY timestamp DESC LIMIT :keepGroups)")
    void pruneToMostRecentGroups(int keepGroups);

    @Query("DELETE FROM session_media_item")
    void deleteAll();
}