package dev.rortega.orchardnotes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory NotesDao covering what the write and sync paths use. */
class FakeNotesDao : NotesDao {
    val notes = linkedMapOf<String, NoteEntity>()
    val folders = linkedMapOf<String, FolderEntity>()
    val shares = linkedMapOf<String, ShareEntity>()
    val pending = linkedMapOf<String, PendingEditEntity>()
    private val pendingFlow = MutableStateFlow(0)
    private val notesFlow = MutableStateFlow(0)
    private val foldersFlow = MutableStateFlow(0)

    override suspend fun upsertNotes(notes: List<NoteEntity>) {
        notes.forEach { this.notes[it.recordName] = it }
        notesFlow.value++
    }
    override suspend fun upsertFolders(folders: List<FolderEntity>) {
        folders.forEach { this.folders[it.recordName] = it }
        foldersFlow.value++
    }
    override suspend fun deleteNotes(recordNames: List<String>) {
        recordNames.forEach { notes.remove(it) }
        notesFlow.value++
    }
    override suspend fun deleteFolders(recordNames: List<String>) = recordNames.forEach { folders.remove(it) }
    override suspend fun clearNotes() = notes.clear()
    override suspend fun clearNotesWithoutPendingEdits() {
        notes.keys.retainAll(pending.keys)
    }
    override suspend fun clearFolders() = folders.clear()
    override suspend fun upsertShares(shares: List<ShareEntity>) {
        shares.forEach { this.shares[it.recordName] = it }
        foldersFlow.value++
    }
    override suspend fun deleteNotesInZone(recordNames: List<String>, zoneOwner: String?) {
        recordNames.forEach { name -> if (notes[name]?.zoneOwner == zoneOwner) notes.remove(name) }
        notesFlow.value++
    }
    override suspend fun deleteFoldersInZone(recordNames: List<String>, zoneOwner: String?) =
        recordNames.forEach { name -> if (folders[name]?.zoneOwner == zoneOwner) folders.remove(name) }
    override suspend fun deleteSharesInZone(recordNames: List<String>, zoneOwner: String?) =
        recordNames.forEach { name -> if (shares[name]?.zoneOwner == zoneOwner) shares.remove(name) }
    override suspend fun sharedZoneOwners(): List<String> =
        (notes.values.mapNotNull { it.zoneOwner } + folders.values.mapNotNull { it.zoneOwner } + shares.values.mapNotNull { it.zoneOwner }).distinct()
    override suspend fun noteNamesInZone(zoneOwner: String?) = notes.values.filter { it.zoneOwner == zoneOwner }.map { it.recordName }
    override suspend fun folderKeysInZone(zoneOwner: String?) = folders.values.filter { it.zoneOwner == zoneOwner }.map { it.recordName }
    override suspend fun deletePendingInZone(zoneOwner: String) {
        pending.keys.removeAll { notes[it]?.zoneOwner == zoneOwner }
        pendingFlow.value++
    }
    override suspend fun deleteOpsInZone(zoneOwner: String) {
        ops.keys.removeAll { notes[it.second]?.zoneOwner == zoneOwner }
    }
    override suspend fun deleteAllNotesInZone(zoneOwner: String) {
        notes.values.removeAll { it.zoneOwner == zoneOwner }
        notesFlow.value++
    }
    override suspend fun deleteAllFoldersInZone(zoneOwner: String) {
        folders.values.removeAll { it.zoneOwner == zoneOwner }
    }
    override suspend fun deleteAllSharesInZone(zoneOwner: String) {
        shares.values.removeAll { it.zoneOwner == zoneOwner }
    }
    override suspend fun clearShares() = shares.clear()
    override suspend fun getFolder(recordName: String) = folders[recordName]
    override fun observeShares(): Flow<List<ShareEntity>> = foldersFlow.map { shares.values.toList() }
    override suspend fun getShare(recordName: String) = shares[recordName]
    override suspend fun overlayPendingEdits() {
        pending.values.forEach { p -> notes[p.recordName]?.let { notes[p.recordName] = it.copy(title = p.title, snippet = p.snippet, plainText = p.plainText) } }
    }
    override suspend fun upsertPending(edit: PendingEditEntity) {
        pending[edit.recordName] = edit
        pendingFlow.value++
    }
    override suspend fun getPending(recordName: String) = pending[recordName]
    override fun observePending(recordName: String): Flow<PendingEditEntity?> = pendingFlow.map { pending[recordName] }
    override suspend fun pushablePending() = pending.values.filter { !it.blocked }.sortedBy { it.updatedAt }
    override fun observePendingCount(): Flow<Int> = pendingFlow.map { pending.size }
    override suspend fun deletePending(recordName: String) {
        pending.remove(recordName)
        pendingFlow.value++
    }
    override suspend fun deletePendingIfUnchanged(recordName: String, updatedAt: Long): Int {
        if (pending[recordName]?.updatedAt != updatedAt) return 0
        deletePending(recordName)
        return 1
    }
    override suspend fun markPending(recordName: String, error: String?, blocked: Boolean) {
        pending[recordName]?.let { pending[recordName] = it.copy(error = error, blocked = blocked) }
        pendingFlow.value++
    }
    override suspend fun rebasePending(recordName: String, baseJson: String) {
        pending[recordName]?.let { pending[recordName] = it.copy(isNew = false, baseJson = baseJson) }
        pendingFlow.value++
    }
    override suspend fun clearPending() {
        pending.clear()
        pendingFlow.value++
    }
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
    override suspend fun clearFoldersWithoutPendingCreates() {
        folders.keys.retainAll(ops.values.filter { it.type == PendingOpEntity.CREATE_FOLDER }.map { it.recordName }.toSet())
    }
    override fun observeNote(recordName: String): Flow<NoteEntity?> = notesFlow.map { notes[recordName] }
    override suspend fun getNote(recordName: String) = notes[recordName]
    override fun observeNotesInFolder(folder: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeAllNotes(trash: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeSearch(query: String, trash: String): Flow<List<NoteSummary>> = throw UnsupportedOperationException()
    override fun observeFolders(): Flow<List<FolderEntity>> = foldersFlow.map { folders.values.sortedBy { it.title.lowercase() } }
    override fun observeFolderCounts(): Flow<List<FolderCount>> = throw UnsupportedOperationException()
}
