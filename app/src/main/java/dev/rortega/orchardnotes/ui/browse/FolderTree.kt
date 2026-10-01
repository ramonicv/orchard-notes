package dev.rortega.orchardnotes.ui.browse

import dev.rortega.orchardnotes.data.FolderEntity
import dev.rortega.orchardnotes.data.SharingIndex
import dev.rortega.orchardnotes.data.SpecialFolders

/** What the notes list is showing. */
sealed interface FolderSelection {
    data object AllNotes : FolderSelection

    /** Every note shared with this account or by it, wherever it lives. */
    data object Shared : FolderSelection

    data class Folder(val recordName: String) : FolderSelection
}

enum class FolderKind { AllNotes, Shared, Default, Regular, SharedFolder, Trash }

data class FolderItem(
    val selection: FolderSelection,
    val title: String,
    val depth: Int,
    val count: Int,
    val kind: FolderKind,
    /** Under the title: who shared the folder with this account. */
    val subtitle: String? = null,
    /** Someone else's folder, shared with this account. */
    val sharedWithMe: Boolean = false,
)

/**
 * Orders folders the way Apple Notes does: "All iCloud", "Shared" (when anything is),
 * then the default "Notes" folder, then the rest alphabetically with subfolders nested
 * under their parent, and "Recently Deleted" last (only when it has notes). Folders
 * shared with this account sit among the others, marked as shared.
 */
fun buildFolderItems(
    folders: List<FolderEntity>,
    counts: Map<String?, Int>,
    sharing: SharingIndex? = null,
    sharedCount: Int = 0,
): List<FolderItem> {
    val byName = folders.associateBy { it.recordName }.toMutableMap()
    // Notes can reference the default folder before (or without) its record syncing.
    if (SpecialFolders.DEFAULT !in byName && (counts[SpecialFolders.DEFAULT] ?: 0) > 0) {
        byName[SpecialFolders.DEFAULT] = FolderEntity(SpecialFolders.DEFAULT, "Notes", null)
    }
    byName.remove(SpecialFolders.TRASH)

    val children = byName.values
        .groupBy { folder -> folder.parentRecordName?.takeIf { it in byName && it != folder.recordName } }
    val comparator = compareBy<FolderEntity> { it.recordName != SpecialFolders.DEFAULT }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

    val items = mutableListOf<FolderItem>()
    val total = counts.filterKeys { it != SpecialFolders.TRASH }.values.sum()
    items += FolderItem(FolderSelection.AllNotes, "All iCloud", 0, total, FolderKind.AllNotes)
    if (sharedCount > 0) items += FolderItem(FolderSelection.Shared, "Shared", 0, sharedCount, FolderKind.Shared)

    val visited = mutableSetOf<String>()
    fun visit(folder: FolderEntity, depth: Int) {
        if (!visited.add(folder.recordName)) return
        val shared = sharing?.ofFolder(folder.recordName)
        items += FolderItem(
            selection = FolderSelection.Folder(folder.recordName),
            title = folder.title,
            depth = depth,
            count = counts[folder.recordName] ?: 0,
            kind = when {
                folder.recordName == SpecialFolders.DEFAULT -> FolderKind.Default
                shared != null -> FolderKind.SharedFolder
                else -> FolderKind.Regular
            },
            // Only on the shared folder itself, not every folder nested in it.
            subtitle = shared?.ownerName?.takeIf { shared.sharedWithMe && folder.shareRecordName != null }?.let { "From $it" },
            sharedWithMe = shared?.sharedWithMe == true,
        )
        children[folder.recordName].orEmpty().sortedWith(comparator).forEach { visit(it, depth + 1) }
    }
    children[null].orEmpty().sortedWith(comparator).forEach { visit(it, 0) }
    // Anything unreachable (a parent cycle) still gets listed at the top level.
    byName.values.filter { it.recordName !in visited }.sortedWith(comparator).forEach { visit(it, 0) }

    val trashCount = counts[SpecialFolders.TRASH] ?: 0
    if (trashCount > 0) {
        items += FolderItem(FolderSelection.Folder(SpecialFolders.TRASH), "Recently Deleted", 0, trashCount, FolderKind.Trash)
    }
    return items
}
