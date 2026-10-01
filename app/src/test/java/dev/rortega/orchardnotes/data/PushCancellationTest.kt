package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.auth.ClientParams
import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.cloudkit.SetupClient
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.FormatReconcile
import dev.rortega.orchardnotes.notes.doc.FormatResult
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteCompression
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.doc.NoteEditing
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Pushes and syncs that overlap other work: the next autosave landing mid-push, or what
 * started them going away (the note closing, the app leaving the screen) and cancelling
 * them part-way. None of it is a failure, and none of it may lose or repeat text.
 */
class PushCancellationTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val server = MockWebServer()
    private val dao = FakeNotesDao()
    private val pausableDao = PausableDao(dao)
    private val deviceReplica = ByteArray(16) { 0x13 }

    /** Everything the repository runs, on one thread (the fake DAO isn't thread-safe). */
    private val executor = Executors.newSingleThreadExecutor()
    private val thread = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private lateinit var writer: NoteWriter
    private lateinit var repository: NotesRepository

    /** The note as iCloud has it. */
    @Volatile private var serverBody: String? = null
    @Volatile private var serverTag = 1
    private val writtenRecords = mutableListOf<String>()

    /** Requests to this endpoint are taken (a write is applied) but not answered until [answer] (a slow connection). */
    @Volatile private var held: String? = null
    private val answers = CountDownLatch(1)
    private val arrivals = Semaphore(0)

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
                val response = when {
                    path.endsWith("records/lookup") -> respond("""{"records":[${record(serverBody!!, "tag-$serverTag")}]}""")
                    path.endsWith("records/modify") -> modify(json.parseToJsonElement(request.body!!.utf8()).jsonObject)
                    path.endsWith("changes/zone") -> respond("""{"zones":[{"zoneID":{"zoneName":"Notes"},"syncToken":"T1","moreComing":false,"records":[]}]}""")
                    path.endsWith("changes/database") -> respond("""{"zones":[],"moreComing":false}""")
                    else -> MockResponse.Builder().code(404).build()
                }
                held?.takeIf { path.endsWith(it) }?.let {
                    arrivals.release()
                    answers.await(10, TimeUnit.SECONDS)
                }
                return response
            }
        }
        server.start()
        val account = IcloudAccount("1", "a", null, server.url("/").toString().trimEnd('/'))
        val session = SessionManager(FakePrefs(), SetupClient(OkHttpClient(), json, Params)) {}
        session.enterDemo(account)
        val cloudKit = CloudKitClient(OkHttpClient(), json, Params) { session.account }
        val sync = NotesSync(cloudKit, pausableDao, FakePrefs()) { writer.rebaseOnServerChanges(it) }
        writer = NoteWriter(cloudKit, pausableDao, sync::applyRecords, json) { ByteArray(16) { 0x42 } }
        repository = NotesRepository(pausableDao, sync, writer, session, scope)
    }

    @After
    fun tearDown() {
        answers.countDown()
        scope.cancel()
        server.close()
        executor.shutdownNow()
    }

    private fun respond(body: String) = MockResponse.Builder().code(200).body(body).build()

    /** Applies a write the way CloudKit does: only on top of the current change tag. */
    private fun modify(body: JsonObject): MockResponse {
        val record = body["operations"]!!.jsonArray[0].jsonObject["record"]!!.jsonObject
        val name = record["recordName"]!!.jsonPrimitive.content
        synchronized(writtenRecords) { writtenRecords += name }
        if (name == NOTE) {
            if (record["recordChangeTag"]?.jsonPrimitive?.content != "tag-$serverTag") {
                return respond("""{"records":[{"recordName":"$name","serverErrorCode":"CONFLICT","reason":"oplock"}]}""")
            }
            serverBody = record["fields"]!!.jsonObject["TextDataEncrypted"]!!.jsonObject["value"]!!.jsonPrimitive.content
            serverTag++
        }
        return respond("""{"records":[${JsonObject(record + ("recordChangeTag" to JsonPrimitive("tag-$serverTag")))}]}""")
    }

    private fun record(textData: String, tag: String) = """
        {"recordName":"$NOTE","recordType":"Note","recordChangeTag":"$tag",
         "fields":{
           "TextDataEncrypted":{"type":"ENCRYPTED_BYTES","value":"$textData"},
           "TitleEncrypted":{"type":"ENCRYPTED_BYTES","value":"VA=="},
           "Folder":{"type":"REFERENCE","value":{"recordName":"FOLDER-1","action":"VALIDATE"}},
           "CreationDate":{"type":"TIMESTAMP","value":1000}}}
    """.trimIndent()

    private fun paragraphs(vararg lines: String): List<FormatParagraph> = ParagraphMerge.withOffsets(
        lines.mapIndexed { i, text ->
            FormatParagraph(
                kind = if (i == 0) ParagraphKind.Title else ParagraphKind.Body,
                text = text,
                spans = if (text.isEmpty()) emptyList() else listOf(InlineSpan(text.length, InlineStyle.Plain)),
            )
        },
    )

    /** iCloud and the cache both have [lines], written by another device; [sharedBy]: in that person's zone. */
    private fun noteOnICloud(vararg lines: String, sharedBy: String? = null) {
        val content = paragraphs(*lines)
        val doc = NoteEditing.buildInitialDocument(content.joinToString("\n") { it.text }, deviceReplica)
        FormatReconcile.reconcile(doc, content, deviceReplica)
        val body = Base64.getEncoder().encodeToString(NoteCompression.compress(doc.encode()))
        serverBody = body
        onThread {
            dao.upsertNotes(
                listOf(
                    NoteEntity(
                        recordName = NOTE, folderRecordName = "FOLDER-1", title = lines.first(), snippet = "", plainText = "",
                        textData = body, creationDate = 0, modificationDate = 0, isPinned = false, recordChangeTag = "tag-$serverTag",
                        firstAttachmentUti = null, isLocked = false, bodyUnavailable = false, zoneOwner = sharedBy,
                    ),
                ),
            )
        }
    }

    /** A local edit waiting to be pushed, as the editor would have saved it. */
    private fun queueEdit(base: List<FormatParagraph>, desired: List<FormatParagraph>): PendingEditEntity {
        val edit = PendingEditEntity(
            recordName = NOTE, isNew = false, folderRecordName = "FOLDER-1",
            baseJson = writer.encodeParagraphs(base), desiredJson = writer.encodeParagraphs(desired),
            title = "Groceries", snippet = "", plainText = desired.joinToString("\n") { it.text }, updatedAt = LocalClock.next(),
        )
        onThread { dao.upsertPending(edit) }
        return edit
    }

    private fun <T> onThread(block: suspend () -> T): T = runBlocking(thread) { block() }

    /** What the editor's autosave does. */
    private fun save(base: List<FormatParagraph>, desired: List<FormatParagraph>) {
        onThread { repository.saveDraft(NOTE, base, desired) }
    }

    /** Starts [block] and returns once it's waiting on iCloud's answer to a [endpoint] request. */
    private fun startAndHoldAt(endpoint: String, block: suspend () -> Unit): Job {
        held = endpoint
        val job = scope.launch { block() }
        assertTrue("iCloud got the $endpoint request", arrivals.tryAcquire(10, TimeUnit.SECONDS))
        return job
    }

    private fun answer() {
        held = null
        answers.countDown()
    }

    private fun join(job: Job) = onThread { job.join() }

    private fun serverLines(): List<String> {
        val content = NoteContent.decode(Base64.getDecoder().decode(serverBody))
        return (content.format() as FormatResult.Ok).paragraphs.map { it.text }
    }

    private fun pending(): PendingEditEntity? = onThread { dao.getPending(NOTE) }

    /** Waits (by default past the push debounce) for [check] to hold. */
    private fun eventually(timeoutMs: Long = 8_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun savingAgainWhileThePreviousSaveIsOnItsWayToICloudDoesntFailIt() {
        noteOnICloud("Groceries", "milk", sharedBy = "_alex")
        // An autosave, then its debounced push: iCloud takes the write but is slow to answer...
        startAndHoldAt("records/modify") {
            repository.saveDraft(NOTE, paragraphs("Groceries", "milk"), paragraphs("Groceries", "milk", "eggs"))
        }
        // ...and the next autosave lands meanwhile.
        save(paragraphs("Groceries", "milk", "eggs"), paragraphs("Groceries", "milk", "eggs and ham"))
        answer()

        eventually { pending().let { it == null || it.error != null } }
        assertNull(pending()?.error)
        assertEquals(listOf("Groceries", "milk", "eggs and ham"), serverLines())
    }

    @Test
    fun aSharedNotesPushCutShortMidWriteIsSettledWithoutDuplicatingText() {
        noteOnICloud("Groceries", "milk", sharedBy = "_alex")
        queueEdit(paragraphs("Groceries", "milk"), paragraphs("Groceries", "milk", "eggs"))
        // A live refresh is pushing when the note closes, which cancels it.
        val refresh = startAndHoldAt("records/modify") { repository.pushPending() }
        refresh.cancel()
        answer()
        join(refresh)

        // Typing carried on from what was on screen.
        save(paragraphs("Groceries", "milk", "eggs"), paragraphs("Groceries", "milk", "eggs and ham"))
        onThread { repository.pushPending() }

        assertNull(pending()?.error)
        assertEquals(listOf("Groceries", "milk", "eggs and ham"), serverLines())
    }

    @Test
    fun anOwnNotesPushCutShortMidWriteIsSettledWithoutSavingACopy() {
        noteOnICloud("Groceries", "milk")
        queueEdit(paragraphs("Groceries", "milk"), paragraphs("Groceries", "milk", "eggs"))
        val push = startAndHoldAt("records/modify") { repository.pushPending() }
        push.cancel()
        answer()
        join(push)

        save(paragraphs("Groceries", "milk", "eggs"), paragraphs("Groceries", "milk", "eggs and ham"))
        onThread { repository.pushPending() }

        assertNull(pending()?.error)
        assertEquals(listOf("Groceries", "milk", "eggs and ham"), serverLines())
        // Our own first write wasn't mistaken for a change made elsewhere.
        assertEquals(setOf(NOTE), synchronized(writtenRecords) { writtenRecords.toSet() })
        assertNull(repository.syncStatus.value.notice)
    }

    @Test
    fun aCancelledPushFinishesTheNoteItStartedAndLeavesTheRestQueued() {
        noteOnICloud("Groceries", "milk")
        queueEdit(paragraphs("Groceries", "milk"), paragraphs("Groceries", "milk", "eggs"))
        val move = PendingOpEntity(PendingOpEntity.MOVE, NOTE, folderRecordName = "FOLDER-2", createdAt = 1)
        onThread { dao.upsertOp(move) }
        val push = startAndHoldAt("records/lookup") { repository.pushPending() }
        push.cancel()
        answer()
        join(push)

        assertNull(pending())
        assertEquals(listOf("Groceries", "milk", "eggs"), serverLines())
        // Pushed after the edits, the move hadn't started: still queued, and not marked as failed.
        assertEquals(listOf(move), onThread { dao.pushableOps() })
    }

    @Test
    fun aSaveRacingTheEndOfAPushStillBuildsOnWhatWasPushed() {
        noteOnICloud("Groceries", "milk", sharedBy = "_alex")
        queueEdit(paragraphs("Groceries", "milk"), paragraphs("Groceries", "milk", "eggs"))
        // The next autosave has read the queued edit, and is held up before writing its own...
        val saving = CompletableDeferred<Unit>()
        pausableDao.pauseNextNoteRead = saving
        val save = scope.launch {
            repository.saveDraft(NOTE, paragraphs("Groceries", "milk", "eggs"), paragraphs("Groceries", "milk", "eggs and ham"))
        }
        // ...while the push of the queued edit lands and wraps up.
        val push = startAndHoldAt("records/modify") { repository.pushPending() }
        answer()
        eventually(timeoutMs = 500) { push.isCompleted }
        saving.complete(Unit)
        join(save)
        join(push)

        onThread { repository.pushPending() }
        assertNull(pending()?.error)
        assertEquals(listOf("Groceries", "milk", "eggs and ham"), serverLines())
    }

    @Test
    fun aSyncCancelledPartWayIsNotReportedAsASyncError() {
        val sync = startAndHoldAt("changes/zone") { repository.syncNow(quiet = true) }
        sync.cancel()
        answer()
        join(sync)

        assertNull(repository.syncStatus.value.error)
    }

    /** The fake DAO, where a read can be made to take a while (as a busy database's can). */
    private class PausableDao(private val dao: FakeNotesDao) : NotesDao by dao {
        /** When set, the next [getNote] waits for it. */
        @Volatile var pauseNextNoteRead: CompletableDeferred<Unit>? = null

        override suspend fun getNote(recordName: String): NoteEntity? {
            pauseNextNoteRead?.let { pause ->
                pauseNextNoteRead = null
                pause.await()
            }
            return dao.getNote(recordName)
        }
    }

    private companion object {
        const val NOTE = "NOTE"
    }
}
