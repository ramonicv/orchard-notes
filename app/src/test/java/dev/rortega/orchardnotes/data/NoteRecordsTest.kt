package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.notes.Fixtures
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NoteRecordsTest {
    private fun record(text: String) = CkRecord.parse(Json.parseToJsonElement(text))!!

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun noteRecordBecomesACacheRowWithDecodedText() {
        val body = Base64.getEncoder().encodeToString(Fixtures.compressed(Fixtures.PLAIN))
        val entity = NoteRecords.toNoteEntity(
            record(
                """
                {"recordName":"N1","recordType":"Note","recordChangeTag":"t1","fields":{
                  "TitleEncrypted":{"type":"ENCRYPTED_BYTES","value":"${b64("Test Note")}"},
                  "SnippetEncrypted":{"type":"ENCRYPTED_BYTES","value":"${b64("snippet")}"},
                  "TextDataEncrypted":{"type":"ENCRYPTED_BYTES","value":"$body"},
                  "CreationDate":{"type":"TIMESTAMP","value":1000},
                  "ModificationDate":{"type":"TIMESTAMP","value":2000},
                  "IsPinned":{"type":"INT64","value":1},
                  "Folder":{"type":"REFERENCE","value":{"recordName":"F1"}}}}
                """,
            ),
        )
        assertEquals("Test Note", entity.title)
        assertEquals("snippet", entity.snippet)
        assertTrue(entity.plainText.startsWith("Test Note"))
        assertEquals("F1", entity.folderRecordName)
        assertEquals(2000L, entity.modificationDate)
        assertTrue(entity.isPinned)
        assertFalse(entity.bodyUnavailable)
    }

    @Test
    fun undecodableBodyIsMarkedUnavailableButKeepsItsTitle() {
        val entity = NoteRecords.toNoteEntity(
            record(
                """
                {"recordName":"N2","recordType":"Note","fields":{
                  "TitleEncrypted":{"type":"STRING","value":"Plain title"},
                  "TextDataEncrypted":{"type":"ENCRYPTED_BYTES","value":"${b64("not a protobuf")}"}}}
                """,
            ),
        )
        assertEquals("Plain title", entity.title)
        assertTrue(entity.bodyUnavailable)
    }

    @Test
    fun purgedAndTrashedNotesAreDistinguished() {
        val purged = record("""{"recordName":"N3","recordType":"Note","fields":{"Deleted":{"type":"INT64","value":1}}}""")
        val trashed = record(
            """{"recordName":"N4","recordType":"Note","fields":{"Folder":{"type":"REFERENCE","value":{"recordName":"TrashFolder-CloudKit"}}}}""",
        )
        assertTrue(NoteRecords.isPurged(purged))
        assertFalse(NoteRecords.isPurged(trashed))
        assertEquals(SpecialFolders.TRASH, NoteRecords.toNoteEntity(trashed).folderRecordName)
    }

    @Test
    fun nestedFolderKeepsItsParent() {
        val folder = NoteRecords.toFolderEntity(
            record(
                """{"recordName":"F2","recordType":"Folder","fields":{
                   "TitleEncrypted":{"type":"ENCRYPTED_BYTES","value":"${b64("Recipes")}"},
                   "ParentFolder":{"type":"REFERENCE","value":{"recordName":"F1"}}}}""",
            ),
        )
        assertEquals(FolderEntity("F2", "Recipes", "F1"), folder)
    }
}

class AttachmentImagesTest {
    @Test
    fun assetUrlsFillInCloudKitsFilenamePlaceholder() {
        val value = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"downloadURL":"https://cvws.icloud-content.com/B/AbC/${'$'}{f}?o=x","fileChecksum":"c"}""",
        )
        assertEquals("https://cvws.icloud-content.com/B/AbC/file?o=x", AttachmentImages.assetUrl(value))
        assertEquals(null, AttachmentImages.assetUrl(kotlinx.serialization.json.Json.parseToJsonElement("""{"recordName":"R"}""")))
    }
}
