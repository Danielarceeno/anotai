package com.example.notepad.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoteTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: NoteTombstone)

    @Query("DELETE FROM note_tombstones WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("SELECT * FROM note_tombstones")
    suspend fun getAll(): List<NoteTombstone>
}
