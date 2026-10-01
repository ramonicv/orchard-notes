package dev.rortega.orchardnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingIndexTest {
    private fun note(zoneOwner: String? = null, share: String? = null, folder: String? = "F") = NoteEntity(
        recordName = "N", folderRecordName = folder, title = "", snippet = "", plainText = "", textData = null,
        creationDate = 0, modificationDate = 0, isPinned = false, recordChangeTag = null, firstAttachmentUti = null,
        isLocked = false, bodyUnavailable = false, zoneOwner = zoneOwner, shareRecordName = share,
    )

    @Test
    fun ownNotesInOwnFoldersAreNotShared() {
        val index = SharingIndex(listOf(FolderEntity("F", "Work", null)), emptyList())
        assertNull(index.ofNote(note()))
        assertNull(index.ofFolder("F"))
    }

    @Test
    fun notesInsideASharedFolderShareItsShareEvenNested() {
        val index = SharingIndex(
            listOf(FolderEntity("TRIP", "Trip", null, "_alex", "SHARE-1"), FolderEntity("F", "Days", "TRIP", "_alex")),
            listOf(ShareEntity("SHARE-1", "_alex", "Alex Kim", "READ_ONLY", "me@example.com\nsam@example.com")),
        )
        val sharing = index.ofNote(note(zoneOwner = "_alex"))!!
        assertTrue(sharing.sharedWithMe)
        assertEquals("Alex Kim", sharing.ownerName)
        assertEquals(listOf("me@example.com", "sam@example.com"), sharing.participants)
        assertFalse(sharing.canEdit)
        assertEquals(sharing, index.ofFolder("F"))
    }

    @Test
    fun aNoteSharedOnItsOwnUsesItsOwnShare() {
        val index = SharingIndex(emptyList(), listOf(ShareEntity("S", "_alex", "Alex", "READ_WRITE", "")))
        val sharing = index.ofNote(note(zoneOwner = "_alex", share = "S", folder = "_alex/DefaultFolder-CloudKit"))!!
        assertEquals("Alex", sharing.ownerName)
        assertTrue(sharing.canEdit)
        assertTrue(sharing.participants.isEmpty())
    }

    @Test
    fun notesThisAccountSharedAreAlwaysEditableAndKnowWhoTheyAreSharedWith() {
        val index = SharingIndex(emptyList(), listOf(ShareEntity("MINE", null, "Me", "READ_ONLY", "Sam")))
        val sharing = index.ofNote(note(share = "MINE"))!!
        assertFalse(sharing.sharedWithMe)
        assertTrue(sharing.canEdit)
        assertEquals(listOf("Sam"), sharing.participants)
    }

    @Test
    fun anUnknownShareStillCountsAsSharedWithEditsLeftToICloud() {
        val sharing = SharingIndex(emptyList(), emptyList()).ofNote(note(zoneOwner = "_alex", folder = null))!!
        assertTrue(sharing.sharedWithMe)
        assertNull(sharing.ownerName)
        assertTrue(sharing.canEdit)
    }
}
