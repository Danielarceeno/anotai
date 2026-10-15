package com.example.notepad.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-created category notes can be filed under (the "no folder" case is folderId = 0). */
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)
