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
import com.bumptech.glide.request.FutureTarget;
import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.plex.PlexApi;
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder;
import com.cappielloantonio.tempo.util.HubCoverPool;
import com.cappielloantonio.tempo.util.Preferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class AlbumArtContentProvider extends ContentProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".albumart.provider";
    public static final String ALBUM_ART = "albumArt";
    public static final String DECADE_ART = "decadeArt";
    public static final String HUB_ART = "hubArt";

    private static final int MATCH_ALBUM_ART = 1;
    private static final int MATCH_DECADE_ART = 2;
    private static final int MATCH_HUB_ART = 3;

    /**
     * A decade key must be four digits. It becomes part of a cache filename, and what the guard
     * buys is exactly two properties: the segment is digits only, so no decoded `/`, no `..` and no
     * separator of any kind reaches the filename CompositeArt.cacheFile interpolates; and it is a
     * fixed-width run, so there is no length to play with either. matches() anchors the whole
     * segment rather than a prefix, which is what makes both properties hold of the segment and not
     * merely of a prefix of it.
     *
     * <p>Deliberately not narrowed to `(19|20)\d{2}`. The smaller filename space that buys does no
     * work: nothing is ever cached for a decade that yields no albums, so the bogus names are never
     * written, and a caller wanting to burn Plex queries can loop the 200 allowed-but-absent values
     * as happily as 10,000. Meanwhile it refuses a genuine pre-1900 key -- an 1890s classical or
     * historical album -- which a real library can produce.
     *
     * <p>The residual is the same either way, and is named here rather than oversold: a well-formed
     * but absent decade still costs one Plex query per open, because DecadeCompositeArt.build()
     * returns null before writing anything and so leaves nothing cached to answer the next request.
     */
    private static final Pattern DECADE = Pattern.compile("\\d{4}");

    // Plex's photo transcoder requires both dimensions. The image-size preference
    // this used to read is frozen at its "-1" sentinel -- the settings screen that
    // set it is gone -- so anything non-positive falls back to a size that fills a
    // car browse tile without fetching a full-resolution cover for every row.
    private static final int DEFAULT_ARTWORK_SIZE = 512;

    private ExecutorService executor;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, ALBUM_ART + "/*", MATCH_ALBUM_ART);
        // scope / decade / bucket. The arity is the guard: openDecadeArt reads
        // each of the three by index, so a URI of any other shape must not
        // reach it at all.
        uriMatcher.addURI(AUTHORITY, DECADE_ART + "/*/*/#", MATCH_DECADE_ART);
        // scope / bucket / pool. Fixed arity again, and the pool is one segment
        // rather than one per thumb: UriMatcher has no repeating wildcard, so
        // the alternative was six near-identical rules with the cap on how many
        // covers a caller may demand emerging from the rule set instead of
        // being stated in openHubArt. See HubCoverPool.
        uriMatcher.addURI(AUTHORITY, HUB_ART + "/*/#/*", MATCH_HUB_ART);
    }

    public static Uri contentUri(String artworkId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(ALBUM_ART)
                .appendPath(artworkId)
                .build();
    }

    /**
     * The composite for one decade, in one library, for one hour.
     *
     * <p>All three parts are in the URI for a single reason: the car caches artwork by URI, so
     * anything that has to invalidate a tile has to be visible here. The bucket covers time. {@code
     * scope} -- {@link CompositeArt#scopeOf} -- covers *which library*, and it was the missing one:
     * "1980s" on two servers minted byte-identical URIs, so after a switch under More -&gt; Server
     * Select the car re-served the old server's mosaic out of its own cache and this provider was
     * never opened at all. Keying the cache file by server, which is where that bug was first
     * chased, cannot fix what never reaches the file.
     */
    public static Uri decadeContentUri(String scope, String decade, long bucket) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(DECADE_ART)
                .appendPath(scope)
                .appendPath(decade)
                .appendPath(Long.toString(bucket))
                .build();
    }

    /**
     * The composite for one Discover row, in one library, for one hour.
     *
     * <p>Unlike {@link #decadeContentUri}, this URI names its own covers, so it is already
     * content-addressed: a re-rolled hub changes the pool and therefore the URI. The bucket is here
     * anyway, for a narrower reason than it has there. A tile that degraded because a cover failed
     * to load would otherwise sit in the car's own image cache under a URI nothing invalidates;
     * rolling the bucket is what lets it redraw.
     *
     * <p>The pool costs no Plex request. It is the six items the hub listing already returned --
     * the ones the row's existence was decided from.
     */
    public static Uri hubContentUri(String scope, long bucket, List<String> pool) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(HUB_ART)
                .appendPath(scope)
                .appendPath(Long.toString(bucket))
                .appendPath(HubCoverPool.encode(pool))
                .build();
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        // uriMatcher was declared and never consulted while there was only one
        // path: openFile read getLastPathSegment() and assumed the album shape.
        // A second path is what makes it load-bearing -- reading the decade off
        // the last segment would silently pick up the bucket instead.
        switch (uriMatcher.match(uri)) {
            case MATCH_ALBUM_ART:
                return openAlbumArt(uri);
            case MATCH_DECADE_ART:
                return openDecadeArt(uri);
            case MATCH_HUB_ART:
                return openHubArt(uri);
            default:
                throw new FileNotFoundException("Unrecognised artwork URI");
        }
    }

    private ParcelFileDescriptor openAlbumArt(Uri uri) throws FileNotFoundException {
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

        int size =
                Preferences.getImageSize() > 0 ? Preferences.getImageSize() : DEFAULT_ARTWORK_SIZE;

        PlexApi api = new PlexApi();
        String artworkUrl =
                MediaUrlBuilder.INSTANCE.artworkUrl(
                        api.getServerUri(),
                        thumbPath,
                        PlexApi.serverTokenOrAccount(api.getServerToken(), api.getAccountToken()),
                        size,
                        size);

        // Null means no server or no token yet -- there is no image to serve, and
        // reporting that is what makes the car fall back to the placeholder icon
        // instead of stalling on a pipe that never gets written.
        if (artworkUrl == null) {
            throw new FileNotFoundException("No Plex artwork URL for " + thumbPath);
        }

        final Uri artworkUriFinal = Uri.parse(artworkUrl);

        return pipeFrom(
                thumbPath,
                () -> {
                    var fileRequest =
                            Glide.with(context)
                                    .asFile()
                                    .load(artworkUriFinal)
                                    .diskCacheStrategy(DiskCacheStrategy.DATA);
                    if (Preferences.isDataSavingMode()) {
                        fileRequest = fileRequest.onlyRetrieveFromCache(true);
                    }
                    FutureTarget<File> target = fileRequest.submit();
                    try {
                        return target.get();
                    } finally {
                        // The same leak CompositeArt fixes, and the reason both are
                        // fixed together: an uncleared target stays registered with the
                        // application-scoped RequestManager and keeps its resource with
                        // it. This one has been here since long before the Plex work.
                        //
                        // It can clear before the bytes are read, where the composite
                        // path cannot. A File target's resource is a SimpleResource,
                        // whose recycle() is empty, so clearing drops the registration
                        // and leaves the disk-cache file where it is for pipeFrom to
                        // copy. A Bitmap resource recycles into Glide's pool instead,
                        // which is why the same mistake has two differently-shaped
                        // repairs rather than one.
                        Glide.with(context).clear(target);
                    }
                });
    }

    /**
     * The decade composite. Its validation differs from openAlbumArt's, and deliberately: no
     * caller-supplied path reaches MediaUrlBuilder on *this* route -- the covers come from our own
     * Plex response -- so the open-proxy hazard that path guards against cannot arise here. That is
     * a property of this method and not of this provider: openHubArt below takes its covers from
     * the URI, and guards them the way openAlbumArt guards its thumb. What this path needs instead
     * is a bound on how much work a caller can ask for, because every cache miss is a Plex request
     * made with the user's token, and an answer to "is this URI even about the library we are
     * pointed at now".
     */
    private ParcelFileDescriptor openDecadeArt(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        List<String> segments = uri.getPathSegments();
        String decade = segments.get(2);

        if (!DECADE.matcher(decade).matches()) {
            throw new FileNotFoundException("Not a decade");
        }

        long bucket;
        try {
            bucket = Long.parseLong(segments.get(3));
        } catch (NumberFormatException e) {
            throw new FileNotFoundException("Not a bucket");
        }
        if (!CompositeArtBucket.isLive(bucket, System.currentTimeMillis())) {
            throw new FileNotFoundException("Stale composite bucket");
        }

        // A URI minted for a server the user has since left. Serving it would
        // hand back the previous library's mosaic -- the bug this segment
        // exists to fix, arriving from the other direction -- and the tile it
        // actually asks for is one we can no longer build. Signed out is the
        // same answer for the same reason: no session, nothing to honour a
        // scope against and nothing to draw.
        //
        // Unlike the decade above, this segment never reaches a filename: it is
        // compared for equality against a locally computed string and used for
        // nothing else, and the composite below is still named from the session
        // rather than from anything the caller sent. That is what makes a
        // charset guard unnecessary here rather than merely absent.
        //
        // Known window, named rather than closed: currentScope() reads the
        // session here and DecadeCompositeArt.cached() reads it again below, so
        // a library switch landing between the two lets a URI validated against
        // the old scope be answered out of the new session's cache file. It is
        // microseconds wide and costs one wrong tile until the browse list
        // behind it is re-fetched, which a library switch provokes anyway.
        // Closing it means threading one session snapshot through both, which
        // is what build() already does for itself -- see the snapshot it pins
        // for its lock key -- and is worth doing when this file becomes Kotlin
        // (#86) rather than growing a second session-shaped Java parameter now.
        String scope = CompositeArt.currentScope();
        if (scope == null || !scope.equals(segments.get(1))) {
            throw new FileNotFoundException("Not this library's composite");
        }

        // The hit path does no background work at all: no Glide, no Retrofit, no
        // executor thread. Eight decades scroll into view at once against a pool
        // sized max(2, cores / 2), and only the first browse in an hour should
        // pay for a build.
        File cached = DecadeCompositeArt.cached(context, decade, bucket);
        if (cached != null) {
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        return pipeFrom(decade, () -> DecadeCompositeArt.build(context, decade, bucket));
    }

    /**
     * The hub composite. Its validation differs from both paths above.
     *
     * <p>Cheaper to serve than a decade tile and cheaper to abuse: there is no Plex request on this
     * route at all, so a hostile open costs Glide fetches and no metadata query -- the opposite of
     * the residual openDecadeArt concedes, where a well-formed but absent decade costs one query
     * per open forever.
     *
     * <p>What it does need is a bound on amplification. One open here can trigger seven
     * authenticated fetches where openAlbumArt triggers one -- up to HubCoverPool.MAX candidate
     * loads, plus the full-edge re-request CompositeArt's degraded branch makes when a pool of four
     * or more lands fewer than four covers and the layout falls back to one cell -- which is what
     * the pool cap is for. The open-proxy hazard itself is openAlbumArt's, not a new one: any app
     * on the head unit has always been able to ask this authority for any server-relative Plex
     * path, behind this same guard.
     *
     * <p>One residual is worth naming rather than leaving for someone to rediscover: a hostile
     * caller can mint many distinct valid pools out of real thumb paths -- reordering six real
     * thumbs alone is hundreds of pools -- and cause a small JPEG per pool to be written under this
     * bucket, since evictStale reaps only stale buckets, not a live bucket's volume. That is
     * app-private storage in a system-evictable cacheDir, no worse than the same app filling its
     * own cache directly, so it is not treated as a bug here -- just recorded so it reads as
     * considered rather than missed.
     */
    private ParcelFileDescriptor openHubArt(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        List<String> segments = uri.getPathSegments();

        long bucket;
        try {
            bucket = Long.parseLong(segments.get(2));
        } catch (NumberFormatException e) {
            // `#` restricts the segment to digits but not to a digit run that
            // fits in a long, which is the case this catch is for.
            throw new FileNotFoundException("Not a bucket");
        }
        if (!CompositeArtBucket.isLive(bucket, System.currentTimeMillis())) {
            throw new FileNotFoundException("Stale composite bucket");
        }

        // The same guard, the same known window as openDecadeArt's: currentScope()
        // reads the session here and HubCompositeArt.cached() reads it again
        // below, so a library switch landing between the two lets a URI validated
        // against the old scope be answered out of the new session's cache file.
        // Microseconds wide, one wrong tile until the list behind it is
        // re-fetched, which a library switch provokes anyway.
        String scope = CompositeArt.currentScope();
        if (scope == null || !scope.equals(segments.get(1))) {
            throw new FileNotFoundException("Not this library's composite");
        }

        List<String> pool = HubCoverPool.decode(segments.get(3));
        // pool.isEmpty() is not reachable today -- HubCoverPool.decode is
        // split(",") and never returns an empty list, not even for "", which
        // HubCoverPoolTest pins as decode("") == listOf(""). It is kept as
        // defence against a future decode() that could return one, not as a
        // condition this guard is currently relied on to catch.
        if (pool.isEmpty() || pool.size() > HubCoverPool.MAX) {
            throw new FileNotFoundException("Not a cover pool");
        }
        for (String thumb : pool) {
            // One bad entry refuses the whole URI rather than being filtered
            // out: filtering would let a caller pair one real thumb with five
            // probes and still be handed a tile, and refusing whole costs real
            // traffic nothing, because a pool this app minted is valid in every
            // position by construction.
            if (!MediaUrlBuilder.isServerRelativePath(thumb)) {
                throw new FileNotFoundException("Not a Plex artwork path");
            }
        }

        File cached = HubCompositeArt.cached(context, pool, bucket);
        if (cached != null) {
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        // The pool's digest, not the HUB_ART literal, so a build failure names
        // which composite failed in the log -- pipeFrom's label is shared with
        // openDecadeArt, which passes the decade for the same reason.
        return pipeFrom(
                HubCompositeArt.idFor(pool), () -> HubCompositeArt.build(context, pool, bucket));
    }

    /**
     * The read end of a pipe that {@code source} fills on the background executor.
     *
     * <p>Shared by both artwork paths: one resolves a Glide-cached cover, the other builds a
     * composite, and everything after "which file" is identical. The caller gets its descriptor
     * immediately -- openFile runs on a binder thread, and neither a Plex round trip nor a Glide
     * fetch may happen there.
     *
     * <p>A source returning null, or throwing, closes the write side with an error, which the car
     * renders as the placeholder icon.
     */
    private ParcelFileDescriptor pipeFrom(String label, Callable<File> source)
            throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readSide = pipe[0];
            ParcelFileDescriptor writeSide = pipe[1];

            executor.execute(
                    () -> {
                        try (OutputStream out =
                                new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
                            File file = source.call();
                            if (file == null) {
                                writeSide.closeWithError("No artwork for " + label);
                                return;
                            }
                            try (InputStream in = new FileInputStream(file)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, bytesRead);
                                }
                            }
                        } catch (Exception e) {
                            try {
                                writeSide.closeWithError("Failed to load image: " + e.getMessage());
                            } catch (IOException ignored) {
                            }
                        }
                    });

            return readSide;
        } catch (IOException e) {
            throw new FileNotFoundException("Could not create pipe: " + e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        executor =
                Executors.newFixedThreadPool(
                        Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
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
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] strings,
            @Nullable String s,
            @Nullable String[] strings1,
            @Nullable String s1) {
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
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues contentValues,
            @Nullable String s,
            @Nullable String[] strings) {
        return 0;
    }
}
