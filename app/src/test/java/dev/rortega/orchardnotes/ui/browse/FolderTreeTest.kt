package dev.rortega.orchardnotes.ui.browse

import dev.rortega.orchardnotes.data.FolderEntity
import dev.rortega.orchardnotes.data.SpecialFolders
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeTest {
    @Test
    fun ordersLikeAppleNotesWithNestingAndTrashLast() {
        val items = buildFolderItems(
            folders = listOf(
                FolderEntity("work", "Work", null),
                FolderEntity("recipes", "recipes", null),
                FolderEntity("desserts", "Desserts", "recipes"),
                FolderEntity(SpecialFolders.DEFAULT, "Notes", null),
            ),
            counts = mapOf(SpecialFolders.DEFAULT to 3, "desserts" to 2, "work" to 1, SpecialFolders.TRASH to 4),
        )
        assertEquals(
            listOf("All iCloud" to 0, "Notes" to 0, "recipes" to 0, "Desserts" to 1, "Work" to 0, "Recently Deleted" to 0),
            items.map { it.title to it.depth },
        )
        assertEquals(6, items.first().count) // trash excluded from All iCloud
        assertEquals(FolderKind.Trash, items.last().kind)
    }

    @Test
    fun synthesizesTheDefaultFolderAndSurvivesParentCycles() {
        val items = buildFolderItems(
            folders = listOf(FolderEntity("a", "A", "b"), FolderEntity("b", "B", "a")),
            counts = mapOf(SpecialFolders.DEFAULT to 1),
        )
        assertEquals(listOf("All iCloud", "Notes", "A", "B"), items.map { it.title })
    }

    @Test
    fun sharedFoldersAreMarkedAndTheSharedListAppearsWhenThereIsSomethingShared() {
        val folders = listOf(
            FolderEntity("work", "Work", null),
            FolderEntity("trip", "Trip", null, zoneOwner = "_alex", shareRecordName = "S1"),
            FolderEntity("days", "Days", "trip", zoneOwner = "_alex"),
            FolderEntity("mine", "Mine", null, shareRecordName = "S2"),
        )
        val sharing = dev.rortega.orchardnotes.data.SharingIndex(
            folders,
            listOf(dev.rortega.orchardnotes.data.ShareEntity("S1", "_alex", "Alex", "READ_WRITE", "")),
        )
        val items = buildFolderItems(folders, mapOf("trip" to 2, "_alex/DefaultFolder-CloudKit" to 1), sharing, sharedCount = 3)

        assertEquals(listOf("All iCloud", "Shared", "Mine", "Trip", "Days", "Work"), items.map { it.title })
        assertEquals(3, items.first { it.kind == FolderKind.AllNotes }.count)
        assertEquals(3, items.first { it.kind == FolderKind.Shared }.count)
        val trip = items.first { it.title == "Trip" }
        assertEquals(FolderKind.SharedFolder, trip.kind)
        assertEquals("From Alex", trip.subtitle)
        assertEquals(true, trip.sharedWithMe)
        // Nested folders are shared with it, but only the shared folder names its owner.
        assertEquals(null, items.first { it.title == "Days" }.subtitle)
        val mine = items.first { it.title == "Mine" }
        assertEquals(FolderKind.SharedFolder, mine.kind)
        assertEquals(false, mine.sharedWithMe)
        assertEquals(FolderKind.Regular, items.first { it.title == "Work" }.kind)
    }

    @Test
    fun noSharedListWithoutSharedNotes() {
        assertEquals(listOf(FolderKind.AllNotes), buildFolderItems(emptyList(), emptyMap()).map { it.kind })
    }
}
