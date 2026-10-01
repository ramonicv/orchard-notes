package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.auth.SessionState
import dev.rortega.orchardnotes.cloudkit.NotesZone
import dev.rortega.orchardnotes.cloudkit.SessionExpiredException
import dev.rortega.orchardnotes.notes.LiveMerge
import dev.rortega.orchardnotes.notes.NoteFields
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/** What [NotesRepository.saveDraft] stored: the content, and its [LocalClock] time. */
data class SavedDraft(val paragraphs: List<FormatParagraph>, val savedAt: Long)

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
 *
 * While the app is on screen it keeps syncing: every [FULL_SYNC_INTERVAL_MS], and every
 * [LIVE_INTERVAL_MS] for the zones of open shared notes, so other people's edits show up
 * live. Pulls and pushes never overlap, so a pull can't see a push half-applied.
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

    /** The wait before a scheduled push (see [schedulePush]). */
    private var pushWait: Job? = null

    /** Held while pushing or applying pulled changes. */
    private val pushMutex = Mutex()

    private val foreground = MutableStateFlow(false)

    /** Zones of notes open on screen that others may be editing, with how many watch each. */
    private val watchedZones = MutableStateFlow<Map<NotesZone, Int>>(emptyMap())

    init {
        scope.launch {
            combine(dao.observePendingCount(), dao.observeOpCount()) { edits, ops -> edits + ops }
                .collect { count -> _syncStatus.update { it.copy(pendingCount = count) } }
        }
        scope.launch {
            combine(foreground, watchedZones) { visible, zones -> if (visible) zones.keys else null }
                .distinctUntilChanged()
                .collectLatest { zones -> if (zones != null) keepLive(zones) }
        }
    }

    fun folders(): Flow<List<FolderEntity>> = dao.observeFolders()
    fun folderCounts(): Flow<List<FolderCount>> = dao.observeFolderCounts()
    fun notesInFolder(folder: String): Flow<List<NoteSummary>> = dao.observeNotesInFolder(folder)
    fun allNotes(): Flow<List<NoteSummary>> = dao.observeAllNotes(SpecialFolders.TRASH)
    fun search(query: String): Flow<List<NoteSummary>> = dao.observeSearch(query, SpecialFolders.TRASH)
    fun note(recordName: String): Flow<NoteEntity?> = dao.observeNote(recordName)
    fun pendingEdit(recordName: String): Flow<PendingEditEntity?> = dao.observePending(recordName)
    fun sharingIndex(): Flow<SharingIndex> = combine(dao.observeFolders(), dao.observeShares()) { folders, shares -> SharingIndex(folders, shares) }

    /** The zone to keep live while [note] is open: its zone, if others may be editing it too. */
    suspend fun liveZoneOf(note: NoteEntity): NotesZone? = if (writer.isCollaborative(note)) NotesZone(note.zoneOwner) else null
    suspend fun folderOf(recordName: String): String? = dao.getNote(recordName)?.folderRecordName

    fun decodeParagraphs(json: String): List<FormatParagraph> = ParagraphMerge.withOffsets(writer.decodeParagraphs(json))

    fun clearNotice() = _syncStatus.update { it.copy(notice = null) }

    // --- reading from iCloud ----------------------------------------------------

    /** Pushes pending edits, then pulls changes; unless a sync is already running. */
    fun requestSync(): Job {
        syncJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch { syncNow() }.also { syncJob = it }
    }

    /** [quiet]: a background sync, which doesn't show as refreshing. */
    suspend fun syncNow(quiet: Boolean = false) {
        if (sessionManager.account == null) return
        _syncStatus.update { it.copy(syncing = !quiet, error = null) }
        pushPending()
        try {
            pushMutex.withLock { sync.sync() }
            _syncStatus.update { it.copy(syncing = false, offline = false, lastSyncedAt = System.currentTimeMillis()) }
        } catch (e: SessionExpiredException) {
            sessionManager.markExpired()
            _syncStatus.update { it.copy(syncing = false, error = "Your iCloud session expired. Sign in again to sync.") }
        } catch (e: java.io.IOException) {
            _syncStatus.update { it.copy(syncing = false, offline = true) }
        } catch (e: CancellationException) {
            // Stopped (the app left the screen, say), not failed.
            _syncStatus.update { it.copy(syncing = false) }
            throw e
        } catch (e: Exception) {
            _syncStatus.update { it.copy(syncing = false, error = e.message ?: "Sync failed.") }
        }
    }

    // --- live sync -------------------------------------------------------------------

    /** The app came to (or left) the screen; background syncing only runs while it's there. */
    fun setForeground(visible: Boolean) {
        foreground.value = visible
    }

    /**
     * Keeps [zone] closely in sync while a note from it is open, since others may be editing
     * it. Returns the function that stops watching.
     */
    fun watch(zone: NotesZone): () -> Unit {
        watchedZones.update { it + (zone to (it[zone] ?: 0) + 1) }
        var stopped = false
        return {
            if (!stopped) {
                stopped = true
                watchedZones.update { current ->
                    val count = (current[zone] ?: 1) - 1
                    if (count <= 0) current - zone else current + (zone to count)
                }
            }
        }
    }

    private suspend fun keepLive(zones: Set<NotesZone>) {
        var failures = 0
        var sinceFullSync = 0L
        while (true) {
            val interval = if (zones.isEmpty()) FULL_SYNC_INTERVAL_MS else LIVE_INTERVAL_MS
            // Back off while iCloud can't be reached (up to 16x).
            delay(interval shl failures.coerceAtMost(MAX_BACKOFF_SHIFT))
            val session = sessionManager.state.value
            if (session !is SessionState.SignedIn || session.expired) continue
            sinceFullSync += interval
            val ok = if (zones.isEmpty() || sinceFullSync >= FULL_SYNC_INTERVAL_MS) {
                sinceFullSync = 0
                syncNow(quiet = true)
                _syncStatus.value.let { it.error == null && !it.offline }
            } else {
                refreshZones(zones)
            }
            failures = if (ok) 0 else failures + 1
        }
    }

    /** Pushes, then pulls just the [zones] of open notes. */
    private suspend fun refreshZones(zones: Set<NotesZone>): Boolean {
        pushPending()
        return try {
            pushMutex.withLock { zones.forEach { sync.syncZone(it) } }
            _syncStatus.update { it.copy(offline = false) }
            true
        } catch (e: SessionExpiredException) {
            sessionManager.markExpired()
            false
        } catch (e: java.io.IOException) {
            _syncStatus.update { it.copy(offline = true) }
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // --- local edits --------------------------------------------------------------

    /**
     * Saves [desired] as the note's new content, locally and immediately; the push to
     * iCloud follows shortly. [base] is the content [desired] was edited from: if the note
     * has changed since (someone's edit arrived), the edit is merged into what it is now.
     * Returns what was saved, which includes any such merged-in changes.
     */
    suspend fun saveDraft(
        recordName: String,
        base: List<FormatParagraph>?,
        desired: List<FormatParagraph>,
        newNoteFolder: String? = null,
    ): SavedDraft = writer.pendingLock.withLock {
        val existing = dao.getPending(recordName)
        val note = dao.getNote(recordName)
        // A note created here is new until iCloud has it; after that, its editor's saves are edits like any other.
        val isNew = existing?.isNew ?: (newNoteFolder != null && note?.recordChangeTag == null)
        val server = if (isNew) null else note?.let(writer::serverParagraphs)
        val current = existing?.let { ParagraphMerge.withOffsets(writer.decodeParagraphs(it.desiredJson)) } ?: server
        val edited = ParagraphMerge.withOffsets(desired)
        val target = if (base == null || current == null || NoteFormat.formatsEqual(ParagraphMerge.withOffsets(base), current)) {
            edited
        } else {
            // The editor catching up with what arrived while it was typing: never a real conflict.
            LiveMerge.merge(ParagraphMerge.withOffsets(base), edited, current).paragraphs
        }
        val text = target.joinToString("\n") { it.text }
        val now = LocalClock.next()
        val title = NoteFields.title(text).trim()
        val snippet = NoteFields.snippet(text)
        dao.upsertPending(
            PendingEditEntity(
                recordName = recordName,
                isNew = isNew,
                folderRecordName = existing?.folderRecordName ?: newNoteFolder,
                // The server version the edit applies to: what it was merged into, else what it started from.
                baseJson = existing?.baseJson ?: if (isNew) null else (server ?: base)?.let(writer::encodeParagraphs),
                desiredJson = writer.encodeParagraphs(target),
                title = title,
                snippet = snippet,
                plainText = text,
                updatedAt = now,
                error = existing?.error,
                blocked = existing?.blocked ?: false,
            ),
        )
        if (isNew && note == null) {
            val folder = newNoteFolder ?: SpecialFolders.DEFAULT
            dao.upsertNotes(
                listOf(
                    NoteEntity(
                        recordName = recordName,
                        folderRecordName = folder,
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
                        // A new note in a folder shared with the account is created in its sharer's zone.
                        zoneOwner = dao.getFolder(folder)?.zoneOwner,
                        syncedAt = now,
                    ),
                ),
            )
        }
        dao.overlayPendingEdits()
        schedulePush()
        SavedDraft(target, now)
    }

    /**
     * [saveDraft] on the app scope, once [after] (the editor's previous save) is done: saves
     * land in order, and complete even if the screen that started them goes away.
     */
    fun saveDraftAsync(
        recordName: String,
        base: List<FormatParagraph>?,
        desired: List<FormatParagraph>,
        newNoteFolder: String?,
        after: Deferred<*>? = null,
    ): Deferred<SavedDraft> = scope.async {
        after?.let { previous -> runCatching { previous.await() } }
        saveDraft(recordName, base, desired, newNoteFolder)
    }

    /** Pushes a local edit that couldn't be saved once more (after an update fixed the cause, say). */
    suspend fun retryPending(recordName: String) {
        dao.markPending(recordName, error = null, blocked = false)
        schedulePush(delayMs = 0)
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
        // Restamped: iCloud's version is current again, newer than the edit just dropped.
        val restored = note.copy(syncedAt = LocalClock.next())
        dao.upsertNotes(
            listOf(
                if (serverText == null) {
                    restored
                } else {
                    restored.copy(title = NoteFields.title(serverText).trim(), snippet = NoteFields.snippet(serverText), plainText = serverText)
                },
            ),
        )
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

    // --- moving, deleting, folders --------------------------------------------------

    /** Moves a note to another folder (Recently Deleted included); queued like any edit. */
    suspend fun moveNote(recordName: String, folderRecordName: String) {
        val pending = dao.getPending(recordName)
        if (pending?.isNew == true) {
            // Never reached iCloud: just change where it will be created.
            dao.setPendingFolder(recordName, folderRecordName)
        } else {
            dao.upsertOp(PendingOpEntity(PendingOpEntity.MOVE, recordName, folderRecordName = folderRecordName, createdAt = System.currentTimeMillis()))
        }
        dao.setNoteFolder(recordName, folderRecordName)
        schedulePush(delayMs = 0)
    }

    /** Apple's delete: the note moves to Recently Deleted and can be recovered for 30 days. */
    suspend fun trashNote(recordName: String) {
        if (dao.getPending(recordName)?.isNew == true) {
            forgetLocalNote(recordName)
        } else {
            moveNote(recordName, SpecialFolders.TRASH)
        }
    }

    suspend fun recoverNote(recordName: String) = moveNote(recordName, SpecialFolders.DEFAULT)

    /** Removes a note for good, including any unsynced edits to it. */
    suspend fun deleteNotePermanently(recordName: String) {
        if (dao.getPending(recordName)?.isNew == true) {
            forgetLocalNote(recordName)
            return
        }
        dao.deletePending(recordName)
        dao.deleteOpsFor(recordName)
        dao.upsertOp(PendingOpEntity(PendingOpEntity.PURGE, recordName, createdAt = System.currentTimeMillis()))
        dao.deleteNotes(listOf(recordName))
        schedulePush(delayMs = 0)
    }

    /** Creates a folder (optionally inside another one). Returns its record name. */
    suspend fun createFolder(title: String, parentRecordName: String? = null): String {
        val recordName = java.util.UUID.randomUUID().toString()
        dao.upsertFolders(listOf(FolderEntity(recordName, title, parentRecordName)))
        dao.upsertOp(
            PendingOpEntity(
                PendingOpEntity.CREATE_FOLDER, recordName, title = title, parentRecordName = parentRecordName,
                createdAt = System.currentTimeMillis(),
            ),
        )
        schedulePush(delayMs = 0)
        return recordName
    }

    private suspend fun forgetLocalNote(recordName: String) {
        dao.deletePending(recordName)
        dao.deleteOpsFor(recordName)
        dao.deleteNotes(listOf(recordName))
    }

    /**
     * Pushes soon, coalescing bursts of edits (typing) into one write. A newer request only
     * restarts the wait: a push already under way is never cut short, and this one follows it.
     */
    fun schedulePush(delayMs: Long = PUSH_DEBOUNCE_MS) {
        pushWait?.cancel()
        pushWait = scope.launch {
            delay(delayMs)
            scope.launch { pushPending() }
        }
    }

    /**
     * Pushes every pending edit that can be pushed. Safe to call from anywhere, any time.
     * Cancelling it stops it between notes; edits not yet pushed stay queued as they were.
     */
    suspend fun pushPending(): Boolean = pushMutex.withLock {
        if (sessionManager.account == null) return@withLock false
        var allPushed = true
        val ops = dao.pushableOps()
        // New folders first, so notes can be created in or moved into them.
        val (folderCreates, otherOps) = ops.partition { it.type == PendingOpEntity.CREATE_FOLDER }
        if (!pushOps(folderCreates)) return@withLock false
        for (pending in dao.pushablePending()) {
            currentCoroutineContext().ensureActive()
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
            } catch (e: CancellationException) {
                // Stopped, not failed: never a reason to hold the edit back for the user.
                throw e
            } catch (e: Exception) {
                dao.markPending(pending.recordName, e.message ?: "Couldn't save this note to iCloud.", blocked = true)
                allPushed = false
            }
        }
        pushOps(otherOps) && allPushed
    }

    /** Pushes queued operations in order; false if iCloud couldn't be reached. */
    private suspend fun pushOps(ops: List<PendingOpEntity>): Boolean {
        for (op in ops) {
            currentCoroutineContext().ensureActive()
            try {
                writer.pushOp(op)
            } catch (e: SessionExpiredException) {
                sessionManager.markExpired()
                return false
            } catch (e: TransientPushException) {
                _syncStatus.update { it.copy(offline = true) }
                schedulePushWhenOnline()
                return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.markOp(op.type, op.recordName, e.message ?: "Couldn't apply this change in iCloud.")
            }
        }
        return true
    }

    suspend fun clear() {
        syncJob?.cancel()
        pushWait?.cancel()
        // After any push under way, which runs to the end: nothing it brings back lands after the reset.
        pushMutex.withLock { sync.reset() }
        _syncStatus.value = SyncStatus()
    }

    private companion object {
        const val PUSH_DEBOUNCE_MS = 1_500L
        const val LIVE_INTERVAL_MS = 4_000L
        const val FULL_SYNC_INTERVAL_MS = 30_000L
        const val MAX_BACKOFF_SHIFT = 4
    }
}
