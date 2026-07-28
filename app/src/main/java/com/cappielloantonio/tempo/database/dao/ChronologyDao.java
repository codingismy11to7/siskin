package com.cappielloantonio.tempo.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;

import com.cappielloantonio.tempo.model.Chronology;

@Dao
public interface ChronologyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Chronology chronologyObject);
}