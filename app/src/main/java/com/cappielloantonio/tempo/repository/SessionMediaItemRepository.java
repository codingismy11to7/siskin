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
 * Remembers the tracks a browse node returned, so that tapping one can rebuild the whole list as
 * the play queue.
 *
 * <p>Split out of AutomotiveRepository, which mixed this Room cache with browsing over HTTP.
 *
 * <p>Reads ({@link #get}, {@link #getSiblings}) block their caller on a scratch thread. That is a
 * choice, not a constraint: both callers are inside MediaLibrarySessionCallback's
 * onSetMediaItems/onAddMediaItems, which return a ListenableFuture and so are free to complete
 * later. It is kept because the read is a single indexed lookup against a table bounded to five
 * browse nodes, and because the alternative -- threading a future back out through
 * resolveQueueForItem -- buys nothing while the read stays that small. Writes already run on {@code
 * dbExecutor}; if these reads ever grow past a lookup, this is the comment that should stop being
 * true rather than the reason not to change it.
 */
@OptIn(markerClass = UnstableApi.class)
public class SessionMediaItemRepository {
    private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    /**
     * How many sibling groups (browse nodes) cache() keeps around. Bounds the table to a handful of
     * recent nodes -- enough to survive back-navigation through a few browse levels -- instead of
     * growing for the whole session. What actually makes 5 safe rather than merely convenient: the
     * car re-issues onGetChildren on every node entry, so a tapped node is re-cached as the newest
     * group on back-navigation instead of depending on its original cache entry still being one of
     * the five retained.
     */
    private static final int RETAINED_GROUPS = 5;

    /**
     * Source of the `timestamp` column: a process-wide monotonic counter, not a clock reading.
     * System.currentTimeMillis() has millisecond resolution, so two browse nodes cached within the
     * same millisecond would collide and silently merge into one sibling group; a backwards clock
     * step (NTP, DST, manual change) could also break the "newest row wins" assumption that
     * SessionMediaItemDao.get(String) relies on. Seeding from currentTimeMillis() keeps values
     * roughly time-ordered for anyone reading the column directly, but only its ordering and
     * uniqueness are ever relied upon -- never its value as an actual point in time.
     */
    private static final AtomicLong groupSequence = new AtomicLong(System.currentTimeMillis());

    private final SessionMediaItemDao dao = AppDatabase.getInstance().sessionMediaItemDao();

    /**
     * Wipes any rows already on disk when this repository is constructed.
     *
     * <p>This cache is session-scoped by design (see the class doc above), so rows from a previous
     * process are never wanted -- but MediaService only reaches releasePlayers() -> deleteAll() on
     * a clean shutdown, not a force-stop, so stale rows can still be sitting in the table when a
     * new process's repository is constructed. Left alone, a backwards clock step between processes
     * (a head unit cold-booting before time sync, or a manual date change) would let those stale
     * rows outrank every freshly cached group in pruneToMostRecentGroups' `ORDER BY timestamp
     * DESC`, since groupSequence below reseeds from System.currentTimeMillis() on every process
     * start: the new seed would sit below the stale timestamps, so the prune would keep the stale
     * groups and delete each new one as it is written, and get(id) would keep answering from the
     * stale rows. Deleting here removes the clock dependency entirely instead of merely narrowing
     * it (e.g. re-seeding groupSequence off the stale max would still depend on that stale max
     * being readable/correct); it also fits the "session-scoped by design" framing better than
     * trying to make cross-process rows survive at all.
     *
     * <p>Runs on dbExecutor like every other write, so it is guaranteed to finish before any later
     * cache() call on this same single-threaded executor can observe the table again.
     */
    public SessionMediaItemRepository() {
        dbExecutor.execute(dao::deleteAll);
    }

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
        dbExecutor.execute(
                () ->
                        AppDatabase.getInstance()
                                .runInTransaction(
                                        () -> {
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

        Thread thread =
                new Thread(
                        () -> {
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
