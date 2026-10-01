package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.ModifyResult
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
 */
class NoteWriter(
    private val cloudKit: CloudKitClient,
    private val dao: NotesDao,
    /** Writes records iCloud returned into the local cache. */
    private val applyRecords: suspend (List<CkRecord>) -> Unit,
    private val json: Json,
    private val replicaId: () -> ByteArray,
) {
    private val paragraphsSerializer = ListSerializer(FormatParagraph.serializer())

    fun encodeParagraphs(paragraphs: List<FormatParagraph>): String = json.encodeToString(paragraphsSerializer, paragraphs)

    fun decodeParagraphs(text: String): List<FormatParagraph> = json.decodeFromString(paragraphsSerializer, text)

    suspend fun push(recordName: String): PushOutcome {
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
            createNote(recordName, pending.folderRecordName ?: SpecialFolders.DEFAULT, desired)
            finish(pending)
            return PushOutcome.Pushed
        }

        repeat(MAX_CONFLICT_RETRIES) {
            val fresh = lookup(recordName) ?: return block(pending, "This note was deleted on another device.")
            val outcome = pushUpdate(pending, fresh, desired)
            if (outcome != null) return outcome
            // null: the record changed between lookup and write (CONFLICT); start over from a fresh copy.
        }
        throw TransientPushException("The note kept changing on another device; will retry.")
    }

    /** One attempt at updating an existing note; null means retry after a write conflict. */
    private suspend fun pushUpdate(pending: PendingEditEntity, fresh: CkRecord, desired: List<FormatParagraph>): PushOutcome? {
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
            when (val merged = ParagraphMerge.merge(base, desired, theirs)) {
                is ParagraphMerge.Result.Merged -> merged.paragraphs
                ParagraphMerge.Result.Conflict -> return saveAsCopy(pending, fresh, desired)
            }
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
        return when (val result = modify(NoteFields.updateOperation(fresh, fields))) {
            is ModifyResult.Saved -> {
                applyRecords(listOf(result.record))
                finish(pending)
                PushOutcome.Pushed
            }
            is ModifyResult.Failed -> if (result.error.serverErrorCode == "CONFLICT") {
                null
            } else {
                throw TransientPushException("iCloud rejected the change (${result.error.serverErrorCode}).")
            }
            is ModifyResult.Deleted -> throw TransientPushException("Unexpected response from iCloud.")
        }
    }

    /** Creates a note from scratch: the one write that never depends on a server document. */
    private suspend fun createNote(recordName: String, folder: String, paragraphs: List<FormatParagraph>): CkRecord {
        val text = paragraphs.joinToString("\n") { it.text }
        val replica = replicaId()
        val doc = NoteEditing.buildInitialDocument(text, replica)
        when (val result = FormatReconcile.reconcile(doc, paragraphs, replica)) {
            is FormatReconcile.Result.Refused -> throw IllegalStateException("Couldn't format the new note: ${result.reason}")
            is FormatReconcile.Result.Ok -> Unit
        }
        val payload = verifiedPayload(doc, paragraphs) ?: throw IllegalStateException("The new note failed verification")
        val now = System.currentTimeMillis()
        val fields = NoteFields.create(Base64.getEncoder().encodeToString(payload), text, now, folder)
        return when (val result = modify(NoteFields.createOperation("Note", recordName, fields))) {
            is ModifyResult.Saved -> result.record.also { applyRecords(listOf(it)) }
            is ModifyResult.Failed -> throw TransientPushException("iCloud rejected the new note (${result.error.serverErrorCode}).")
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
        createNote(copyName, folder, desired)
        dao.deletePending(pending.recordName)
        applyRecords(listOf(fresh))
        return PushOutcome.SavedAsCopy(pending.title, copyName)
    }

    /**
     * Pushes one queued move / permanent delete / new folder. Moves and deletes are
     * ordinary updates of a fresh copy of the note (Apple's "delete" is a move to Recently
     * Deleted; a permanent delete also marks it Deleted), retried on write conflicts.
     */
    suspend fun pushOp(op: PendingOpEntity) {
        when (op.type) {
            PendingOpEntity.CREATE_FOLDER -> {
                val fields = NoteFields.folder(op.title.orEmpty(), op.parentRecordName)
                when (val result = modify(NoteFields.createOperation("Folder", op.recordName, fields, parent = op.parentRecordName))) {
                    is ModifyResult.Saved -> applyRecords(listOf(result.record))
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
                            applyRecords(listOf(result.record))
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

    private suspend fun finish(pending: PendingEditEntity) {
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

    private suspend fun lookup(recordName: String): CkRecord? = try {
        cloudKit.lookup(recordName)
    } catch (e: java.io.IOException) {
        throw TransientPushException("Couldn't reach iCloud.", e)
    }

    private suspend fun modify(operation: kotlinx.serialization.json.JsonObject): ModifyResult = try {
        cloudKit.modify(listOf(operation)).single()
    } catch (e: java.io.IOException) {
        throw TransientPushException("Couldn't reach iCloud.", e)
    }

    private companion object {
        const val MAX_CONFLICT_RETRIES = 3
    }
}
