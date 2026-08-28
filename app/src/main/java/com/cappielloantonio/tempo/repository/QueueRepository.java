package com.cappielloantonio.tempo.repository;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.QueueDao;
import com.cappielloantonio.tempo.model.Queue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@OptIn(markerClass = UnstableApi.class)
public class QueueRepository {
    private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private final QueueDao queueDao = AppDatabase.getInstance().queueDao();

    public List<MediaItem> getMedia() {
        final List<MediaItem> media = new ArrayList<>();

        Thread thread =
                new Thread(
                        () -> {
                            for (Queue row : queueDao.getAllSimple()) {
                                media.add(row.toMediaItem());
                            }
                        });
        thread.start();

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return media;
    }

    private boolean isMediaInQueue(List<Queue> queue, MediaItem media) {
        if (queue == null || media == null || media.mediaId == null) return false;
        return queue.stream()
                .anyMatch(
                        queueItem -> queueItem != null && media.mediaId.equals(queueItem.getId()));
    }

    public void insertAll(List<MediaItem> toAdd, boolean reset, int afterIndex) {
        List<MediaItem> snapshot = new ArrayList<>(toAdd);

        dbExecutor.execute(
                () -> {
                    List<Queue> media = reset ? new ArrayList<>() : queueDao.getAllSimple();

                    final List<Queue> existing = media;
                    List<MediaItem> filtered =
                            snapshot.stream()
                                    .filter(item -> !isMediaInQueue(existing, item))
                                    .collect(Collectors.toList());

                    int insertAt = Math.max(0, Math.min(afterIndex, media.size()));
                    for (int i = 0; i < filtered.size(); i++) {
                        Queue row = Queue.fromMediaItem(filtered.get(i));
                        if (row != null) media.add(insertAt + i, row);
                    }

                    for (int i = 0; i < media.size(); i++) {
                        media.get(i).setTrackOrder(i);
                    }

                    queueDao.replaceQueue(media);
                });
    }

    public void setLastPlayedTimestamp(String id) {
        dbExecutor.execute(() -> queueDao.setLastPlay(id, System.currentTimeMillis()));
    }

    public void setPlayingPausedTimestamp(String id, long ms) {
        dbExecutor.execute(() -> queueDao.setPlayingChanged(id, ms));
    }

    public int getLastPlayedMediaIndex() {
        Queue last = getLastPlayed();
        return last != null ? last.getTrackOrder() : 0;
    }

    public long getLastPlayedMediaTimestamp() {
        Queue last = getLastPlayed();
        return last != null ? last.getPlayingChanged() : 0;
    }

    private Queue getLastPlayed() {
        final Queue[] result = new Queue[1];

        Thread thread = new Thread(() -> result[0] = queueDao.getLastPlayed());
        thread.start();

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return result[0];
    }

    public void deleteAll() {
        dbExecutor.execute(queueDao::deleteAll);
    }

    public void deleteRange(int fromIndex, int toIndex) {
        dbExecutor.execute(
                () -> {
                    List<Queue> media = queueDao.getAllSimple();
                    if (fromIndex < 0 || toIndex > media.size() || fromIndex >= toIndex) return;
                    media.subList(fromIndex, toIndex).clear();
                    for (int i = 0; i < media.size(); i++) {
                        media.get(i).setTrackOrder(i);
                    }
                    queueDao.replaceQueue(media);
                });
    }
}
