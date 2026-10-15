package com.example.notepad.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface FolderDao {

    @Insert
    suspend fun insert(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun observeAll(): LiveData<List<Folder>>

    @Query("SELECT * FROM folders ORDER BY name ASC")
    suspend fun getAll(): List<Folder>
}
