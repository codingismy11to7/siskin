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
