package dev.rortega.orchardnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, FolderEntity::class, PendingEditEntity::class, PendingOpEntity::class, ShareEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notesDao(): NotesDao

    companion object {
        /** Shared notes: which zone notes and folders come from, and the shares themselves. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `zoneOwner` TEXT")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `shareRecordName` TEXT")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `syncedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `zoneOwner` TEXT")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `shareRecordName` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shares` (`recordName` TEXT NOT NULL, `zoneOwner` TEXT, `ownerName` TEXT, " +
                        "`permission` TEXT, `participantNames` TEXT NOT NULL, PRIMARY KEY(`recordName`))",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_ops` (`type` TEXT NOT NULL, `recordName` TEXT NOT NULL, " +
                        "`folderRecordName` TEXT, `title` TEXT, `parentRecordName` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "`error` TEXT, PRIMARY KEY(`type`, `recordName`))",
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
