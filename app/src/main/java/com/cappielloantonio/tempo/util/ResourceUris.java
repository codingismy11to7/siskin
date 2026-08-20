package com.cappielloantonio.tempo.util;

import android.content.ContentResolver;
import android.content.res.Resources;
import android.net.Uri;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.BuildConfig;

/**
 * Builds {@code android.resource://} URIs in name form.
 *
 * <p>Android Auto resolves these URIs by numeric id or by name, but Android
 * Automotive OS resolves them <em>by name only</em>: it reconstructs a resource
 * name as {@code authority + path.replaceFirst("/", ":")} and hands that to
 * {@link Resources#getIdentifier}, which returns 0 for a numeric path. AAOS then
 * calls {@code getDrawable(0)} on a background thread, and the resulting
 * {@link Resources.NotFoundException} is uncaught — it takes down the car's
 * whole media process, not just this app.
 *
 * <p>So a URI must look like
 * {@code android.resource://<package>/drawable/ic_aa_albums}, never
 * {@code android.resource://<package>/2131230812}.
 *
 * <p>Because the name is what resolves, every drawable reaching this method
 * depends on its <em>name</em> surviving into the release APK, not just its
 * bytes. R8's optimized resource shrinking is on, and it was checked against
 * exactly that: all ten {@code ic_browse_*} icons and
 * {@code media3_icon_shuffle_on} keep their names in the shrunk artifact. A new
 * icon routed through here wants the same check —
 * {@code aapt2 dump resources} on the release APK — because nothing else will
 * catch it. It builds, it passes, and then it takes the car's media process
 * down on a device.
 */
public final class ResourceUris {
    private ResourceUris() {
    }

    /**
     * @param resId any resource id, e.g. {@code R.drawable.ic_aa_albums}
     * @return a name-form resource URI both Android Auto and AAOS can resolve
     */
    public static Uri forResource(int resId) {
        Resources resources = App.getContext().getResources();

        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(BuildConfig.APPLICATION_ID)
                .appendPath(resources.getResourceTypeName(resId))
                .appendPath(resources.getResourceEntryName(resId))
                .build();
    }
}
