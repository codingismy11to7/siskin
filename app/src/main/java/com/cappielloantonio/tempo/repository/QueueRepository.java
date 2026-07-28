package com.cappielloantonio.tempo.repository;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.QueueDao;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class QueueRepository {
    private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private final QueueDao queueDao = AppDatabase.getInstance().queueDao();

    public List<Child> getMedia() {
        List<Child> media = new ArrayList<>();

        GetMediaThreadSafe getMedia = new GetMediaThreadSafe(queueDao);
        Thread thread = new Thread(getMedia);
        thread.start();

        try {
            thread.join();
            media = getMedia.getMedia().stream()
                    .map(Child.class::cast)
                    .collect(Collectors.toList());

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return media;
    }

    private boolean isMediaInQueue(List<Queue> queue, Child media) {
        if (queue == null || media == null) return false;
        return queue.stream().anyMatch(queueItem ->
                queueItem != null && media.getId() != null &&
                        queueItem.getId().equals(media.getId())
        );
    }

    public void insertAll(List<Child> toAdd, boolean reset, int afterIndex) {
        dbExecutor.execute(() -> {
            List<Queue> media = new ArrayList<>();

            if (!reset) {
                media = queueDao.getAllSimple();
            }

            final List<Queue> finalMedia = media;
            List<Child> toAddCopy = new ArrayList<>(toAdd);
            List<Child> filteredToAdd = toAddCopy.stream()
                    .filter(child -> !isMediaInQueue(finalMedia, child))
                    .collect(Collectors.toList());

            for (int i = 0; i < filteredToAdd.size(); i++) {
                Queue queueItem = new Queue(filteredToAdd.get(i));
                media.add(afterIndex + i, queueItem);
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
        int index = 0;

        GetLastPlayedMediaThreadSafe getLastPlayedMediaThreadSafe = new GetLastPlayedMediaThreadSafe(queueDao);
        Thread thread = new Thread(getLastPlayedMediaThreadSafe);
        thread.start();

        try {
            thread.join();
            Queue lastMediaPlayed = getLastPlayedMediaThreadSafe.getQueueItem();
            if (lastMediaPlayed != null) {
                index = lastMediaPlayed.getTrackOrder();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return index;
    }

    public long getLastPlayedMediaTimestamp() {
        long timestamp = 0;

        GetLastPlayedMediaThreadSafe getLastPlayedMediaThreadSafe = new GetLastPlayedMediaThreadSafe(queueDao);
        Thread thread = new Thread(getLastPlayedMediaThreadSafe);
        thread.start();

        try {
            thread.join();
            Queue lastMediaPlayed = getLastPlayedMediaThreadSafe.getQueueItem();
            if (lastMediaPlayed != null) {
                timestamp = lastMediaPlayed.getPlayingChanged();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return timestamp;
    }

    private static class GetMediaThreadSafe implements Runnable {
        private final QueueDao queueDao;
        private List<Queue> media;

        public GetMediaThreadSafe(QueueDao queueDao) {
            this.queueDao = queueDao;
        }

        @Override
        public void run() {
            media = queueDao.getAllSimple();
        }

        public List<Queue> getMedia() {
            return media;
        }
    }

    private static class GetLastPlayedMediaThreadSafe implements Runnable {
        private final QueueDao queueDao;
        private Queue lastMediaPlayed;

        public GetLastPlayedMediaThreadSafe(QueueDao queueDao) {
            this.queueDao = queueDao;
        }

        @Override
        public void run() {
            lastMediaPlayed = queueDao.getLastPlayed();
        }

        public Queue getQueueItem() {
            return lastMediaPlayed;
        }
    }

    public void deleteRange(int fromIndex, int toIndex) {
        dbExecutor.execute(() -> {
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
