package com.cappielloantonio.tempo.subsonic;

import com.cappielloantonio.tempo.subsonic.api.albumsonglist.AlbumSongListClient;
import com.cappielloantonio.tempo.subsonic.api.browsing.BrowsingClient;
import com.cappielloantonio.tempo.subsonic.api.mediaannotation.MediaAnnotationClient;
import com.cappielloantonio.tempo.subsonic.api.mediaretrieval.MediaRetrievalClient;
import com.cappielloantonio.tempo.subsonic.api.playlist.PlaylistClient;
import com.cappielloantonio.tempo.subsonic.api.searching.SearchingClient;
import com.cappielloantonio.tempo.subsonic.api.system.SystemClient;
import com.cappielloantonio.tempo.subsonic.base.Version;

import java.util.HashMap;
import java.util.Map;

public class Subsonic {
    private static final Version API_MAX_VERSION = Version.of("1.15.0");

    private final Version apiVersion = API_MAX_VERSION;
    private final SubsonicPreferences preferences;

    private SystemClient systemClient;
    private BrowsingClient browsingClient;
    private MediaRetrievalClient mediaRetrievalClient;
    private PlaylistClient playlistClient;
    private SearchingClient searchingClient;
    private AlbumSongListClient albumSongListClient;
    private MediaAnnotationClient mediaAnnotationClient;

    public Subsonic(SubsonicPreferences preferences) {
        this.preferences = preferences;
    }

    public Version getApiVersion() {
        return apiVersion;
    }

    public SystemClient getSystemClient() {
        if (systemClient == null) {
            systemClient = new SystemClient(this);
        }
        return systemClient;
    }

    public BrowsingClient getBrowsingClient() {
        if (browsingClient == null) {
            browsingClient = new BrowsingClient(this);
        }
        return browsingClient;
    }

    public MediaRetrievalClient getMediaRetrievalClient() {
        if (mediaRetrievalClient == null) {
            mediaRetrievalClient = new MediaRetrievalClient(this);
        }
        return mediaRetrievalClient;
    }

    public PlaylistClient getPlaylistClient() {
        if (playlistClient == null) {
            playlistClient = new PlaylistClient(this);
        }
        return playlistClient;
    }

    public SearchingClient getSearchingClient() {
        if (searchingClient == null) {
            searchingClient = new SearchingClient(this);
        }
        return searchingClient;
    }

    public AlbumSongListClient getAlbumSongListClient() {
        if (albumSongListClient == null) {
            albumSongListClient = new AlbumSongListClient(this);
        }
        return albumSongListClient;
    }

    public MediaAnnotationClient getMediaAnnotationClient() {
        if (mediaAnnotationClient == null) {
            mediaAnnotationClient = new MediaAnnotationClient(this);
        }
        return mediaAnnotationClient;
    }

    public String getUrl() {
        String url = preferences.getServerUrl() + "/rest/";
        return url.replace("//rest", "/rest");
    }

    public Map<String, String> getParams() {
        Map<String, String> params = new HashMap<>();
        params.put("u", preferences.getUsername());

        // Null until a server has been configured. On phones this is unreachable —
        // setup gates the UI — but Android Automotive OS exposes the media browse
        // tree straight after install, so the car can request a library before any
        // credentials exist. Elsewhere (App.java) this accessor is already
        // null-checked; this path was the outlier and crashed the media service.
        SubsonicPreferences.SubsonicAuthentication authentication = preferences.getAuthentication();

        if (authentication != null) {
            if (authentication.getPassword() != null)
                params.put("p", authentication.getPassword());
            if (authentication.getSalt() != null)
                params.put("s", authentication.getSalt());
            if (authentication.getToken() != null)
                params.put("t", authentication.getToken());
        }

        params.put("v", getApiVersion().getVersionString());
        params.put("c", preferences.getClientName());
        params.put("f", "json");

        return params;
    }
}
