package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.notes.doc.NoteContent

/** Well-known folder record names in the Notes zone. */
object SpecialFolders {
    /** The account's default "Notes" folder. */
    const val DEFAULT = "DefaultFolder-CloudKit"

    /** "Recently Deleted": trashed notes are notes whose Folder points here. */
    const val TRASH = "TrashFolder-CloudKit"
}

/** Converts CloudKit records from the Notes zone into cache rows. */
object NoteRecords {

    /** Whether a Note record is gone for good (purged), as opposed to merely in Recently Deleted. */
    fun isPurged(record: CkRecord): Boolean = record.deleted || (record.long("Deleted") ?: 0L) != 0L

    fun toNoteEntity(record: CkRecord): NoteEntity {
        val locked = record.recordType == "PasswordProtectedNote"
        val textData = record.stringValue("TextDataEncrypted")
        val plainText = if (locked || textData == null) {
            null
        } else {
            runCatching { NoteContent.decode(record.bytes("TextDataEncrypted")!!).text }.getOrNull()
        }
        val title = record.encryptedString("TitleEncrypted")?.takeIf { it.isNotBlank() }
            ?: plainText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(TITLE_FALLBACK_LENGTH)
            ?: ""
        val snippet = record.encryptedString("SnippetEncrypted") ?: ""
        val folder = record.reference("Folder") ?: record.referenceList("Folders").firstOrNull()
        return NoteEntity(
            recordName = record.recordName,
            folderRecordName = folder,
            title = title,
            snippet = snippet,
            plainText = plainText ?: "",
            textData = textData,
            creationDate = record.long("CreationDate") ?: 0L,
            modificationDate = record.long("ModificationDate") ?: 0L,
            isPinned = (record.long("IsPinned") ?: 0L) != 0L,
            recordChangeTag = record.recordChangeTag,
            firstAttachmentUti = record.encryptedString("FirstAttachmentUTIEncrypted"),
            isLocked = locked,
            bodyUnavailable = !locked && plainText == null,
        )
    }

    fun toFolderEntity(record: CkRecord): FolderEntity = FolderEntity(
        recordName = record.recordName,
        title = record.encryptedString("TitleEncrypted")?.takeIf { it.isNotBlank() }
            ?: if (record.recordName == SpecialFolders.DEFAULT) "Notes" else "Untitled Folder",
        parentRecordName = record.reference("ParentFolder") ?: record.parentRecordName,
    )

    private const val TITLE_FALLBACK_LENGTH = 80
}
