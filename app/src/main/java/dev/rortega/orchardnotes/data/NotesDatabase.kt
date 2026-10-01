package dev.rortega.orchardnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NoteEntity::class, FolderEntity::class], version = 1, exportSchema = true)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notesDao(): NotesDao

    companion object {
        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
                // The database is only a cache of iCloud; rebuilding it is always safe.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
