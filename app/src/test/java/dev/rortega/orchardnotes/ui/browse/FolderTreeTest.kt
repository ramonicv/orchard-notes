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
}
