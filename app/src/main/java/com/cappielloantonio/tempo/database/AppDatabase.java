package com.cappielloantonio.tempo.database;

import androidx.media3.common.util.UnstableApi;
import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.DeleteColumn;
import androidx.room.DeleteTable;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.AutoMigrationSpec;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.converter.DateConverters;
import com.cappielloantonio.tempo.database.converter.StringListConverter;
import com.cappielloantonio.tempo.database.dao.QueueDao;
import com.cappielloantonio.tempo.database.dao.SessionMediaItemDao;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.model.SessionMediaItem;

// Version 24 deliberately has no @AutoMigration entry: queue and
// session_media_item are rebuilt around Plex rating keys and part keys, which is
// far past what Room can derive. getInstance() below falls back to a destructive
// migration, and that is the intent -- a queue of Subsonic ids is meaningless
// against a Plex server, so there is nothing worth carrying across.
//
// Version 25 then drops parent_rating_key from both tables, a column nothing
// ever read back. That is a version bump rather than an edit of 24 in place
// because Room hashes the schema and refuses to open a database whose hash does
// not match -- fallbackToDestructiveMigration below only covers a *version* it
// has no path for, so reshaping 24 while leaving the number alone crashes on
// open for every install that already has a version-24 database, which on this
// branch is every developer who has run it.
@UnstableApi
@Database(
        version = 25,
        entities = {
            Queue.class,
            SessionMediaItem.class,
        },
        autoMigrations = {
                @AutoMigration(from = 10, to = 11),
                @AutoMigration(from = 11, to = 12),
                @AutoMigration(from = 12, to = 13),
                @AutoMigration(from = 13, to = 14),
                @AutoMigration(from = 14, to = 15),
                @AutoMigration(from = 15, to = 16),
                @AutoMigration(from = 16, to = 17),
                @AutoMigration(from = 17, to = 18),
                @AutoMigration(from = 18, to = 19),
                @AutoMigration(from = 19, to = 20),
                @AutoMigration(from = 20, to = 21, spec = AppDatabase.DropTablesForPrunedFeatures.class),
                @AutoMigration(from = 21, to = 22, spec = AppDatabase.DropPlaylistTables.class),
                @AutoMigration(from = 22, to = 23, spec = AppDatabase.DropServerTable.class),
                @AutoMigration(from = 24, to = 25, spec = AppDatabase.DropParentRatingKey.class),
        }
)
@TypeConverters({DateConverters.class, StringListConverter.class})
public abstract class AppDatabase extends RoomDatabase {
    private final static String DB_NAME = "tempo_db";
    private static AppDatabase instance;

    // The download, recent-search, favorite, lyrics-cache and internet-radio-station-cache
    // features were pruned along with their models and DAOs; this migration drops their
    // now-orphaned tables instead of retroactively rewriting the version-20 schema.
    @DeleteTable.Entries({
            @DeleteTable(tableName = "download"),
            @DeleteTable(tableName = "recent_search"),
            @DeleteTable(tableName = "favorite"),
            @DeleteTable(tableName = "lyrics_cache"),
            @DeleteTable(tableName = "internet_radio_station_cache"),
            @DeleteTable(tableName = "playlist_song"),
    })
    static class DropTablesForPrunedFeatures implements AutoMigrationSpec {
    }

    // The playlist and pinned_playlist tables outlived PlaylistRepository because their
    // entities lived under subsonic/models rather than model/, so the sweep above did not
    // reach them; this migration drops their now-orphaned tables.
    @DeleteTable.Entries({
            @DeleteTable(tableName = "playlist"),
            @DeleteTable(tableName = "pinned_playlist")
    })
    static class DropPlaylistTables implements AutoMigrationSpec {
    }

    // The server table held the Subsonic multi-server list, entered through a form
    // that no longer exists: Plex authenticates through a PIN and stores its token,
    // server URI and section key in preferences instead.
    @DeleteTable.Entries({
            @DeleteTable(tableName = "server")
    })
    static class DropServerTable implements AutoMigrationSpec {
    }

    // parent_rating_key was persisted on both tables and never read back: the
    // readers its constant named (Chronology, MappingUtil) were Subsonic-era and
    // are gone. Dropped rather than left in place because a column nothing reads
    // is a schema claim that a track's album id matters, which it does not --
    // and unlike a destructive rebuild this keeps the saved queue.
    @DeleteColumn.Entries({
            @DeleteColumn(tableName = "queue", columnName = "parent_rating_key"),
            @DeleteColumn(tableName = "session_media_item", columnName = "parent_rating_key")
    })
    static class DropParentRatingKey implements AutoMigrationSpec {
    }

    public static synchronized AppDatabase getInstance() {
        if (instance == null) {
            instance = Room.databaseBuilder(App.getContext(), AppDatabase.class, DB_NAME)
                    .fallbackToDestructiveMigration()
                    .build();
        }

        return instance;
    }

    public abstract QueueDao queueDao();

    public abstract SessionMediaItemDao sessionMediaItemDao();
}
