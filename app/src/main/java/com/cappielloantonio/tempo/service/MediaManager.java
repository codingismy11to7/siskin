package com.cappielloantonio.tempo.service;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;

import com.cappielloantonio.tempo.plex.PlexMediaMapper;
import com.cappielloantonio.tempo.plex.api.search.SearchClient;
import com.cappielloantonio.tempo.repository.PlexMixRepository;
import com.cappielloantonio.tempo.repository.QueueRepository;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Reports playback to Plex's timeline endpoint.
     *
     * Plex wants the part being played and a transport state where Subsonic's
     * scrobble was a fire-and-forget "I played this": a track with no part key
     * has nothing to report against, so it is skipped rather than sent half-formed.
     *
     * {@code positionMs} matters because Plex, not this client, decides whether a
     * play counts: the {@code /:/timeline} handler on the server compares the
     * reported position against the track duration and its own watched-percentage
     * threshold before it will mark the item played. A "stopped" report sent with
     * position 0 reads to Plex as "left at the very start" regardless of how much
     * actually played, so it silently never registers a play. Callers must pass
     * the real position at the moment of the event, not a placeholder.
     */
    public static void scrobble(MediaItem mediaItem, boolean submission, long positionMs) {
        if (mediaItem == null || mediaItem.mediaMetadata.extras == null) return;
        if (!Preferences.isScrobblingEnabled()) return;

        String ratingKey = mediaItem.mediaMetadata.extras.getString(PlexMediaMapper.EXTRA_ID);
        String partKey = mediaItem.mediaMetadata.extras.getString(PlexMediaMapper.EXTRA_PART_KEY);
        if (ratingKey == null || partKey == null) return;

        String state = submission ? SearchClient.STATE_STOPPED : SearchClient.STATE_PLAYING;
        // Via PlexScrobbler because SearchClient.reportProgress is a suspend
        // function, which Java has no way to call.
        PlexScrobbler.report(ratingKey, partKey, state, positionMs);
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

        // The similar tier may answer with tracks already queued, so filter before
        // deciding whether the random fallback is needed -- Plex's similar endpoint
        // does not know what is in the queue.
        getMixRepository().similarTracks(trackId, 25, similar -> {
            if (!similar.isEmpty()) {
                List<MediaItem> filtered = dedupAgainstQueue(similar, existingBrowserFuture);
                if (!filtered.isEmpty()) {
                    Log.d(TAG, "Continuous Play: adding " + filtered.size() + " similar tracks");
                    enqueue(existingBrowserFuture, filtered, true);
                    continuousPlayIsRunning.set(false);
                    if (onComplete != null) onComplete.run();
                    return;
                }
            }

            if (!Preferences.isFallbackToRandomTracksEnabled()) {
                Log.w(TAG, "Continuous Play: no new similar tracks, random fallback disabled");
                continuousPlayIsRunning.set(false);
                if (onComplete != null) onComplete.run();
                return;
            }

            Log.w(TAG, "Continuous Play: no new similar tracks, falling back to random songs");
            getMixRepository().randomTracks(25, random -> {
                if (!random.isEmpty()) {
                    List<MediaItem> filtered = dedupAgainstQueue(random, existingBrowserFuture);
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
                if (onComplete != null) onComplete.run();
            });
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private static List<MediaItem> dedupAgainstQueue(List<MediaItem> candidates,
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
                .filter(item -> !currentIds.contains(item.mediaId))
                .collect(Collectors.toList());
    }

    // Only caller is continuousPlay, above; not part of the public MediaManager API.
    @OptIn(markerClass = UnstableApi.class)
    private static void enqueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<MediaItem> media, boolean playImmediatelyAfter) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        Log.d(TAG, "enqueue");
                        MediaBrowser browser = mediaBrowserListenableFuture.get();
                        if (playImmediatelyAfter && browser.getNextMediaItemIndex() != -1) {
                            enqueueDatabase(media, false, browser.getNextMediaItemIndex());
                            browser.addMediaItems(browser.getNextMediaItemIndex(), media);
                        } else {
                            enqueueDatabase(media, false, browser.getMediaItemCount());
                            browser.addMediaItems(media);
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    @OptIn(markerClass = UnstableApi.class)
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

    private static PlexMixRepository getMixRepository() {
        return new PlexMixRepository();
    }

    private static void enqueueDatabase(List<MediaItem> media, boolean reset, int afterIndex) {
        getQueueRepository().insertAll(media, reset, afterIndex);
    }
}
