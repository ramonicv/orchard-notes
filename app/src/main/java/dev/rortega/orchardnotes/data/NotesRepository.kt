package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.cloudkit.SessionExpiredException
import dev.rortega.orchardnotes.notes.NoteFields
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.NoteContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

data class SyncStatus(
    val syncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
    /** Local edits waiting to reach iCloud. */
    val pendingCount: Int = 0,
    /** True when the last attempt couldn't reach iCloud. */
    val offline: Boolean = false,
    /** A one-off message for the user (shown once, then cleared). */
    val notice: String? = null,
)

/**
 * The app's single source of truth for notes: the local cache, kept fresh from iCloud,
 * plus local edits that are saved immediately and pushed when iCloud is reachable.
 */
class NotesRepository(
    private val dao: NotesDao,
    private val sync: NotesSync,
    private val writer: NoteWriter,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
    /** Asks the system to push pending edits once a network connection is available. */
    private val schedulePushWhenOnline: () -> Unit = {},
) {
    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    private var syncJob: Job? = null
    private var pushJob: Job? = null
    private val pushMutex = Mutex()
    private val saveMutex = Mutex()

    init {
        scope.launch { dao.observePendingCount().collect { count -> _syncStatus.update { it.copy(pendingCount = count) } } }
    }

    fun folders(): Flow<List<FolderEntity>> = dao.observeFolders()
    fun folderCounts(): Flow<List<FolderCount>> = dao.observeFolderCounts()
    fun notesInFolder(folder: String): Flow<List<NoteSummary>> = dao.observeNotesInFolder(folder)
    fun allNotes(): Flow<List<NoteSummary>> = dao.observeAllNotes(SpecialFolders.TRASH)
    fun search(query: String): Flow<List<NoteSummary>> = dao.observeSearch(query, SpecialFolders.TRASH)
    fun note(recordName: String): Flow<NoteEntity?> = dao.observeNote(recordName)
    fun pendingEdit(recordName: String): Flow<PendingEditEntity?> = dao.observePending(recordName)

    fun decodeParagraphs(json: String): List<FormatParagraph> = ParagraphMerge.withOffsets(writer.decodeParagraphs(json))

    fun clearNotice() = _syncStatus.update { it.copy(notice = null) }

    // --- reading from iCloud ----------------------------------------------------

    /** Pushes pending edits, then pulls changes; unless a sync is already running. */
    fun requestSync(): Job {
        syncJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch { syncNow() }.also { syncJob = it }
    }

    suspend fun syncNow() {
        if (sessionManager.account == null) return
        _syncStatus.update { it.copy(syncing = true, error = null) }
        pushPending()
        try {
            sync.sync()
            _syncStatus.update { it.copy(syncing = false, offline = false, lastSyncedAt = System.currentTimeMillis()) }
        } catch (e: SessionExpiredException) {
            sessionManager.markExpired()
            _syncStatus.update { it.copy(syncing = false, error = "Your iCloud session expired. Sign in again to sync.") }
        } catch (e: java.io.IOException) {
            _syncStatus.update { it.copy(syncing = false, offline = true) }
        } catch (e: Exception) {
            _syncStatus.update { it.copy(syncing = false, error = e.message ?: "Sync failed.") }
        }
    }

    // --- local edits --------------------------------------------------------------

    /**
     * Saves [desired] as the note's new content, locally and immediately; the push to
     * iCloud follows shortly. [base] is the content the editor started from; it is only
     * recorded for the first unsynced edit, so merges always see the true starting point.
     */
    suspend fun saveDraft(
        recordName: String,
        base: List<FormatParagraph>?,
        desired: List<FormatParagraph>,
        newNoteFolder: String? = null,
    ) {
        val existing = dao.getPending(recordName)
        val text = desired.joinToString("\n") { it.text }
        val now = System.currentTimeMillis()
        val isNew = existing?.isNew ?: (newNoteFolder != null)
        val title = NoteFields.title(text).trim()
        val snippet = NoteFields.snippet(text)
        dao.upsertPending(
            PendingEditEntity(
                recordName = recordName,
                isNew = isNew,
                folderRecordName = existing?.folderRecordName ?: newNoteFolder,
                baseJson = existing?.baseJson ?: if (isNew) null else base?.let(writer::encodeParagraphs),
                desiredJson = writer.encodeParagraphs(desired),
                title = title,
                snippet = snippet,
                plainText = text,
                updatedAt = now,
                error = existing?.error,
                blocked = existing?.blocked ?: false,
            ),
        )
        if (isNew && dao.getNote(recordName) == null) {
            dao.upsertNotes(
                listOf(
                    NoteEntity(
                        recordName = recordName,
                        folderRecordName = newNoteFolder ?: SpecialFolders.DEFAULT,
                        title = title,
                        snippet = snippet,
                        plainText = text,
                        textData = null,
                        creationDate = now,
                        modificationDate = now,
                        isPinned = false,
                        recordChangeTag = null,
                        firstAttachmentUti = null,
                        isLocked = false,
                        bodyUnavailable = false,
                    ),
                ),
            )
        }
        dao.overlayPendingEdits()
        schedulePush()
    }

    /** [saveDraft] on the app scope, for saves that must outlive the screen that started them. */
    fun saveDraftInBackground(recordName: String, base: List<FormatParagraph>?, desired: List<FormatParagraph>, newNoteFolder: String?) {
        scope.launch { saveMutex.withLock { saveDraft(recordName, base, desired, newNoteFolder) } }
    }

    /** Drops a local edit; the note goes back to what iCloud has. */
    suspend fun discardPending(recordName: String) {
        val pending = dao.getPending(recordName) ?: return
        dao.deletePending(recordName)
        val note = dao.getNote(recordName) ?: return
        if (pending.isNew) {
            dao.deleteNotes(listOf(recordName))
            return
        }
        val serverText = note.textData?.let { data ->
            runCatching { NoteContent.decode(Base64.getDecoder().decode(data)).text }.getOrNull()
        }
        if (serverText != null) {
            dao.upsertNotes(listOf(note.copy(title = NoteFields.title(serverText).trim(), snippet = NoteFields.snippet(serverText), plainText = serverText)))
        }
    }

    /** Turns a local edit that can't be applied to its note into a brand-new note. Returns its record name. */
    suspend fun savePendingAsNewNote(recordName: String): String? {
        val pending = dao.getPending(recordName) ?: return null
        val note = dao.getNote(recordName)
        val newName = java.util.UUID.randomUUID().toString()
        saveDraft(newName, null, decodeParagraphs(pending.desiredJson), newNoteFolder = note?.folderRecordName ?: SpecialFolders.DEFAULT)
        discardPending(recordName)
        return newName
    }

    /** Pushes soon, coalescing bursts of edits (typing) into one write. */
    fun schedulePush(delayMs: Long = PUSH_DEBOUNCE_MS) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(delayMs)
            pushPending()
        }
    }

    /** Pushes every pending edit that can be pushed. Safe to call from anywhere, any time. */
    suspend fun pushPending(): Boolean = pushMutex.withLock {
        if (sessionManager.account == null) return@withLock false
        var allPushed = true
        for (pending in dao.pushablePending()) {
            try {
                when (val outcome = writer.push(pending.recordName)) {
                    is PushOutcome.SavedAsCopy -> _syncStatus.update {
                        it.copy(notice = "“${outcome.title.ifBlank { "A note" }}” was also changed on another device. Your version was saved as a separate note.")
                    }
                    is PushOutcome.Blocked -> allPushed = false
                    else -> Unit
                }
                _syncStatus.update { it.copy(offline = false) }
            } catch (e: SessionExpiredException) {
                sessionManager.markExpired()
                return@withLock false
            } catch (e: TransientPushException) {
                _syncStatus.update { it.copy(offline = true) }
                schedulePushWhenOnline()
                return@withLock false
            } catch (e: Exception) {
                dao.markPending(pending.recordName, e.message ?: "Couldn't save this note to iCloud.", blocked = true)
                allPushed = false
            }
        }
        allPushed
    }

    suspend fun clear() {
        syncJob?.cancel()
        pushJob?.cancel()
        sync.reset()
        _syncStatus.value = SyncStatus()
    }

    private companion object {
        const val PUSH_DEBOUNCE_MS = 1_500L
    }
}
