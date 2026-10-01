package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.NotesZone
import dev.rortega.orchardnotes.notes.doc.NoteContent

/** Well-known folder record names in the Notes zone. */
object SpecialFolders {
    /** The account's default "Notes" folder. */
    const val DEFAULT = "DefaultFolder-CloudKit"

    /** "Recently Deleted": trashed notes are notes whose Folder points here. */
    const val TRASH = "TrashFolder-CloudKit"

    val ALL = setOf(DEFAULT, TRASH)
}

/** Converts CloudKit records from a Notes zone into cache rows. */
object NoteRecords {

    /** Whether a Note record is gone for good (purged), as opposed to merely in Recently Deleted. */
    fun isPurged(record: CkRecord): Boolean = record.deleted || (record.long("Deleted") ?: 0L) != 0L

    /**
     * Whether a record from [zone] should leave the cache: purged, or a sharer's note they
     * moved to their own Recently Deleted (it's no longer shared in any useful sense).
     */
    fun isGone(record: CkRecord, zone: NotesZone): Boolean =
        isPurged(record) || (zone.isShared && folderOf(record) == SpecialFolders.TRASH)

    /**
     * The cache key of a folder. Folder record names are unique UUIDs except the special
     * folders every zone has, so a sharer's "Notes" and "Recently Deleted" are keyed by
     * their owner too: their notes then never land in this account's own folders.
     */
    fun folderKey(recordName: String, zoneOwner: String?): String =
        if (zoneOwner != null && recordName in SpecialFolders.ALL) "$zoneOwner/$recordName" else recordName

    /** The CloudKit record name behind a [folderKey]. */
    fun folderRecordName(key: String): String = key.substringAfter('/')

    fun toNoteEntity(record: CkRecord, zone: NotesZone = NotesZone.Private): NoteEntity {
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
        return NoteEntity(
            recordName = record.recordName,
            folderRecordName = folderOf(record)?.let { folderKey(it, zone.owner) },
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
            zoneOwner = zone.owner,
            shareRecordName = record.shareRecordName,
        )
    }

    fun toFolderEntity(record: CkRecord, zone: NotesZone = NotesZone.Private): FolderEntity = FolderEntity(
        recordName = folderKey(record.recordName, zone.owner),
        title = record.encryptedString("TitleEncrypted")?.takeIf { it.isNotBlank() }
            ?: if (record.recordName == SpecialFolders.DEFAULT) "Notes" else "Untitled Folder",
        parentRecordName = (record.reference("ParentFolder") ?: record.parentRecordName)?.let { folderKey(it, zone.owner) },
        zoneOwner = zone.owner,
        shareRecordName = record.shareRecordName,
    )

    fun toShareEntity(record: CkRecord, zone: NotesZone = NotesZone.Private): ShareEntity {
        val owner = record.participants.firstOrNull { it.isOwner }
        return ShareEntity(
            recordName = record.recordName,
            zoneOwner = zone.owner,
            ownerName = owner?.displayName,
            permission = record.currentUserPermission,
            participantNames = record.participants
                .filter { !it.isOwner && it.acceptanceStatus != "REMOVED" }
                .mapNotNull { it.displayName }
                .joinToString("\n"),
        )
    }

    private fun folderOf(record: CkRecord): String? = record.reference("Folder") ?: record.referenceList("Folders").firstOrNull()

    private const val TITLE_FALLBACK_LENGTH = 80
}
