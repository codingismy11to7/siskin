package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.text.Html;
import android.util.Log;

import com.cappielloantonio.tempo.App;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MusicUtil {
    private static final String TAG = "MusicUtil";

    private static final Pattern BITRATE_PATTERN = Pattern.compile("&maxBitRate=\\d+");
    private static final Pattern FORMAT_PATTERN = Pattern.compile("&format=\\w+");

    public static Uri getStreamUri(String id, int timeOffset) {
        Map<String, String> params = App.getSubsonicClientInstance(false).getParams();

        StringBuilder uri = new StringBuilder();

        uri.append(App.getSubsonicClientInstance(false).getUrl());
        uri.append("stream");

        if (params.containsKey("u") && params.get("u") != null)
            uri.append("?u=").append(Util.encode(params.get("u")));
        if (params.containsKey("p") && params.get("p") != null)
            uri.append("&p=").append(params.get("p"));
        if (params.containsKey("s") && params.get("s") != null)
            uri.append("&s=").append(params.get("s"));
        if (params.containsKey("t") && params.get("t") != null)
            uri.append("&t=").append(params.get("t"));
        if (params.containsKey("v") && params.get("v") != null)
            uri.append("&v=").append(params.get("v"));
        if (params.containsKey("c") && params.get("c") != null)
            uri.append("&c=").append(params.get("c"));

        String selectedBitrate = getBitratePreference();
        String selectedFormat = getTranscodingFormatPreference();
        Log.i(TAG, "DEBUG: Requesting Format: " + selectedFormat + " at Bitrate: " + selectedBitrate);
        
        if (!Preferences.isServerPrioritized())
            uri.append("&maxBitRate=").append(getBitratePreference());
        if (!Preferences.isServerPrioritized())
            uri.append("&format=").append(getTranscodingFormatPreference());
        if (timeOffset > 0)
            uri.append("&timeOffset=").append(timeOffset);

        uri.append("&id=").append(id);

        Log.d(TAG, "getStreamUri: " + uri);

        return Uri.parse(uri.toString());
    }

    public static Uri getStreamUri(String id) {
        return getStreamUri(id, 0);
    }

    public static Uri updateStreamUri(Uri uri) {
        if (uri == null) return null;

        String scheme = uri.getScheme();
        // If it is local (content:// or file://), return it IMMEDIATELY.
        // This prevents the code below from appending &maxBitRate to a local path.
        if (scheme != null && (scheme.equals("content") || scheme.equals("file"))) {
            return uri;
        }
        
        String s = uri.toString();

        Matcher m1 = BITRATE_PATTERN.matcher(s);
        s = m1.replaceAll("");
        Matcher m2 = FORMAT_PATTERN.matcher(s);
        s = m2.replaceAll("");

        if (!Preferences.isServerPrioritized())
            s += "&maxBitRate=" + getBitratePreference();
        if (!Preferences.isServerPrioritized())
            s += "&format=" + getTranscodingFormatPreference();

        return Uri.parse(s);
    }

    public static String getReadableDurationString(Long duration, boolean millis) {
        long lenght = duration != null ? duration : 0;

        long minutes;
        long seconds;

        if (millis) {
            minutes = (lenght / 1000) / 60;
            seconds = (lenght / 1000) % 60;
        } else {
            minutes = lenght / 60;
            seconds = lenght % 60;
        }

        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%01d:%02d", minutes, seconds);
        } else {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
    }

    public static String getReadableDurationString(Integer duration, boolean millis) {
        long lenght = duration != null ? duration : 0;
        return getReadableDurationString(lenght, millis);
    }

    public static String getReadableString(String string) {
        if (string != null) {
            return Html.fromHtml(string, Html.FROM_HTML_MODE_COMPACT).toString();
        }

        return "";
    }

    public static String passwordHexEncoding(String plainPassword) {
        return "enc:" + plainPassword.chars().mapToObj(Integer::toHexString).collect(Collectors.joining());
    }

    public static String getBitratePreference() {
        Network network = getConnectivityManager().getActiveNetwork();
        NetworkCapabilities networkCapabilities = getConnectivityManager().getNetworkCapabilities(network);
        String audioTranscodeFormat = getTranscodingFormatPreference();

        if (audioTranscodeFormat.equals("raw") || network == null || networkCapabilities == null)
            return "0";

        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Preferences.getMaxBitrateWifi();
        } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return Preferences.getMaxBitrateMobile();
        } else {
            return Preferences.getMaxBitrateWifi();
        }
    }

    public static String getTranscodingFormatPreference() {
        Network network = getConnectivityManager().getActiveNetwork();
        NetworkCapabilities networkCapabilities = getConnectivityManager().getNetworkCapabilities(network);

        if (network == null || networkCapabilities == null) return "raw";

        String format;
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            format = Preferences.getAudioTranscodeFormatWifi();
            Log.d(TAG, "DEBUG: Using WIFI Format: " + format);
        } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            format = Preferences.getAudioTranscodeFormatMobile();
            Log.d(TAG, "DEBUG: Using MOBILE Format: " + format);
        } else {
            format = Preferences.getAudioTranscodeFormatWifi();
        }
        return format;
    }

    private static ConnectivityManager getConnectivityManager() {
        return (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }
}