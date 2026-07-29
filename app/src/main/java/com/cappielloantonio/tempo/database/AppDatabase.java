package com.cappielloantonio.tempo.database;

import androidx.media3.common.util.UnstableApi;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.converter.DateConverters;
import com.cappielloantonio.tempo.database.converter.StringListConverter;
import com.cappielloantonio.tempo.database.dao.QueueDao;
import com.cappielloantonio.tempo.database.dao.SessionMediaItemDao;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.model.SessionMediaItem;

/**
 * Version 1 is the first schema number this app has ever had that means
 * anything. The twenty-five versions and fifteen auto-migrations that preceded
 * it were inherited from upstream tempo, whose applicationId differs from this
 * fork's -- Android treats that as a different app, so none of those migrations
 * could ever have run here. See
 * docs/decisions/2026-07-28-room-schema-reset-design.md.
 *
 * There is deliberately no fallbackToDestructiveMigration. Real migrations are
 * written from here on, and that only works if forgetting one is noticeable:
 * with the fallback, a missed migration silently drops the database and
 * presents as the saved queue having emptied itself, which looks like a
 * playback bug and is nowhere near its cause. Without it, Room throws when it
 * opens a database whose version has no path -- on the developer's own device,
 * the first time they run the change.
 *
 * fallbackToDestructiveMigrationOnDowngrade is absent for the same reason. It
 * does not bite on the move to version 1 -- the database file is renamed below,
 * so there is no older database to downgrade from -- but it governs what
 * happens later: checking out a branch whose schema version is lower than the
 * database on the device crashes on open, and because this database is reached
 * through the media service that presents as a service restart loop rather than
 * a dialog.
 */
@UnstableApi
@Database(
        version = 1,
        entities = {
            Queue.class,
            SessionMediaItem.class,
        }
)
@TypeConverters({DateConverters.class, StringListConverter.class})
public abstract class AppDatabase extends RoomDatabase {
    // Renamed from the inherited "tempo_db". A different filename is a
    // different database, so version 1 opens a file that does not exist yet and
    // Room creates it fresh -- which is what makes the collapse to version 1
    // safe on a device that already has data, with no uninstall and no
    // destructive fallback. Any old tempo_db is left orphaned on purpose:
    // cleaning it up would mean a deleteDatabase call living here forever to
    // tidy a file that exists on one emulator.
    private final static String DB_NAME = "siskin_db";
    private static AppDatabase instance;

    public static synchronized AppDatabase getInstance() {
        if (instance == null) {
            instance = Room.databaseBuilder(App.getContext(), AppDatabase.class, DB_NAME)
                    .build();
        }

        return instance;
    }

    public abstract QueueDao queueDao();

    public abstract SessionMediaItemDao sessionMediaItemDao();
}
