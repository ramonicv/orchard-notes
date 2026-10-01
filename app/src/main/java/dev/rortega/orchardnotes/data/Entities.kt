package dev.rortega.orchardnotes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A note as cached from CloudKit. */
@Entity(tableName = "notes", indices = [Index("folderRecordName"), Index("modificationDate")])
data class NoteEntity(
    @PrimaryKey val recordName: String,
    val folderRecordName: String?,
    val title: String,
    val snippet: String,
    /** Decoded visible text, for search and previews. */
    val plainText: String,
    /** `TextDataEncrypted` exactly as received (base64 of the compressed body). */
    val textData: String?,
    val creationDate: Long,
    val modificationDate: Long,
    val isPinned: Boolean,
    val recordChangeTag: String?,
    /** UTI of the note's first attachment, when it has any. */
    val firstAttachmentUti: String?,
    /** Locked with a password; content can't be read here. */
    val isLocked: Boolean,
    /** Body couldn't be decoded or isn't inline (too large, encrypted, or an unknown format). */
    val bodyUnavailable: Boolean,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val recordName: String,
    val title: String,
    val parentRecordName: String?,
)

/** The lightweight projection used by note lists. */
data class NoteSummary(
    val recordName: String,
    val folderRecordName: String?,
    val title: String,
    val snippet: String,
    val modificationDate: Long,
    val isPinned: Boolean,
    val isLocked: Boolean,
    val firstAttachmentUti: String?,
)

data class FolderCount(
    @ColumnInfo(name = "folderRecordName") val folderRecordName: String?,
    @ColumnInfo(name = "count") val count: Int,
)

/**
 * A local edit not yet confirmed by iCloud. Saved before any network call, so edits
 * survive being offline or the app closing. [baseJson] is the formatted content the edit
 * started from (null for a note created here), used to merge with changes made elsewhere.
 */
@Entity(tableName = "pending_edits")
data class PendingEditEntity(
    @PrimaryKey val recordName: String,
    val isNew: Boolean,
    val folderRecordName: String?,
    val baseJson: String?,
    val desiredJson: String,
    val title: String,
    val snippet: String,
    val plainText: String,
    val updatedAt: Long,
    /** Why the last push failed, when it needs the user's attention. */
    val error: String? = null,
    /** Pushing can't succeed without the user deciding what to do (see [error]). */
    val blocked: Boolean = false,
)
