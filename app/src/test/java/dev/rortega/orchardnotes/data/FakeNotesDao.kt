package dev.rortega.orchardnotes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory NotesDao covering what the write path uses. */
class FakeNotesDao : NotesDao {
    val notes = linkedMapOf<String, NoteEntity>()
    val pending = linkedMapOf<String, PendingEditEntity>()
    private val pendingFlow = MutableStateFlow(0)

    override suspend fun upsertNotes(notes: List<NoteEntity>) = notes.forEach { this.notes[it.recordName] = it }
    override suspend fun upsertFolders(folders: List<FolderEntity>) = Unit
    override suspend fun deleteNotes(recordNames: List<String>) = recordNames.forEach { notes.remove(it) }
    override suspend fun deleteFolders(recordNames: List<String>) = Unit
    override suspend fun clearNotes() = notes.clear()
    override suspend fun clearNotesWithoutPendingEdits() {
        notes.keys.retainAll(pending.keys)
    }
    override suspend fun clearFolders() = Unit
    override suspend fun overlayPendingEdits() {
        pending.values.forEach { p -> notes[p.recordName]?.let { notes[p.recordName] = it.copy(title = p.title, snippet = p.snippet, plainText = p.plainText) } }
    }
    override suspend fun upsertPending(edit: PendingEditEntity) {
        pending[edit.recordName] = edit
        pendingFlow.value = pending.size
    }
    override suspend fun getPending(recordName: String) = pending[recordName]
    override fun observePending(recordName: String): Flow<PendingEditEntity?> = pendingFlow.map { pending[recordName] }
    override suspend fun pushablePending() = pending.values.filter { !it.blocked }.sortedBy { it.updatedAt }
    override fun observePendingCount(): Flow<Int> = pendingFlow
    override suspend fun deletePending(recordName: String) {
        pending.remove(recordName)
        pendingFlow.value = pending.size
    }
    override suspend fun deletePendingIfUnchanged(recordName: String, updatedAt: Long): Int {
        if (pending[recordName]?.updatedAt != updatedAt) return 0
        deletePending(recordName)
        return 1
    }
    override suspend fun markPending(recordName: String, error: String?, blocked: Boolean) {
        pending[recordName]?.let { pending[recordName] = it.copy(error = error, blocked = blocked) }
    }
    override suspend fun rebasePending(recordName: String, baseJson: String) {
        pending[recordName]?.let { pending[recordName] = it.copy(isNew = false, baseJson = baseJson) }
    }
    override suspend fun clearPending() = pending.clear()
    override fun observeNote(recordName: String): Flow<NoteEntity?> = throw UnsupportedOperationException()
    override suspend fun getNote(recordName: String) = notes[recordName]
    override fun observeNotesInFolder(folder: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeAllNotes(trash: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeSearch(query: String, trash: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeFolders(): Flow<List<FolderEntity>> = throw UnsupportedOperationException()
    override fun observeFolderCounts(): Flow<List<FolderCount>> = throw UnsupportedOperationException()
}
