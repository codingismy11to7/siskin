package com.cappielloantonio.tempo.provider;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.plex.PlexApi;
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder;
import com.cappielloantonio.tempo.util.Preferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AlbumArtContentProvider extends ContentProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".albumart.provider";
    public static final String ALBUM_ART = "albumArt";

    // Plex's photo transcoder requires both dimensions. The image-size preference
    // this used to read is frozen at its "-1" sentinel -- the settings screen that
    // set it is gone -- so anything non-positive falls back to a size that fills a
    // car browse tile without fetching a full-resolution cover for every row.
    private static final int DEFAULT_ARTWORK_SIZE = 512;

    private ExecutorService executor;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, "albumArt/*", 1);
    }

    public static Uri contentUri(String artworkId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(ALBUM_ART)
                .appendPath(artworkId)
                .build();
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Context context = getContext();

        // The last path segment is a Plex thumb path such as
        // /library/metadata/12345/thumb/1699999999. contentUri() percent-encodes its
        // separators and getLastPathSegment() decodes them, so the multi-segment path
        // arrives whole rather than truncated to its final component.
        String thumbPath = uri.getLastPathSegment();

        // This provider is exported -- the car reads artwork through it -- so the
        // path segment is attacker-controlled: any app on the head unit can ask
        // for any URI under this authority. MediaUrlBuilder.artworkUrl embeds the
        // segment as `url=` on Plex's photo transcoder, which will fetch whatever
        // that names, absolute URLs on other hosts included. Unvalidated, that
        // makes the user's Plex server a proxy the caller can point at anything
        // reachable from it, authenticated with the user's own token.
        //
        // A genuine Plex thumb is always a server-relative path, so anything else
        // is refused the same way an absent one is: FileNotFoundException, which
        // the car renders as the placeholder icon.
        if (!MediaUrlBuilder.isServerRelativePath(thumbPath)) {
            throw new FileNotFoundException("Not a Plex artwork path");
        }

        int size = Preferences.getImageSize() > 0 ? Preferences.getImageSize() : DEFAULT_ARTWORK_SIZE;

        PlexApi api = new PlexApi();
        String artworkUrl = MediaUrlBuilder.INSTANCE.artworkUrl(
                api.getServerUri(),
                thumbPath,
                PlexApi.serverTokenOrAccount(api.getServerToken(), api.getAccountToken()),
                size,
                size
        );

        // Null means no server or no token yet -- there is no image to serve, and
        // reporting that is what makes the car fall back to the placeholder icon
        // instead of stalling on a pipe that never gets written.
        if (artworkUrl == null) {
            throw new FileNotFoundException("No Plex artwork URL for " + thumbPath);
        }

        final Uri artworkUriFinal = Uri.parse(artworkUrl);

        try {
            // use pipe to communicate between background thread and caller of openFile()
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readSide = pipe[0];
            ParcelFileDescriptor writeSide = pipe[1];

            // perform loading in background thread to avoid blocking UI
            executor.execute(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {

                    var fileRequest = Glide.with(context)
                            .asFile()
                            .load(artworkUriFinal)
                            .diskCacheStrategy(DiskCacheStrategy.DATA);
                    if (Preferences.isDataSavingMode()) {
                        fileRequest = fileRequest.onlyRetrieveFromCache(true);
                    }
                    File file = fileRequest.submit().get();

                    // copy artwork down pipe returned by ContentProvider
                    try (InputStream in = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    } catch (Exception e) {
                        writeSide.closeWithError("Failed to load image: " + e.getMessage());
                    }

                } catch (Exception e) {
                    try {
                        writeSide.closeWithError("Failed to load image: " + e.getMessage());
                    } catch (IOException ignored) {}
                }
            });

            return readSide;

        } catch (IOException e) {
            throw new FileNotFoundException("Could not create pipe: " + e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        executor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2)
        );
        return true;
    }

    @Override
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] strings, @Nullable String s, @Nullable String[] strings1, @Nullable String s1) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues contentValues) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String s, @Nullable String[] strings) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues contentValues, @Nullable String s, @Nullable String[] strings) {
        return 0;
    }
}
