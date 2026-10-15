package com.example.notepad.data

import androidx.lifecycle.LiveData

class FolderRepository(private val folderDao: FolderDao, private val noteDao: NoteDao) {

    fun observeAll(): LiveData<List<Folder>> = folderDao.observeAll()

    suspend fun getAll(): List<Folder> = folderDao.getAll()

    suspend fun insert(name: String): Long = folderDao.insert(Folder(name = name))

    suspend fun rename(folder: Folder, newName: String) = folderDao.update(folder.copy(name = newName))

    /** Notes filed under this folder fall back to "no folder" rather than being deleted. */
    suspend fun delete(folder: Folder) {
        noteDao.clearFolder(folder.id)
        folderDao.delete(folder.id)
    }
}
