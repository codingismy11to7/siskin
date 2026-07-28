package com.cappielloantonio.tempo.service;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;

import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.repository.ChronologyRepository;
import com.cappielloantonio.tempo.repository.QueueRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MediaManager {
    private static final String TAG = "MediaManager";
    public static AtomicBoolean justStarted = new AtomicBoolean(false);
    public static AtomicBoolean continuousPlayIsRunning = new AtomicBoolean(false);

    public static void setLastPlayedTimestamp(MediaItem mediaItem) {
        if (mediaItem != null) getQueueRepository().setLastPlayedTimestamp(mediaItem.mediaId);
    }

    public static void setPlayingPausedTimestamp(MediaItem mediaItem, long ms) {
        if (mediaItem != null)
            getQueueRepository().setPlayingPausedTimestamp(mediaItem.mediaId, ms);
    }

    public static void scrobble(MediaItem mediaItem, boolean submission) {
        if (mediaItem != null && mediaItem.mediaMetadata.extras != null && Preferences.isScrobblingEnabled()) {
            getSongRepository().scrobble(mediaItem.mediaMetadata.extras.getString("id"), submission);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public static void continuousPlay(MediaItem mediaItem,
                                      ListenableFuture<MediaBrowser> existingBrowserFuture) {
        continuousPlay(mediaItem, existingBrowserFuture, null);
    }
    @OptIn(markerClass = UnstableApi.class)
    public static void continuousPlay(MediaItem mediaItem,
                                      ListenableFuture<MediaBrowser> existingBrowserFuture,
                                      @Nullable Runnable onComplete) {
        if (continuousPlayIsRunning.get() || !Preferences.isInstantMixUsable()) {
            Log.d(TAG, "Continuous Play: already running");
            if (onComplete != null) onComplete.run();
            return;
        }
        Log.d(TAG, "Continuous Play");

        Preferences.setLastInstantMix();
        continuousPlayIsRunning.set(true);

        // keep only NUMBER_TRACKS_KEEP_IN_QUEUE items in queue before starting continuous play
        int numberOfTracksKeepInQueue = Preferences.getNumberOfTracksKeepInQueue();
        if (existingBrowserFuture != null) {
            existingBrowserFuture.addListener(() -> {
                try {
                    if (existingBrowserFuture.isDone()) {
                        MediaBrowser browser = existingBrowserFuture.get();
                        int currentIndex = browser.getCurrentMediaItem() != null
                                ? browser.getCurrentMediaItemIndex()
                                : 0;
                        int firstToKeep = Math.max(0, currentIndex - numberOfTracksKeepInQueue);
                        if (firstToKeep > 0) {
                            Log.d(TAG, "Continuous Play: purging " + firstToKeep + " old items from queue");
                            removeRange(existingBrowserFuture, 0, firstToKeep);
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Continuous Play: purge failed", e);
                }
            }, MoreExecutors.directExecutor());
        }
        String trackId = mediaItem.mediaId;
        String artistId = mediaItem.mediaMetadata.extras != null
                ? mediaItem.mediaMetadata.extras.getString("artistId")
                : null;

        LiveData<List<Child>> instantMix =
                getSongRepository().getContinuousMix(trackId, artistId, 25);

        instantMix.observeForever(new Observer<List<Child>>() {
            @Override
            public void onChanged(List<Child> media) {
                instantMix.removeObserver(this);

                // Filter against current queue before deciding if we need fallback.
                // getSimilarSongs2 doesn't know what's already queued, so it may
                // return tracks we already have. Filter first, then decide.
                if (media != null && !media.isEmpty()) {
                    List<Child> filtered = dedupAgainstQueue(media, existingBrowserFuture);
                    if (!filtered.isEmpty()) {
                        Log.d(TAG, "Continuous Play: adding " + filtered.size() + " similar tracks");
                        enqueue(existingBrowserFuture, filtered, true);
                        continuousPlayIsRunning.set(false);
                        return;
                    }
                }

                if (Preferences.isFallbackToRandomTracksEnabled()) {
                    Log.w(TAG, "Continuous Play: no new similar tracks, falling back to random songs");
                    LiveData<List<Child>> randomSongs = getSongRepository().getRandomSample(25, null, null);
                    randomSongs.observeForever(new Observer<List<Child>>() {
                        @Override
                        public void onChanged(List<Child> random) {
                            randomSongs.removeObserver(this);
                            if (random != null && !random.isEmpty()) {
                                List<Child> filtered = dedupAgainstQueue(random, existingBrowserFuture);
                                if (!filtered.isEmpty()) {
                                    Log.d(TAG, "Continuous Play: adding " + filtered.size() + " random tracks");
                                    enqueue(existingBrowserFuture, filtered, true);
                                } else {
                                    Log.w(TAG, "Continuous Play: random tracks already in queue");
                                }
                            } else {
                                Log.w(TAG, "Continuous Play: random fallback also empty");
                            }
                            continuousPlayIsRunning.set(false);
                        }
                    });
                } else {
                    Log.w(TAG, "Continuous Play: no new similar tracks, random fallback disabled");
                    continuousPlayIsRunning.set(false);
                }
            }
        });
    }

    private static List<Child> dedupAgainstQueue(List<Child> candidates,
                                                  ListenableFuture<MediaBrowser> existingBrowserFuture) {
        if (existingBrowserFuture == null) return new ArrayList<>(candidates);

        final MediaBrowser browser;
        try {
            browser = existingBrowserFuture.get();
        } catch (ExecutionException | InterruptedException e) {
            return new ArrayList<>(candidates);
        }

        Set<String> currentIds = new HashSet<>();
        for (int i = 0; i < Objects.requireNonNull(browser).getMediaItemCount(); i++) {
            currentIds.add(browser.getMediaItemAt(i).mediaId);
        }

        return candidates.stream()
                .filter(child -> !currentIds.contains(child.getId()))
                .collect(Collectors.toList());
    }

    // Only caller is continuousPlay, above; not part of the public MediaManager API.
    private static void enqueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, boolean playImmediatelyAfter) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        Log.d(TAG, "enqueue");
                        MediaBrowser browser = mediaBrowserListenableFuture.get();
                        if (playImmediatelyAfter && browser.getNextMediaItemIndex() != -1) {
                            enqueueDatabase(media, false, browser.getNextMediaItemIndex());
                            browser.addMediaItems(browser.getNextMediaItemIndex(), MappingUtil.mapMediaItems(media));
                        } else {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getMediaItemCount());
                            mediaBrowserListenableFuture.get().addMediaItems(MappingUtil.mapMediaItems(media));
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void saveChronology(MediaItem mediaItem) {
        if (mediaItem != null) {
            getChronologyRepository().insert(new Chronology(mediaItem));
        }
    }

    public static void removeRange(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, int fromItem, int toItem) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().removeMediaItems(fromItem, toItem);
                        getQueueRepository().deleteRange(fromItem, toItem);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    private static QueueRepository getQueueRepository() {
        return new QueueRepository();
    }

    private static SongRepository getSongRepository() {
        return new SongRepository();
    }

    private static ChronologyRepository getChronologyRepository() {
        return new ChronologyRepository();
    }

    private static void enqueueDatabase(List<Child> media, boolean reset, int afterIndex) {
        getQueueRepository().insertAll(media, reset, afterIndex);
    }
}
