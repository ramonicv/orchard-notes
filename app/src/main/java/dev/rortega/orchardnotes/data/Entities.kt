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
    /**
     * Whose zone the note lives in: null for the account's own notes, otherwise the user
     * record name of the person who shared it with this account.
     */
    val zoneOwner: String? = null,
    /** The `cloudkit.share` this note is the root of, when it was shared on its own. */
    val shareRecordName: String? = null,
    /** When iCloud's version was last stored ([LocalClock]): orders it against local edits. */
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,
)

@Entity(tableName = "folders")
data class FolderEntity(
    /** The record name; a sharer's special folders are keyed by owner too (see [NoteRecords.folderKey]). */
    @PrimaryKey val recordName: String,
    val title: String,
    val parentRecordName: String?,
    /** See [NoteEntity.zoneOwner]. */
    val zoneOwner: String? = null,
    /** The `cloudkit.share` this folder is the root of, when it was shared. */
    val shareRecordName: String? = null,
)

/** A `cloudkit.share`: who shared a note or folder, with whom, and this account's access to it. */
@Entity(tableName = "shares")
data class ShareEntity(
    @PrimaryKey val recordName: String,
    /** The zone the share lives in (see [NoteEntity.zoneOwner]); null for shares this account owns. */
    val zoneOwner: String?,
    /** The person who shared, for display. */
    val ownerName: String?,
    /** This account's permission: `READ_WRITE` or `READ_ONLY`; null when iCloud didn't say. */
    val permission: String?,
    /** Everyone else in the share, one display name per line. */
    val participantNames: String,
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
    val zoneOwner: String?,
    val shareRecordName: String?,
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

/** A queued structural change (move, permanent delete, new folder), pushed like [PendingEditEntity]. */
@Entity(tableName = "pending_ops", primaryKeys = ["type", "recordName"])
data class PendingOpEntity(
    val type: String,
    val recordName: String,
    /** MOVE: destination folder. */
    val folderRecordName: String? = null,
    /** CREATE_FOLDER: the folder's name and optional parent. */
    val title: String? = null,
    val parentRecordName: String? = null,
    val createdAt: Long,
    val error: String? = null,
) {
    companion object {
        const val MOVE = "MOVE"
        const val PURGE = "PURGE"
        const val CREATE_FOLDER = "CREATE_FOLDER"
    }
}
