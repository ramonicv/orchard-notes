package dev.rortega.orchardnotes.data

/** How a note or folder is shared. */
data class SharingInfo(
    /** Someone else shared it with this account (it lives in their zone). */
    val sharedWithMe: Boolean,
    /** Who shared it, when iCloud says. */
    val ownerName: String?,
    /** Everyone else in the share. */
    val participants: List<String>,
    /** False when this account may only view it. */
    val canEdit: Boolean,
)

/**
 * Resolves how notes and folders are shared: through their own share (a note or folder
 * shared on its own) or the closest shared folder above them, since everything inside a
 * shared folder is shared with it.
 */
class SharingIndex(folders: Collection<FolderEntity>, shares: Collection<ShareEntity>) {
    private val folders = folders.associateBy { it.recordName }
    private val shares = shares.associateBy { it.recordName }

    fun ofNote(note: NoteEntity): SharingInfo? = resolve(note.zoneOwner, note.shareRecordName, note.folderRecordName)

    fun ofNote(note: NoteSummary): SharingInfo? = resolve(note.zoneOwner, note.shareRecordName, note.folderRecordName)

    fun ofFolder(folderKey: String): SharingInfo? = folders[folderKey]?.let { resolve(it.zoneOwner, null, folderKey) }

    private fun resolve(zoneOwner: String?, shareRecordName: String?, folderKey: String?): SharingInfo? {
        var shared = shareRecordName != null
        var share = shareRecordName?.let(shares::get)
        var key = folderKey
        var depth = 0
        while (!shared && key != null && depth++ < MAX_DEPTH) {
            val folder = folders[key] ?: break
            if (folder.shareRecordName != null) {
                shared = true
                share = shares[folder.shareRecordName]
            }
            key = folder.parentRecordName
        }
        if (!shared && zoneOwner == null) return null
        return SharingInfo(
            sharedWithMe = zoneOwner != null,
            ownerName = share?.ownerName,
            participants = share?.participantNames?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            // Unknown permission: let iCloud decide when the edit is pushed.
            canEdit = zoneOwner == null || share?.permission != READ_ONLY,
        )
    }

    private companion object {
        const val READ_ONLY = "READ_ONLY"
        const val MAX_DEPTH = 32
    }
}
