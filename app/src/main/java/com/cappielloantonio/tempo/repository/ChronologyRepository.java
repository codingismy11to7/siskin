package com.cappielloantonio.tempo.repository;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.ChronologyDao;
import com.cappielloantonio.tempo.model.Chronology;

public class ChronologyRepository {
    private final ChronologyDao chronologyDao = AppDatabase.getInstance().chronologyDao();

    public void insert(Chronology item) {
        InsertThreadSafe insert = new InsertThreadSafe(chronologyDao, item);
        Thread thread = new Thread(insert);
        thread.start();
    }

    private static class InsertThreadSafe implements Runnable {
        private final ChronologyDao chronologyDao;
        private final Chronology item;

        public InsertThreadSafe(ChronologyDao chronologyDao, Chronology item) {
            this.chronologyDao = chronologyDao;
            this.item = item;
        }

        @Override
        public void run() {
            chronologyDao.insert(item);
        }
    }
}
