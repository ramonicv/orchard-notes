package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.ModifyResult
import dev.rortega.orchardnotes.cloudkit.NotesZone
import dev.rortega.orchardnotes.notes.LiveMerge
import dev.rortega.orchardnotes.notes.NoteFields
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.FormatReconcile
import dev.rortega.orchardnotes.notes.doc.FormatResult
import dev.rortega.orchardnotes.notes.doc.NoteCompression
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.doc.NoteDocument
import dev.rortega.orchardnotes.notes.doc.NoteEditing
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.OBJECT_REPLACEMENT_CHARACTER
import dev.rortega.orchardnotes.notes.doc.TextDiff
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

sealed interface PushOutcome {
    data object Pushed : PushOutcome
    data object NothingToDo : PushOutcome

    /** The note changed elsewhere in the same places; our version was saved as [newRecordName]. */
    data class SavedAsCopy(val title: String, val newRecordName: String) : PushOutcome

    /** Needs the user: the edit can't be applied safely (see the pending edit's error). */
    data class Blocked(val reason: String) : PushOutcome
}

/** Thrown for problems that may go away on retry (network, server hiccups). */
class TransientPushException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pushes pending local edits to iCloud.
 *
 * Every write starts from a fresh copy of the note and goes through the same gates
 * icloud-md uses: the server's document must round-trip byte-for-byte through our model,
 * edits must not touch embedded objects, and the rebuilt document is decoded again and
 * checked against the intended text, formatting and attachments before upload. Anything
 * that fails a gate is refused rather than written.
 *
 * Notes shared with the account live in their sharer's zone and are written there. Notes
 * several people edit (shared either way) merge concurrent changes character by
 * character instead of ever saving a conflicting copy.
 */
class NoteWriter(
    private val cloudKit: CloudKitClient,
    private val dao: NotesDao,
    /** Writes records iCloud returned into the local cache. */
    private val applyRecords: suspend (List<CkRecord>, NotesZone) -> Unit,
    private val json: Json,
    private val replicaId: () -> ByteArray,
) {
    private val paragraphsSerializer = ListSerializer(FormatParagraph.serializer())

    /**
     * Held by every read-modify-write of a pending edit (saving from the editor, rebasing
     * onto a newer server version, a push recording that it's done), so none of them loses
     * another's change.
     */
    val pendingLock = Mutex()

    fun encodeParagraphs(paragraphs: List<FormatParagraph>): String = json.encodeToString(paragraphsSerializer, paragraphs)

    fun decodeParagraphs(text: String): List<FormatParagraph> = json.decodeFromString(paragraphsSerializer, text)

    /** The formatted content of the server's version of a note, if it can be read. */
    fun serverParagraphs(note: NoteEntity): List<FormatParagraph>? {
        val raw = note.textData?.let { runCatching { NoteCompression.decompress(Base64.getDecoder().decode(it)) }.getOrNull() } ?: return null
        val content = runCatching { NoteContent.decodeRaw(raw) }.getOrNull() ?: return null
        return (content.format() as? FormatResult.Ok)?.paragraphs
    }

    /** Whether several people may be editing the note at once: it's shared, either way. */
    suspend fun isCollaborative(note: NoteEntity?): Boolean {
        if (note == null) return false
        if (note.zoneOwner != null || note.shareRecordName != null) return true
        var folderKey = note.folderRecordName
        repeat(MAX_FOLDER_DEPTH) {
            val folder = folderKey?.let { dao.getFolder(it) } ?: return false
            if (folder.shareRecordName != null || folder.zoneOwner != null) return true
            folderKey = folder.parentRecordName
        }
        return false
    }

    /**
     * Combines local changes ([ours], made from [base]) with [theirs]. Shared notes always
     * merge; for the account's own notes, null when both changed the same paragraphs.
     */
    fun merge(collaborative: Boolean, base: List<FormatParagraph>, ours: List<FormatParagraph>, theirs: List<FormatParagraph>): List<FormatParagraph>? =
        if (collaborative) {
            LiveMerge.merge(base, ours, theirs).paragraphs
        } else {
            (ParagraphMerge.merge(base, ours, theirs) as? ParagraphMerge.Result.Merged)?.paragraphs
        }

    /**
     * A newer version of these notes arrived from iCloud: rebases local edits still waiting
     * to be pushed onto it, so what's shown, and later pushed, includes the other changes.
     * Own notes whose same paragraphs changed on both sides are left for [push] to settle.
     */
    suspend fun rebaseOnServerChanges(notes: List<NoteEntity>) = pendingLock.withLock {
        var changed = false
        for (note in notes) {
            val pending = dao.getPending(note.recordName) ?: continue
            if (pending.isNew || pending.blocked) continue
            val base = pending.baseJson?.let { ParagraphMerge.withOffsets(decodeParagraphs(it)) } ?: continue
            val theirs = serverParagraphs(note) ?: continue
            if (NoteFormat.formatsEqual(base, theirs)) continue
            val desired = ParagraphMerge.withOffsets(decodeParagraphs(pending.desiredJson))
            val merged = merge(isCollaborative(note), base, desired, theirs) ?: continue
            val text = merged.joinToString("\n") { it.text }
            dao.upsertPending(
                pending.copy(
                    baseJson = encodeParagraphs(theirs),
                    desiredJson = encodeParagraphs(merged),
                    title = NoteFields.title(text).trim(),
                    snippet = NoteFields.snippet(text),
                    plainText = text,
                    updatedAt = LocalClock.next(),
                ),
            )
            changed = true
        }
        if (changed) dao.overlayPendingEdits()
    }

    /**
     * Pushes the note's pending edit. Once started it runs to the end, even if the caller is
     * cancelled (the note closing, the app leaving the screen): iCloud may already have taken
     * the write, and if the pending edit never learned so, the next push would take our own
     * change for someone else's (duplicating its text, or saving a needless copy).
     */
    suspend fun push(recordName: String): PushOutcome = withContext(NonCancellable) { pushEdit(recordName) }

    private suspend fun pushEdit(recordName: String): PushOutcome {
        val pending = dao.getPending(recordName) ?: return PushOutcome.NothingToDo
        if (pending.blocked) return PushOutcome.Blocked(pending.error ?: "Needs attention")
        val desired = ParagraphMerge.withOffsets(decodeParagraphs(pending.desiredJson))
        val desiredText = desired.joinToString("\n") { it.text }

        if (pending.isNew) {
            if (desiredText.isBlank()) {
                // Nothing worth creating (Apple's clients don't save empty notes either).
                dao.deletePending(recordName)
                dao.deleteNotes(listOf(recordName))
                return PushOutcome.Pushed
            }
            val folder = pending.folderRecordName ?: SpecialFolders.DEFAULT
            createNote(recordName, folder, desired, zoneOfFolder(folder))
            finish(pending)
            return PushOutcome.Pushed
        }

        val note = dao.getNote(recordName)
        val zone = NotesZone(note?.zoneOwner)
        val collaborative = isCollaborative(note)
        repeat(MAX_CONFLICT_RETRIES) {
            val fresh = lookup(recordName, zone) ?: return block(pending, "This note was deleted on another device.")
            val outcome = pushUpdate(pending, fresh, desired, zone, collaborative)
            if (outcome != null) return outcome
            // null: the record changed between lookup and write (CONFLICT); start over from a fresh copy.
        }
        throw TransientPushException("The note kept changing on another device; will retry.")
    }

    /** One attempt at updating an existing note; null means retry after a write conflict. */
    private suspend fun pushUpdate(
        pending: PendingEditEntity,
        fresh: CkRecord,
        desired: List<FormatParagraph>,
        zone: NotesZone,
        collaborative: Boolean,
    ): PushOutcome? {
        if (fresh.value("TextDataAsset") != null) {
            return block(pending, "This note is too large to edit here.")
        }
        val compressed = fresh.bytes("TextDataEncrypted") ?: return block(pending, "This note has no editable text.")
        val raw = runCatching { NoteCompression.decompress(compressed) }.getOrNull()
            ?: return block(pending, "This note's content couldn't be read.")
        if (!NoteDocument.roundTrips(raw)) {
            return block(pending, "This note uses content Orchard can't edit safely yet.")
        }
        val doc = NoteDocument.parse(raw)
        val theirs = when (val format = NoteFormat.decode(doc.text, doc.attributeRuns)) {
            is FormatResult.Ok -> format.paragraphs
            is FormatResult.Unsupported -> return block(pending, "This note uses formatting Orchard can't edit safely yet.")
        }

        val base = pending.baseJson?.let { ParagraphMerge.withOffsets(decodeParagraphs(it)) }
        val target = if (base == null || NoteFormat.formatsEqual(base, theirs)) {
            desired
        } else {
            merge(collaborative, base, desired, theirs) ?: return saveAsCopy(pending, fresh, desired)
        }
        val targetText = target.joinToString("\n") { it.text }
        if (doc.text == targetText && NoteFormat.formatsEqual(theirs, target)) {
            finish(pending)
            return PushOutcome.NothingToDo
        }

        // Embedded objects are linked to their placeholder character; never delete, move or invent one.
        val touchesEmbed = TextDiff.computeSplices(doc.text, targetText).any { splice ->
            OBJECT_REPLACEMENT_CHARACTER in doc.text.substring(splice.start, splice.start + splice.deleteLength) ||
                OBJECT_REPLACEMENT_CHARACTER in splice.insertText
        }
        if (touchesEmbed) return block(pending, "Attachments can only be removed or moved in Notes on an Apple device.")

        val attachmentsBefore = NoteContent(doc.text, doc.attributeRuns).attachments().map { it.identifier }
        val replica = replicaId()
        try {
            NoteEditing.applyTextEdit(doc, targetText, replica)
            when (val result = FormatReconcile.reconcile(doc, target, replica)) {
                is FormatReconcile.Result.Refused -> return block(pending, "Couldn't apply formatting safely: ${result.reason}.")
                is FormatReconcile.Result.Ok -> Unit
            }
            doc.validate()
        } catch (e: Exception) {
            return block(pending, "Couldn't apply this edit safely: ${e.message}")
        }
        val payload = verifiedPayload(doc, target) ?: return block(pending, "The rebuilt note failed verification, so it wasn't saved.")
        val rebuilt = NoteContent.decodeRaw(NoteCompression.decompress(payload))
        if (rebuilt.attachments().map { it.identifier } != attachmentsBefore) {
            return block(pending, "The rebuilt note failed attachment verification, so it wasn't saved.")
        }

        val fields = NoteFields.update(fresh, Base64.getEncoder().encodeToString(payload), targetText, System.currentTimeMillis())
        return when (val result = modify(NoteFields.updateOperation(fresh, fields, withParent = !zone.isShared), zone)) {
            is ModifyResult.Saved -> {
                applyRecords(listOf(result.record), zone)
                finish(pending)
                PushOutcome.Pushed
            }
            is ModifyResult.Failed -> when (val code = result.error.serverErrorCode) {
                "CONFLICT" -> null
                in PERMISSION_ERRORS -> block(pending, "You can view this shared note but not edit it.")
                in TRANSIENT_ERRORS -> throw TransientPushException("iCloud couldn't take the change right now ($code).")
                else -> if (zone.isShared) {
                    block(pending, "iCloud refused the change to this shared note ($code${result.error.reason?.let { ": $it" } ?: ""}).")
                } else {
                    throw TransientPushException("iCloud rejected the change ($code).")
                }
            }
            is ModifyResult.Deleted -> throw TransientPushException("Unexpected response from iCloud.")
        }
    }

    /** The zone a new note in this folder belongs to: the sharer's, for a folder shared with the account. */
    private suspend fun zoneOfFolder(folder: String): NotesZone = NotesZone(dao.getFolder(folder)?.zoneOwner)

    /**
     * Creates a note from scratch: the one write that never depends on a server document.
     * In a folder shared with the account, the note goes in the sharer's zone as a child of
     * the folder, which is what makes it part of the folder's share.
     */
    private suspend fun createNote(recordName: String, folder: String, paragraphs: List<FormatParagraph>, zone: NotesZone): CkRecord {
        val text = paragraphs.joinToString("\n") { it.text }
        val replica = replicaId()
        val doc = NoteEditing.buildInitialDocument(text, replica)
        when (val result = FormatReconcile.reconcile(doc, paragraphs, replica)) {
            is FormatReconcile.Result.Refused -> throw IllegalStateException("Couldn't format the new note: ${result.reason}")
            is FormatReconcile.Result.Ok -> Unit
        }
        val payload = verifiedPayload(doc, paragraphs) ?: throw IllegalStateException("The new note failed verification")
        val now = System.currentTimeMillis()
        val folderName = NoteRecords.folderRecordName(folder)
        val fields = NoteFields.create(Base64.getEncoder().encodeToString(payload), text, now, folderName, zone)
        val operation = NoteFields.createOperation(
            "Note", recordName, fields,
            parent = folderName.takeIf { zone.isShared },
            createShortGuid = zone.isShared,
        )
        return when (val result = modify(operation, zone)) {
            is ModifyResult.Saved -> result.record.also { applyRecords(listOf(it), zone) }
            is ModifyResult.Failed -> when (result.error.serverErrorCode) {
                in PERMISSION_ERRORS -> throw IllegalStateException("You can't add notes to this shared folder.")
                else -> throw TransientPushException("iCloud rejected the new note (${result.error.serverErrorCode}).")
            }
            is ModifyResult.Deleted -> throw TransientPushException("Unexpected response from iCloud.")
        }
    }

    /**
     * Both sides changed the same paragraphs: keep theirs in place and save ours as a new
     * note next to it, so nothing is lost and nothing is silently overwritten.
     */
    private suspend fun saveAsCopy(pending: PendingEditEntity, fresh: CkRecord, desired: List<FormatParagraph>): PushOutcome {
        val copyName = UUID.randomUUID().toString()
        val folder = fresh.reference("Folder") ?: pending.folderRecordName ?: SpecialFolders.DEFAULT
        createNote(copyName, folder, desired, NotesZone.Private)
        dao.deletePending(pending.recordName)
        applyRecords(listOf(fresh), NotesZone.Private)
        return PushOutcome.SavedAsCopy(pending.title, copyName)
    }

    /**
     * Pushes one queued move / permanent delete / new folder. Moves and deletes are
     * ordinary updates of a fresh copy of the note (Apple's "delete" is a move to Recently
     * Deleted; a permanent delete also marks it Deleted), retried on write conflicts. Like
     * [push], it runs to the end once started.
     */
    suspend fun pushOp(op: PendingOpEntity) = withContext(NonCancellable) { pushOperation(op) }

    private suspend fun pushOperation(op: PendingOpEntity) {
        if (op.type != PendingOpEntity.CREATE_FOLDER && dao.getNote(op.recordName)?.zoneOwner != null) {
            return failOp(op, "Notes shared with you can only be moved or deleted by the person who shared them.")
        }
        when (op.type) {
            PendingOpEntity.CREATE_FOLDER -> {
                val fields = NoteFields.folder(op.title.orEmpty(), op.parentRecordName)
                when (val result = modify(NoteFields.createOperation("Folder", op.recordName, fields, parent = op.parentRecordName))) {
                    is ModifyResult.Saved -> applyRecords(listOf(result.record), NotesZone.Private)
                    is ModifyResult.Failed -> return failOp(op, "iCloud rejected the new folder (${result.error.serverErrorCode}).")
                    is ModifyResult.Deleted -> throw TransientPushException("Unexpected response from iCloud.")
                }
                dao.deleteOpIfUnchanged(op.type, op.recordName, op.createdAt)
            }
            PendingOpEntity.MOVE, PendingOpEntity.PURGE -> {
                val purge = op.type == PendingOpEntity.PURGE
                val folder = if (purge) SpecialFolders.TRASH else op.folderRecordName ?: SpecialFolders.DEFAULT
                repeat(MAX_CONFLICT_RETRIES) {
                    val fresh = lookup(op.recordName)
                    if (fresh == null || (!purge && fresh.reference("Folder") == folder)) {
                        // Already gone, or already where it should be.
                        dao.deleteOpIfUnchanged(op.type, op.recordName, op.createdAt)
                        return
                    }
                    val fields = NoteFields.relocate(fresh, folder, System.currentTimeMillis(), purge)
                    when (val result = modify(NoteFields.updateOperation(fresh, fields))) {
                        is ModifyResult.Saved -> {
                            applyRecords(listOf(result.record), NotesZone.Private)
                            dao.deleteOpIfUnchanged(op.type, op.recordName, op.createdAt)
                            return
                        }
                        is ModifyResult.Failed -> if (result.error.serverErrorCode != "CONFLICT") {
                            return failOp(op, "iCloud rejected the change (${result.error.serverErrorCode}).")
                        }
                        is ModifyResult.Deleted -> throw TransientPushException("Unexpected response from iCloud.")
                    }
                }
                throw TransientPushException("The note kept changing on another device; will retry.")
            }
        }
    }

    private suspend fun failOp(op: PendingOpEntity, reason: String) = dao.markOp(op.type, op.recordName, reason)

    /** Encodes, compresses and independently re-decodes the document; null if anything disagrees. */
    private fun verifiedPayload(doc: NoteDocument, expected: List<FormatParagraph>): ByteArray? {
        val encoded = doc.encode()
        if (!NoteDocument.roundTrips(encoded)) return null
        val compressed = NoteCompression.compress(encoded)
        val decoded = NoteContent.decodeRaw(NoteCompression.decompress(compressed))
        if (decoded.text != expected.joinToString("\n") { it.text }) return null
        val format = decoded.format() as? FormatResult.Ok ?: return null
        return compressed.takeIf { NoteFormat.formatsEqual(format.paragraphs, expected) }
    }

    private suspend fun finish(pending: PendingEditEntity) = pendingLock.withLock {
        // Under the lock: a save that read the edit just before this would otherwise write it back on its old base.
        if (dao.deletePendingIfUnchanged(pending.recordName, pending.updatedAt) == 0) {
            // Edited again while this push was in flight: those edits now build on what we just pushed.
            dao.rebasePending(pending.recordName, pending.desiredJson)
        }
        dao.overlayPendingEdits()
    }

    private suspend fun block(pending: PendingEditEntity, reason: String): PushOutcome {
        dao.markPending(pending.recordName, reason, blocked = true)
        return PushOutcome.Blocked(reason)
    }

    private suspend fun lookup(recordName: String, zone: NotesZone = NotesZone.Private): CkRecord? = try {
        cloudKit.lookup(recordName, zone)
    } catch (e: java.io.IOException) {
        throw TransientPushException("Couldn't reach iCloud.", e)
    }

    private suspend fun modify(operation: kotlinx.serialization.json.JsonObject, zone: NotesZone = NotesZone.Private): ModifyResult = try {
        cloudKit.modify(listOf(operation), zone).single()
    } catch (e: java.io.IOException) {
        throw TransientPushException("Couldn't reach iCloud.", e)
    }

    private companion object {
        const val MAX_CONFLICT_RETRIES = 3
        const val MAX_FOLDER_DEPTH = 32

        /** What CloudKit answers a write the account isn't allowed to make. */
        val PERMISSION_ERRORS = setOf("ACCESS_DENIED", "PERMISSION_FAILURE")
        val TRANSIENT_ERRORS = setOf("THROTTLED", "TRY_AGAIN_LATER", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE")
    }
}
