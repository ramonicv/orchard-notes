package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.auth.ClientParams
import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.cloudkit.NotesZone
import dev.rortega.orchardnotes.notes.doc.AttachmentInfo
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.FormatReconcile
import dev.rortega.orchardnotes.notes.doc.FormatResult
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteCompression
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.doc.NoteDocument
import dev.rortega.orchardnotes.notes.doc.NoteEditing
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import dev.rortega.orchardnotes.notes.ParagraphMerge
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class NoteWriterTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val server = MockWebServer()
    private val dao = FakeNotesDao()
    private val applied = mutableListOf<CkRecord>()
    private val appliedZones = mutableListOf<NotesZone>()

    /** Every request: its path and JSON body. */
    private val requests = mutableListOf<Pair<String, JsonObject>>()

    /** When set, records/modify answers with this per-record error. */
    private var modifyError: String? = null
    private val ourReplica = ByteArray(16) { 0x42 }
    private val deviceReplica = ByteArray(16) { 0x13 }
    private lateinit var writer: NoteWriter

    /** The server-side note: its current body and change tag. */
    private var serverBody: String? = null
    private var serverTag = "tag-1"
    private var conflictsToReturn = 0
    private val modifyBodies = mutableListOf<JsonObject>()

    private object Params : ClientParams {
        override val clientId = "C"
        override val clientBuildNumber = "B"
        override val clientMasteringNumber = "M"
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += path to json.parseToJsonElement(request.body!!.utf8()).jsonObject
                modifyError?.takeIf { path.endsWith("records/modify") }?.let { code ->
                    return respond("""{"records":[{"recordName":"NOTE","serverErrorCode":"$code","reason":"denied"}]}""")
                }
                return when {
                    path.endsWith("records/lookup") -> respond(
                        serverBody?.let { """{"records":[${recordJson("NOTE", it, serverTag)}]}""" }
                            ?: """{"records":[{"recordName":"NOTE","serverErrorCode":"NOT_FOUND"}]}""",
                    )
                    path.endsWith("records/modify") -> {
                        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
                        modifyBodies += body
                        val record = body["operations"]!!.jsonArray[0].jsonObject["record"]!!.jsonObject
                        val name = record["recordName"]!!.jsonPrimitive.content
                        if (conflictsToReturn > 0) {
                            conflictsToReturn--
                            serverTag += "x"
                            respond("""{"records":[{"recordName":"$name","serverErrorCode":"CONFLICT","reason":"oplock"}]}""")
                        } else {
                            val text = record["fields"]!!.jsonObject["TextDataEncrypted"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                            if (name == "NOTE" && text != null) {
                                serverBody = text
                                serverTag = "tag-saved"
                            }
                            // Echo the written record back the way CloudKit does, with a new change tag.
                            val saved = JsonObject(record + ("recordChangeTag" to kotlinx.serialization.json.JsonPrimitive("tag-saved")))
                            respond("""{"records":[$saved]}""")
                        }
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        val account = IcloudAccount("1", "a", null, server.url("/").toString().trimEnd('/'))
        val client = CloudKitClient(OkHttpClient(), json, Params) { account }
        writer = NoteWriter(client, dao, { records, zone ->
            applied += records
            appliedZones += zone
        }, json) { ourReplica }
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun recordJson(name: String, textData: String, tag: String) = """
        {"recordName":"$name","recordType":"Note","recordChangeTag":"$tag","parent":{"recordName":"DefaultFolder-CloudKit"},
         "fields":{
           "TextDataEncrypted":{"type":"ENCRYPTED_BYTES","value":"$textData"},
           "TitleEncrypted":{"type":"ENCRYPTED_BYTES","value":"VA=="},
           "Folder":{"type":"REFERENCE","value":{"recordName":"DefaultFolder-CloudKit","action":"VALIDATE"}},
           "CreationDate":{"type":"TIMESTAMP","value":1000},
           "ReplicaIDToNotesVersionDataEncrypted":{"type":"ENCRYPTED_BYTES","value":"AAEC"}}}
    """.trimIndent()

    private fun paragraphs(vararg lines: Pair<String, ParagraphKind>): List<FormatParagraph> = ParagraphMerge.withOffsets(
        lines.map { (text, kind) ->
            FormatParagraph(kind = kind, text = text, spans = if (text.isEmpty()) emptyList() else listOf(InlineSpan(text.length, InlineStyle.Plain)))
        },
    )

    /** A note as another Apple device would have written it. */
    private fun serverNote(content: List<FormatParagraph>, configure: (NoteDocument) -> Unit = {}): String {
        val doc = NoteEditing.buildInitialDocument(content.joinToString("\n") { it.text }, deviceReplica)
        FormatReconcile.reconcile(doc, content, deviceReplica)
        configure(doc)
        return Base64.getEncoder().encodeToString(NoteCompression.compress(doc.encode()))
    }

    private suspend fun pend(
        base: List<FormatParagraph>?,
        desired: List<FormatParagraph>,
        isNew: Boolean = false,
        name: String = "NOTE",
        folder: String = "DefaultFolder-CloudKit",
    ) =
        dao.upsertPending(
            PendingEditEntity(
                recordName = name, isNew = isNew, folderRecordName = folder,
                baseJson = base?.let(writer::encodeParagraphs), desiredJson = writer.encodeParagraphs(desired),
                title = "", snippet = "", plainText = "", updatedAt = 1,
            ),
        )

    private fun uploaded(index: Int = modifyBodies.lastIndex): Pair<JsonObject, NoteContent> {
        val op = modifyBodies[index]["operations"]!!.jsonArray[0].jsonObject
        val fields = op["record"]!!.jsonObject["fields"]!!.jsonObject
        val body = fields["TextDataEncrypted"]!!.jsonObject["value"]!!.jsonPrimitive.content
        return op to NoteContent.decode(Base64.getDecoder().decode(body))
    }

    private fun kinds(content: NoteContent) = (content.format() as FormatResult.Ok).paragraphs.map { it.text to it.kind }

    private val title = "Groceries" to ParagraphKind.Title
    private val eggs = "eggs" to ParagraphKind.Checklist
    private val milk = "milk" to ParagraphKind.Checklist

    @Test
    fun updatePushesACrdtEditWithEchoedFieldsAndClearsThePendingEdit() = runTest {
        val base = paragraphs(title, eggs)
        serverBody = serverNote(base)
        pend(base, paragraphs(title, eggs, milk))

        assertEquals(PushOutcome.Pushed, writer.push("NOTE"))

        val (op, content) = uploaded()
        assertEquals("update", op["operationType"]!!.jsonPrimitive.content)
        val record = op["record"]!!.jsonObject
        assertEquals("tag-1", record["recordChangeTag"]!!.jsonPrimitive.content)
        assertEquals("DefaultFolder-CloudKit", record["parent"]!!.jsonObject["recordName"]!!.jsonPrimitive.content)
        val fields = record["fields"]!!.jsonObject
        assertEquals("AAEC", fields["ReplicaIDToNotesVersionDataEncrypted"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Groceries", String(Base64.getDecoder().decode(fields["TitleEncrypted"]!!.jsonObject["value"]!!.jsonPrimitive.content)))
        assertEquals(listOf(title, eggs, milk), kinds(content))
        assertNull(dao.pending["NOTE"])
        assertEquals("tag-saved", applied.single().recordChangeTag)

        // The device's original characters are still there, with our insert appended under our replica.
        val doc = NoteDocument.parse(NoteCompression.decompress(Base64.getDecoder().decode(serverBody)))
        assertTrue(doc.replicas.any { it.id.contentEquals(deviceReplica) })
        assertTrue(doc.replicas.any { it.id.contentEquals(ourReplica) })
    }

    @Test
    fun aWriteConflictRetriesFromAFreshCopy() = runTest {
        val base = paragraphs(title, eggs)
        serverBody = serverNote(base)
        conflictsToReturn = 1
        pend(base, paragraphs(title, eggs, milk))

        assertEquals(PushOutcome.Pushed, writer.push("NOTE"))
        assertEquals(2, modifyBodies.size)
        assertEquals(listOf(title, eggs, milk), kinds(uploaded().second))
    }

    @Test
    fun changesMadeElsewhereInOtherParagraphsAreMerged() = runTest {
        val base = paragraphs(title, eggs, "" to ParagraphKind.Body, "notes" to ParagraphKind.Body)
        // Meanwhile on the Mac: "bread" added at the end.
        serverBody = serverNote(paragraphs(title, eggs, "" to ParagraphKind.Body, "notes" to ParagraphKind.Body, "bread" to ParagraphKind.Body))
        // Here: "milk" added after eggs.
        pend(base, paragraphs(title, eggs, milk, "" to ParagraphKind.Body, "notes" to ParagraphKind.Body))

        assertEquals(PushOutcome.Pushed, writer.push("NOTE"))
        assertEquals(
            listOf(title, eggs, milk, "" to ParagraphKind.Body, "notes" to ParagraphKind.Body, "bread" to ParagraphKind.Body),
            kinds(uploaded().second),
        )
    }

    @Test
    fun overlappingChangesAreSavedAsASeparateNote() = runTest {
        val base = paragraphs(title, eggs)
        serverBody = serverNote(paragraphs(title, "eggs (dozen)" to ParagraphKind.Checklist))
        pend(base, paragraphs(title, "eggs (6)" to ParagraphKind.Checklist))

        val outcome = writer.push("NOTE")

        assertTrue(outcome is PushOutcome.SavedAsCopy)
        val (op, content) = uploaded()
        assertEquals("create", op["operationType"]!!.jsonPrimitive.content)
        assertEquals(listOf(title, "eggs (6)" to ParagraphKind.Checklist), kinds(content))
        assertNull(dao.pending["NOTE"])
        // The original note on the server was left alone.
        assertEquals(1, modifyBodies.size)
    }

    @Test
    fun editsThatWouldDeleteAnAttachmentAreBlocked() = runTest {
        val base = paragraphs(title, "￼" to ParagraphKind.Body)
        serverBody = serverNote(base) { doc ->
            // Mark the placeholder as an attachment, as Apple's clients do.
            val last = doc.attributeRuns.removeAt(doc.attributeRuns.lastIndex)
            doc.attributeRuns += last.copy().apply { length -= 1 }
            doc.attributeRuns += dev.rortega.orchardnotes.notes.doc.AttributeRun.plain(1).apply { attachmentInfo = AttachmentInfo("IMG", "public.jpeg") }
            doc.attributeRuns.removeAll { it.length == 0 }
        }
        pend(base, paragraphs(title, "" to ParagraphKind.Body))

        val outcome = writer.push("NOTE")

        assertTrue(outcome is PushOutcome.Blocked)
        assertTrue(dao.pending["NOTE"]!!.blocked)
        assertTrue(modifyBodies.isEmpty())
    }

    @Test
    fun aNoteDeletedElsewhereBlocksInsteadOfRecreating() = runTest {
        serverBody = null
        pend(paragraphs(title), paragraphs(title, eggs))
        assertTrue(writer.push("NOTE") is PushOutcome.Blocked)
        assertTrue(modifyBodies.isEmpty())
    }

    @Test
    fun newNotesAreCreatedWithTheWebClientsFieldsAndFormatting() = runTest {
        pend(null, paragraphs(title, "" to ParagraphKind.Body, eggs), isNew = true, name = "NEW-1")

        assertEquals(PushOutcome.Pushed, writer.push("NEW-1"))

        val (op, content) = uploaded()
        assertEquals("create", op["operationType"]!!.jsonPrimitive.content)
        val record = op["record"]!!.jsonObject
        assertEquals("NEW-1", record["recordName"]!!.jsonPrimitive.content)
        val fields = record["fields"]!!.jsonObject
        assertTrue(fields["ModificationDate"]!!.jsonObject["value"]!!.jsonPrimitive.content.toLong() > 0)
        assertEquals(JsonObject(emptyMap()), fields["FirstAttachmentThumbnail"])
        assertEquals(listOf(title, "" to ParagraphKind.Body, eggs), kinds(content))
        assertNull(dao.pending["NEW-1"])
    }

    @Test
    fun blankNewNotesAreDroppedInsteadOfCreated() = runTest {
        pend(null, paragraphs("" to ParagraphKind.Body), isNew = true, name = "NEW-2")
        assertEquals(PushOutcome.Pushed, writer.push("NEW-2"))
        assertTrue(modifyBodies.isEmpty())
        assertNull(dao.pending["NEW-2"])
    }

    @Test
    fun alreadyAppliedEditsFinishWithoutWriting() = runTest {
        val content = paragraphs(title, eggs)
        serverBody = serverNote(content)
        pend(paragraphs(title), content)
        assertEquals(PushOutcome.NothingToDo, writer.push("NOTE"))
        assertTrue(modifyBodies.isEmpty())
        assertNull(dao.pending["NOTE"])
    }

    private fun field(op: JsonObject, name: String) =
        op["record"]!!.jsonObject["fields"]!!.jsonObject[name]?.jsonObject?.get("value")

    @Test
    fun movingANoteRewritesBothFolderReferencesAndKeepsItsBody() = runTest {
        serverBody = serverNote(paragraphs(title))
        val op = PendingOpEntity(PendingOpEntity.MOVE, "NOTE", folderRecordName = "FOLDER-2", createdAt = 5)
        dao.upsertOp(op)

        writer.pushOp(op)

        val sent = modifyBodies.single()["operations"]!!.jsonArray[0].jsonObject
        assertEquals("FOLDER-2", field(sent, "Folder")!!.jsonObject["recordName"]!!.jsonPrimitive.content)
        assertEquals("FOLDER-2", field(sent, "Folders")!!.jsonArray[0].jsonObject["recordName"]!!.jsonPrimitive.content)
        assertTrue(field(sent, "FoldersModificationDate") != null)
        assertNull(field(sent, "Deleted"))
        assertEquals(serverBody, field(sent, "TextDataEncrypted")!!.jsonPrimitive.content)
        assertTrue(dao.ops.isEmpty())
    }

    @Test
    fun permanentDeleteMovesToTrashAndMarksDeleted() = runTest {
        serverBody = serverNote(paragraphs(title))
        val op = PendingOpEntity(PendingOpEntity.PURGE, "NOTE", createdAt = 5)
        dao.upsertOp(op)

        writer.pushOp(op)

        val sent = modifyBodies.single()["operations"]!!.jsonArray[0].jsonObject
        assertEquals(SpecialFolders.TRASH, field(sent, "Folder")!!.jsonObject["recordName"]!!.jsonPrimitive.content)
        assertEquals("1", field(sent, "Deleted")!!.jsonPrimitive.content)
        assertTrue(dao.ops.isEmpty())
    }

    @Test
    fun movesOfNotesAlreadyThereOrGoneAreDropped() = runTest {
        serverBody = serverNote(paragraphs(title))
        val alreadyThere = PendingOpEntity(PendingOpEntity.MOVE, "NOTE", folderRecordName = "DefaultFolder-CloudKit", createdAt = 1)
        dao.upsertOp(alreadyThere)
        writer.pushOp(alreadyThere)
        serverBody = null
        val gone = PendingOpEntity(PendingOpEntity.PURGE, "NOTE", createdAt = 2)
        dao.upsertOp(gone)
        writer.pushOp(gone)
        assertTrue(modifyBodies.isEmpty())
        assertTrue(dao.ops.isEmpty())
    }

    @Test
    fun newFoldersAreCreatedWithAnEncodedTitleAndParent() = runTest {
        val op = PendingOpEntity(PendingOpEntity.CREATE_FOLDER, "FOLDER-NEW", title = "Recipes", parentRecordName = "FOLDER-1", createdAt = 1)
        dao.upsertOp(op)

        writer.pushOp(op)

        val sent = modifyBodies.single()["operations"]!!.jsonArray[0].jsonObject
        assertEquals("create", sent["operationType"]!!.jsonPrimitive.content)
        val record = sent["record"]!!.jsonObject
        assertEquals("Folder", record["recordType"]!!.jsonPrimitive.content)
        assertEquals("FOLDER-1", record["parent"]!!.jsonObject["recordName"]!!.jsonPrimitive.content)
        assertEquals("Recipes", String(Base64.getDecoder().decode(field(sent, "TitleEncrypted")!!.jsonPrimitive.content)))
        assertEquals("FOLDER-1", field(sent, "ParentFolder")!!.jsonObject["recordName"]!!.jsonPrimitive.content)
        assertEquals("Folder", applied.single().recordType)
    }

    // --- shared notes -------------------------------------------------------------------

    private fun cachedNote(name: String = "NOTE", zoneOwner: String? = null, share: String? = null, textData: String? = null) = NoteEntity(
        recordName = name, folderRecordName = "DefaultFolder-CloudKit", title = "", snippet = "", plainText = "",
        textData = textData, creationDate = 0, modificationDate = 0, isPinned = false, recordChangeTag = "tag-1",
        firstAttachmentUti = null, isLocked = false, bodyUnavailable = false, zoneOwner = zoneOwner, shareRecordName = share,
    )

    @Test
    fun notesSharedWithMeAreWrittenInTheSharersZone() = runTest {
        val base = paragraphs(title, eggs)
        serverBody = serverNote(base)
        dao.upsertNotes(listOf(cachedNote(zoneOwner = "_alex")))
        pend(base, paragraphs(title, eggs, milk))

        assertEquals(PushOutcome.Pushed, writer.push("NOTE"))

        assertEquals(listOf("lookup", "modify"), requests.map { it.first.substringAfterLast('/') })
        for ((path, body) in requests) {
            assertTrue(path, path.contains("/production/shared/records/"))
            assertEquals("_alex", body["zoneID"]!!.jsonObject["ownerRecordName"]!!.jsonPrimitive.content)
        }
        assertEquals(listOf(NotesZone("_alex")), appliedZones)
    }

    @Test
    fun overlappingChangesToASharedNoteMergeInsteadOfMakingACopy() = runTest {
        val base = paragraphs(title, eggs)
        serverBody = serverNote(paragraphs(title, "eggs (dozen)" to ParagraphKind.Checklist))
        // Shared by this account: in its own zone, but others edit it too.
        dao.upsertNotes(listOf(cachedNote(share = "SHARE-1")))
        pend(base, paragraphs(title, "eggs (6)" to ParagraphKind.Checklist))

        assertEquals(PushOutcome.Pushed, writer.push("NOTE"))

        val (op, content) = uploaded()
        assertEquals("update", op["operationType"]!!.jsonPrimitive.content)
        assertEquals(listOf(title, "eggs (dozen) (6)" to ParagraphKind.Checklist), kinds(content))
        assertEquals(1, modifyBodies.size)
    }

    @Test
    fun aSharedNoteICanOnlyViewBlocksInsteadOfRetrying() = runTest {
        val base = paragraphs(title, eggs)
        serverBody = serverNote(base)
        dao.upsertNotes(listOf(cachedNote(zoneOwner = "_alex")))
        pend(base, paragraphs(title, eggs, milk))
        modifyError = "ACCESS_DENIED"

        assertTrue(writer.push("NOTE") is PushOutcome.Blocked)
        assertTrue(dao.pending["NOTE"]!!.blocked)
        assertTrue(dao.pending["NOTE"]!!.error!!.contains("view"))
    }

    @Test
    fun newNotesInASharedFolderAreCreatedUnderItInTheSharersZone() = runTest {
        dao.upsertFolders(listOf(FolderEntity("FOLDER-1", "Trip", null, zoneOwner = "_alex", shareRecordName = "SHARE-1")))
        pend(null, paragraphs(title, eggs), isNew = true, name = "NEW-2", folder = "FOLDER-1")

        assertEquals(PushOutcome.Pushed, writer.push("NEW-2"))

        val (path, body) = requests.single()
        assertTrue(path.endsWith("/production/shared/records/modify"))
        assertEquals("_alex", body["zoneID"]!!.jsonObject["ownerRecordName"]!!.jsonPrimitive.content)
        val record = body["operations"]!!.jsonArray[0].jsonObject["record"]!!.jsonObject
        assertEquals("FOLDER-1", record["parent"]!!.jsonObject["recordName"]!!.jsonPrimitive.content)
        assertEquals("true", record["createShortGUID"]!!.jsonPrimitive.content)
        val folder = record["fields"]!!.jsonObject["Folder"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("FOLDER-1", folder["recordName"]!!.jsonPrimitive.content)
        assertEquals("_alex", folder["zoneID"]!!.jsonObject["ownerRecordName"]!!.jsonPrimitive.content)
    }

    @Test
    fun notesSharedWithMeAreNeverMovedOrDeleted() = runTest {
        dao.upsertNotes(listOf(cachedNote(zoneOwner = "_alex")))
        val move = PendingOpEntity(PendingOpEntity.MOVE, "NOTE", folderRecordName = "work", createdAt = 1)
        dao.upsertOp(move)

        writer.pushOp(move)

        assertTrue(requests.isEmpty())
        assertTrue(dao.ops.values.single().error != null)
    }

    @Test
    fun pendingEditsToSharedNotesAreRebasedOntoWhatArrives() = runTest {
        val base = paragraphs(title, "buy milk" to ParagraphKind.Checklist)
        val theirs = paragraphs(title, "buy milk" to ParagraphKind.Checklist, "bread" to ParagraphKind.Checklist)
        val note = cachedNote(zoneOwner = "_alex", textData = serverNote(theirs))
        dao.upsertNotes(listOf(note))
        pend(base, paragraphs(title, "buy oat milk" to ParagraphKind.Checklist))

        writer.rebaseOnServerChanges(listOf(note))

        val pending = dao.pending["NOTE"]!!
        assertTrue(dev.rortega.orchardnotes.notes.doc.NoteFormat.formatsEqual(theirs, ParagraphMerge.withOffsets(writer.decodeParagraphs(pending.baseJson!!))))
        assertEquals(listOf("Groceries", "buy oat milk", "bread"), writer.decodeParagraphs(pending.desiredJson).map { it.text })
    }
}
