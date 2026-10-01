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

    @Query("DELETE FROM notes WHERE recordName NOT IN (SELECT recordName FROM pending_edits)")
    suspend fun clearNotesWithoutPendingEdits()

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
        overlayPendingEdits()
        overlayPendingMoves()
        deleteNotesPendingPurge()
    }

    @Query(
        "UPDATE notes SET folderRecordName = (SELECT o.folderRecordName FROM pending_ops o " +
            "WHERE o.type = 'MOVE' AND o.recordName = notes.recordName) " +
            "WHERE recordName IN (SELECT recordName FROM pending_ops WHERE type = 'MOVE')",
    )
    suspend fun overlayPendingMoves()

    @Query("DELETE FROM notes WHERE recordName IN (SELECT recordName FROM pending_ops WHERE type = 'PURGE')")
    suspend fun deleteNotesPendingPurge()

    @Upsert
    suspend fun upsertOp(op: PendingOpEntity)

    @Query("SELECT * FROM pending_ops WHERE error IS NULL ORDER BY createdAt")
    suspend fun pushableOps(): List<PendingOpEntity>

    @Query("DELETE FROM pending_ops WHERE type = :type AND recordName = :recordName AND createdAt = :createdAt")
    suspend fun deleteOpIfUnchanged(type: String, recordName: String, createdAt: Long): Int

    @Query("DELETE FROM pending_ops WHERE recordName = :recordName")
    suspend fun deleteOpsFor(recordName: String)

    @Query("UPDATE pending_ops SET error = :error WHERE type = :type AND recordName = :recordName")
    suspend fun markOp(type: String, recordName: String, error: String?)

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeOpCount(): Flow<Int>

    @Query("UPDATE notes SET folderRecordName = :folder WHERE recordName = :recordName")
    suspend fun setNoteFolder(recordName: String, folder: String)

    @Query("UPDATE pending_edits SET folderRecordName = :folder WHERE recordName = :recordName")
    suspend fun setPendingFolder(recordName: String, folder: String)

    /** Keeps list rows showing local, not-yet-pushed edits after server data lands. */
    @Query(
        "UPDATE notes SET " +
            "title = (SELECT p.title FROM pending_edits p WHERE p.recordName = notes.recordName), " +
            "snippet = (SELECT p.snippet FROM pending_edits p WHERE p.recordName = notes.recordName), " +
            "plainText = (SELECT p.plainText FROM pending_edits p WHERE p.recordName = notes.recordName), " +
            "modificationDate = MAX(modificationDate, (SELECT p.updatedAt FROM pending_edits p WHERE p.recordName = notes.recordName)) " +
            "WHERE recordName IN (SELECT recordName FROM pending_edits)",
    )
    suspend fun overlayPendingEdits()

    @Upsert
    suspend fun upsertPending(edit: PendingEditEntity)

    @Query("SELECT * FROM pending_edits WHERE recordName = :recordName")
    suspend fun getPending(recordName: String): PendingEditEntity?

    @Query("SELECT * FROM pending_edits WHERE recordName = :recordName")
    fun observePending(recordName: String): Flow<PendingEditEntity?>

    @Query("SELECT * FROM pending_edits WHERE blocked = 0 ORDER BY updatedAt")
    suspend fun pushablePending(): List<PendingEditEntity>

    @Query("SELECT COUNT(*) FROM pending_edits")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM pending_edits WHERE recordName = :recordName")
    suspend fun deletePending(recordName: String)

    /** Removes a pending edit only if no newer local edit replaced it while it was being pushed. */
    @Query("DELETE FROM pending_edits WHERE recordName = :recordName AND updatedAt = :updatedAt")
    suspend fun deletePendingIfUnchanged(recordName: String, updatedAt: Long): Int

    @Query("UPDATE pending_edits SET error = :error, blocked = :blocked WHERE recordName = :recordName")
    suspend fun markPending(recordName: String, error: String?, blocked: Boolean)

    /** After a push, newer local edits continue from the version that was just pushed. */
    @Query("UPDATE pending_edits SET isNew = 0, baseJson = :baseJson WHERE recordName = :recordName")
    suspend fun rebasePending(recordName: String, baseJson: String)

    @Query("DELETE FROM pending_edits")
    suspend fun clearPending()

    @Query("DELETE FROM pending_ops")
    suspend fun clearOps()

    @Query("DELETE FROM folders WHERE recordName NOT IN (SELECT recordName FROM pending_ops WHERE type = 'CREATE_FOLDER')")
    suspend fun clearFoldersWithoutPendingCreates()

    /** Clears the server cache. Pending local edits are kept unless [includingPending]. */
    @Transaction
    suspend fun clearAll(includingPending: Boolean = false) {
        if (includingPending) {
            clearPending()
            clearOps()
            clearNotes()
            clearFolders()
        } else {
            clearNotesWithoutPendingEdits()
            clearFoldersWithoutPendingCreates()
        }
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
