package com.cappielloantonio.tempo.repository;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.SessionMediaItemDao;
import com.cappielloantonio.tempo.model.SessionMediaItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remembers the tracks a browse node returned, so that tapping one can rebuild
 * the whole list as the play queue.
 *
 * Split out of AutomotiveRepository, which mixed this Room cache with browsing
 * over HTTP. Reads block the caller because media3 calls onSetMediaItems and
 * onAddMediaItems synchronously and expects the queue back.
 */
@OptIn(markerClass = UnstableApi.class)
public class SessionMediaItemRepository {
    private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    /**
     * How many sibling groups (browse nodes) cache() keeps around. Bounds the
     * table to a handful of recent nodes -- enough to survive back-navigation
     * through a few browse levels -- instead of growing for the whole session.
     */
    private static final int RETAINED_GROUPS = 5;

    /**
     * Source of the `timestamp` column: a process-wide monotonic counter, not a
     * clock reading. System.currentTimeMillis() has millisecond resolution, so
     * two browse nodes cached within the same millisecond would collide and
     * silently merge into one sibling group; a backwards clock step (NTP, DST,
     * manual change) could also break the "newest row wins" assumption that
     * SessionMediaItemDao.get(String) relies on. Seeding from
     * currentTimeMillis() keeps values roughly time-ordered for anyone reading
     * the column directly, but only its ordering and uniqueness are ever relied
     * upon -- never its value as an actual point in time.
     */
    private static final AtomicLong groupSequence = new AtomicLong(System.currentTimeMillis());

    private final SessionMediaItemDao dao = AppDatabase.getInstance().sessionMediaItemDao();

    /** All items of one browse node share a timestamp; that is how siblings are found. */
    public void cache(List<MediaItem> items) {
        long timestamp = groupSequence.incrementAndGet();

        List<SessionMediaItem> rows = new ArrayList<>();
        for (MediaItem item : items) {
            SessionMediaItem row = SessionMediaItem.fromMediaItem(item);
            if (row == null) continue;
            row.setTimestamp(timestamp);
            rows.add(row);
        }

        // Insert and prune run in one Room transaction so a concurrent reader
        // (get()/getSiblings() run on their own ad hoc threads, outside
        // dbExecutor) can never observe the row count transiently over the
        // retention bound between the two statements.
        dbExecutor.execute(() -> AppDatabase.getInstance().runInTransaction(() -> {
            dao.insertAll(rows);
            dao.pruneToMostRecentGroups(RETAINED_GROUPS);
        }));
    }

    public SessionMediaItem get(String id) {
        final SessionMediaItem[] result = new SessionMediaItem[1];

        Thread thread = new Thread(() -> result[0] = dao.get(id));
        thread.start();

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The child thread may still be writing result[0]; returning it here
            // would be an unsynchronised data race. getSiblings() below already
            // treats interruption as "no answer" by returning empty -- null is
            // this method's equivalent of that.
            return null;
        }

        return result[0];
    }

    public List<MediaItem> getSiblings(long timestamp) {
        final List<MediaItem> items = new ArrayList<>();

        Thread thread = new Thread(() -> {
            List<SessionMediaItem> rows = dao.get(timestamp);
            for (SessionMediaItem row : rows) {
                items.add(row.toMediaItem());
            }
        });
        thread.start();

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }

        return items;
    }

    public void deleteAll() {
        dbExecutor.execute(dao::deleteAll);
    }
}
