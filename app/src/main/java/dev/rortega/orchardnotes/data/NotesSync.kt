package dev.rortega.orchardnotes.data

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.CloudKitServerException
import dev.rortega.orchardnotes.cloudkit.CkRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Incremental sync of the Notes zone into the local cache, using CloudKit's
 * `changes/zone` sync-token model (the same one the web client uses).
 */
class NotesSync(
    private val cloudKit: CloudKitClient,
    private val dao: NotesDao,
    private val prefs: SharedPreferences,
) {
    private val mutex = Mutex()

    /** Pulls every change since the last sync. Returns the number of records applied. */
    suspend fun sync(): Int = mutex.withLock {
        val storedToken = prefs.getString(KEY_SYNC_TOKEN, null)
        try {
            pull(storedToken)
        } catch (e: CloudKitServerException) {
            // An unusable sync token surfaces as a zone-level BAD_REQUEST: refetch from scratch.
            if (storedToken == null || e.serverErrorCode != "BAD_REQUEST") throw e
            prefs.edit { remove(KEY_SYNC_TOKEN) }
            dao.clearAll()
            pull(null)
        }
    }

    /** Applies records that came back from our own writes, without waiting for the next sync. */
    suspend fun applyRecords(records: List<CkRecord>) = apply(records)

    suspend fun reset() = mutex.withLock {
        prefs.edit { remove(KEY_SYNC_TOKEN) }
        dao.clearAll()
    }

    private suspend fun pull(startToken: String?): Int {
        var token = startToken
        var applied = 0
        do {
            val page = cloudKit.changesZone(token)
            apply(page.records)
            applied += page.records.size
            if (page.moreComing && (page.syncToken == null || page.syncToken == token)) {
                throw IllegalStateException("CloudKit reported more changes without advancing the sync token")
            }
            token = page.syncToken ?: token
            // Safe to persist per page: everything up to this token is already in the cache.
            prefs.edit { putString(KEY_SYNC_TOKEN, token) }
        } while (page.moreComing)
        return applied
    }

    private suspend fun apply(records: List<CkRecord>) {
        val notes = mutableListOf<NoteEntity>()
        val folders = mutableListOf<FolderEntity>()
        val deleted = mutableListOf<String>()
        for (record in records) {
            when {
                record.deleted -> deleted += record.recordName
                record.recordType == "Note" || record.recordType == "PasswordProtectedNote" ->
                    if (NoteRecords.isPurged(record)) deleted += record.recordName else notes += NoteRecords.toNoteEntity(record)
                record.recordType == "Folder" ->
                    if ((record.long("Deleted") ?: 0L) != 0L) deleted += record.recordName else folders += NoteRecords.toFolderEntity(record)
            }
        }
        dao.applyChanges(notes, folders, deleted)
    }

    private companion object {
        const val KEY_SYNC_TOKEN = "notes_zone_sync_token"
    }
}
