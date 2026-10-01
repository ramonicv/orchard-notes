package dev.rortega.orchardnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class, FolderEntity::class, PendingEditEntity::class], version = 2, exportSchema = true)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notesDao(): NotesDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_edits` (`recordName` TEXT NOT NULL, `isNew` INTEGER NOT NULL, " +
                        "`folderRecordName` TEXT, `baseJson` TEXT, `desiredJson` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`snippet` TEXT NOT NULL, `plainText` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `error` TEXT, " +
                        "`blocked` INTEGER NOT NULL, PRIMARY KEY(`recordName`))",
                )
            }
        }

        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
