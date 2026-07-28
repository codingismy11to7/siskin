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

    private final SessionMediaItemDao dao = AppDatabase.getInstance().sessionMediaItemDao();

    /** All items of one browse node share a timestamp; that is how siblings are found. */
    public void cache(List<MediaItem> items) {
        long timestamp = System.currentTimeMillis();

        List<SessionMediaItem> rows = new ArrayList<>();
        for (MediaItem item : items) {
            SessionMediaItem row = SessionMediaItem.fromMediaItem(item);
            if (row == null) continue;
            row.setTimestamp(timestamp);
            rows.add(row);
        }

        dbExecutor.execute(() -> dao.insertAll(rows));
    }

    public SessionMediaItem get(String id) {
        final SessionMediaItem[] result = new SessionMediaItem[1];

        Thread thread = new Thread(() -> result[0] = dao.get(id));
        thread.start();

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
