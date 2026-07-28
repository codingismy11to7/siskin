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

    @Query("SELECT * FROM session_media_item WHERE timestamp = :timestamp")
    List<SessionMediaItem> get(long timestamp);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(SessionMediaItem sessionMediaItem);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<SessionMediaItem> sessionMediaItems);

    @Query("DELETE FROM session_media_item")
    void deleteAll();
}