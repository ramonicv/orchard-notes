package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.cloudkit.SessionExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncStatus(
    val syncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
)

/** The app's single source of truth for notes: the local cache, kept fresh from iCloud. */
class NotesRepository(
    private val dao: NotesDao,
    private val sync: NotesSync,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
) {
    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    private var syncJob: Job? = null

    fun folders(): Flow<List<FolderEntity>> = dao.observeFolders()
    fun folderCounts(): Flow<List<FolderCount>> = dao.observeFolderCounts()
    fun notesInFolder(folder: String): Flow<List<NoteSummary>> = dao.observeNotesInFolder(folder)
    fun allNotes(): Flow<List<NoteSummary>> = dao.observeAllNotes(SpecialFolders.TRASH)
    fun search(query: String): Flow<List<NoteSummary>> = dao.observeSearch(query, SpecialFolders.TRASH)
    fun note(recordName: String): Flow<NoteEntity?> = dao.observeNote(recordName)

    /** Starts a background sync unless one is already running. */
    fun requestSync(): Job {
        syncJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch { syncNow() }.also { syncJob = it }
    }

    suspend fun syncNow() {
        if (sessionManager.account == null) return
        _syncStatus.update { it.copy(syncing = true, error = null) }
        try {
            sync.sync()
            _syncStatus.update { SyncStatus(syncing = false, lastSyncedAt = System.currentTimeMillis()) }
        } catch (e: SessionExpiredException) {
            sessionManager.markExpired()
            _syncStatus.update { it.copy(syncing = false, error = "Your iCloud session expired. Sign in again to sync.") }
        } catch (e: Exception) {
            _syncStatus.update { it.copy(syncing = false, error = e.message ?: "Sync failed.") }
        }
    }

    suspend fun clear() {
        syncJob?.cancel()
        sync.reset()
        _syncStatus.value = SyncStatus()
    }
}
