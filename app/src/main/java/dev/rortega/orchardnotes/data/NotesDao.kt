package dev.rortega.orchardnotes.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val SUMMARY_COLUMNS =
    "recordName, folderRecordName, title, snippet, modificationDate, isPinned, isLocked, firstAttachmentUti"

@Dao
interface NotesDao {
    @Upsert
    suspend fun upsertNotes(notes: List<NoteEntity>)

    @Upsert
    suspend fun upsertFolders(folders: List<FolderEntity>)

    @Query("DELETE FROM notes WHERE recordName IN (:recordNames)")
    suspend fun deleteNotes(recordNames: List<String>)

    @Query("DELETE FROM folders WHERE recordName IN (:recordNames)")
    suspend fun deleteFolders(recordNames: List<String>)

    @Query("DELETE FROM notes")
    suspend fun clearNotes()

    @Query("DELETE FROM folders")
    suspend fun clearFolders()

    @Transaction
    suspend fun applyChanges(
        notes: List<NoteEntity>,
        folders: List<FolderEntity>,
        deletedRecordNames: List<String>,
    ) {
        if (deletedRecordNames.isNotEmpty()) {
            deletedRecordNames.chunked(500).forEach {
                deleteNotes(it)
                deleteFolders(it)
            }
        }
        if (folders.isNotEmpty()) upsertFolders(folders)
        if (notes.isNotEmpty()) upsertNotes(notes)
    }

    @Transaction
    suspend fun clearAll() {
        clearNotes()
        clearFolders()
    }

    @Query("SELECT * FROM notes WHERE recordName = :recordName")
    fun observeNote(recordName: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE recordName = :recordName")
    suspend fun getNote(recordName: String): NoteEntity?

    @Query("SELECT $SUMMARY_COLUMNS FROM notes WHERE folderRecordName = :folder ORDER BY isPinned DESC, modificationDate DESC")
    fun observeNotesInFolder(folder: String): Flow<List<NoteSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM notes WHERE folderRecordName IS NOT :trash ORDER BY isPinned DESC, modificationDate DESC")
    fun observeAllNotes(trash: String): Flow<List<NoteSummary>>

    @Query(
        "SELECT $SUMMARY_COLUMNS FROM notes WHERE folderRecordName IS NOT :trash " +
            "AND (title LIKE '%' || :query || '%' OR plainText LIKE '%' || :query || '%') " +
            "ORDER BY isPinned DESC, modificationDate DESC",
    )
    fun observeSearch(query: String, trash: String): Flow<List<NoteSummary>>

    @Query("SELECT * FROM folders ORDER BY title COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT folderRecordName, COUNT(*) AS count FROM notes GROUP BY folderRecordName")
    fun observeFolderCounts(): Flow<List<FolderCount>>
}
