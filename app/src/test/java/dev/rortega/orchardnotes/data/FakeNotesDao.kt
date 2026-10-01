package dev.rortega.orchardnotes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory NotesDao covering what the write path uses. */
class FakeNotesDao : NotesDao {
    val notes = linkedMapOf<String, NoteEntity>()
    val pending = linkedMapOf<String, PendingEditEntity>()
    private val pendingFlow = MutableStateFlow(0)
    private val notesFlow = MutableStateFlow(0)

    override suspend fun upsertNotes(notes: List<NoteEntity>) {
        notes.forEach { this.notes[it.recordName] = it }
        notesFlow.value++
    }
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
    val ops = linkedMapOf<Pair<String, String>, PendingOpEntity>()
    override suspend fun overlayPendingMoves() = Unit
    override suspend fun deleteNotesPendingPurge() = Unit
    override suspend fun upsertOp(op: PendingOpEntity) {
        ops[op.type to op.recordName] = op
    }
    override suspend fun pushableOps() = ops.values.filter { it.error == null }.sortedBy { it.createdAt }
    override suspend fun deleteOpIfUnchanged(type: String, recordName: String, createdAt: Long): Int =
        if (ops[type to recordName]?.createdAt == createdAt) 1.also { ops.remove(type to recordName) } else 0
    override suspend fun deleteOpsFor(recordName: String) {
        ops.keys.removeAll { it.second == recordName }
    }
    override suspend fun markOp(type: String, recordName: String, error: String?) {
        ops[type to recordName]?.let { ops[type to recordName] = it.copy(error = error) }
    }
    override fun observeOpCount(): Flow<Int> = MutableStateFlow(0)
    override suspend fun setNoteFolder(recordName: String, folder: String) {
        notes[recordName]?.let { notes[recordName] = it.copy(folderRecordName = folder) }
    }
    override suspend fun setPendingFolder(recordName: String, folder: String) {
        pending[recordName]?.let { pending[recordName] = it.copy(folderRecordName = folder) }
    }
    override suspend fun clearOps() = ops.clear()
    override suspend fun clearFoldersWithoutPendingCreates() = Unit
    override fun observeNote(recordName: String): Flow<NoteEntity?> = notesFlow.map { notes[recordName] }
    override suspend fun getNote(recordName: String) = notes[recordName]
    override fun observeNotesInFolder(folder: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeAllNotes(trash: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeSearch(query: String, trash: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeFolders(): Flow<List<FolderEntity>> = throw UnsupportedOperationException()
    override fun observeFolderCounts(): Flow<List<FolderCount>> = throw UnsupportedOperationException()
}
