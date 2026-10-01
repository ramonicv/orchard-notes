package dev.rortega.orchardnotes.ui.note

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.rortega.orchardnotes.auth.ClientParams
import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.cloudkit.SetupClient
import dev.rortega.orchardnotes.data.FakeNotesDao
import dev.rortega.orchardnotes.data.FakePrefs
import dev.rortega.orchardnotes.data.LocalClock
import dev.rortega.orchardnotes.data.NoteEntity
import dev.rortega.orchardnotes.data.NoteWriter
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.data.NotesSync
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.FormatReconcile
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteCompression
import dev.rortega.orchardnotes.notes.doc.NoteEditing
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** A shared note open in the editor while someone else edits it. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteLiveEditingTest {
    private val dao = FakeNotesDao()
    private val json = Json { ignoreUnknownKeys = true }
    private val otherDevice = ByteArray(16) { 0x13 }
    private lateinit var writer: NoteWriter
    private lateinit var repository: NotesRepository

    /** One thread for the UI, like the app's main thread: the view model is only touched from it. */
    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val main = mainExecutor.asCoroutineDispatcher()

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        val params = object : ClientParams {
            override val clientId = "C"
            override val clientBuildNumber = "B"
            override val clientMasteringNumber = "M"
        }
        // No account: nothing is pushed, so saved edits stay in the fake DAO.
        val session = SessionManager(FakePrefs(), SetupClient(OkHttpClient(), json, params)) {}
        val cloudKit = CloudKitClient(OkHttpClient(), json, params) { null as IcloudAccount? }
        val sync = NotesSync(cloudKit, dao, FakePrefs())
        writer = NoteWriter(cloudKit, dao, sync::applyRecords, json) { ByteArray(16) { 0x42 } }
        repository = NotesRepository(dao, sync, writer, session, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        // Let background state updates settle before Main goes away.
        Thread.sleep(100)
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    /** State is built off the main thread: waits (briefly) for [check] to hold. */
    private fun eventually(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun paragraphs(vararg lines: String) = ParagraphMerge.withOffsets(
        lines.mapIndexed { i, text ->
            FormatParagraph(
                kind = if (i == 0) ParagraphKind.Title else ParagraphKind.Body,
                text = text,
                spans = if (text.isEmpty()) emptyList() else listOf(InlineSpan(text.length, InlineStyle.Plain)),
            )
        },
    )

    /** The note as iCloud has it, written by another device. */
    private fun serverNote(vararg lines: String): NoteEntity {
        val content = paragraphs(*lines)
        val doc = NoteEditing.buildInitialDocument(content.joinToString("\n") { it.text }, otherDevice)
        FormatReconcile.reconcile(doc, content, otherDevice)
        return NoteEntity(
            recordName = "N", folderRecordName = "FOLDER", title = lines.first(), snippet = "", plainText = "",
            textData = Base64.getEncoder().encodeToString(NoteCompression.compress(doc.encode())),
            creationDate = 0, modificationDate = 0, isPinned = false, recordChangeTag = "t", firstAttachmentUti = null,
            isLocked = false, bodyUnavailable = false, zoneOwner = "_alex",
        )
    }

    /** Someone else's edit arriving, the way a sync pull applies it. */
    private fun arrive(vararg lines: String) = runBlocking {
        val note = serverNote(*lines).copy(syncedAt = LocalClock.next())
        dao.upsertNotes(listOf(note))
        writer.rebaseOnServerChanges(listOf(note))
    }

    private fun openForEditing(vararg lines: String, cursor: Int? = null): NoteViewModel {
        runBlocking { dao.upsertNotes(listOf(serverNote(*lines))) }
        val vm = onMain { NoteViewModel(repository, "N", null) }
        runBlocking { vm.state.first { it is NoteUiState.Ready && it.editable } }
        onMain { vm.startEditing(cursor) }
        return vm
    }

    private fun NoteViewModel.awaitText(expected: String) {
        eventually { text() == expected }
        assertEquals(expected, text())
    }

    private fun NoteViewModel.type(text: String, cursor: Int = text.length) = onMain { onValueChange(TextFieldValue(text, TextRange(cursor))) }

    private fun NoteViewModel.save() = onMain { saveNow() }

    private fun NoteViewModel.text() = onMain { value.value.text }

    @Test
    fun aRemoteEditShowsUpWithTheCursorStayingPut() {
        val vm = openForEditing("Groceries", "milk", cursor = "Groceries\nmi".length)

        arrive("Groceries", "bread", "milk")

        vm.awaitText("Groceries\nbread\nmilk")
        assertEquals(TextRange("Groceries\nbread\nmi".length), vm.value.value.selection)
    }

    @Test
    fun unsavedTypingAndARemoteEditBothSurvive() {
        val vm = openForEditing("Groceries", "milk")
        vm.type("Groceries\nmilk\neggs")

        arrive("Groceries", "bread", "milk")

        vm.awaitText("Groceries\nbread\nmilk\neggs")
        assertEquals(listOf("Groceries", "bread", "milk", "eggs"), writer.decodeParagraphs(dao.pending["N"]!!.desiredJson).map { it.text })
    }

    @Test
    fun aRemoteEditToAnEditAlreadySavedHereIsMergedIntoIt() {
        val vm = openForEditing("Groceries", "milk")
        vm.type("Groceries\noat milk")
        vm.save()
        eventually { dao.pending["N"] != null }

        // The pending edit is rebased onto the new version, and the editor follows.
        arrive("Groceries", "milk", "bread")

        vm.awaitText("Groceries\noat milk\nbread")
    }

    @Test
    fun theSameLineTypedOnBothSidesAtOnceKeepsBoth() {
        val vm = openForEditing("Groceries", "")
        vm.type("Groceries\napples")

        arrive("Groceries", "pears")

        vm.awaitText("Groceries\npearsapples")
    }

    @Test
    fun ourOwnSaveComingBackIsNotAppliedTwice() {
        val vm = openForEditing("Groceries", "milk")
        vm.type("Groceries\nmilk\neggs")
        vm.save()
        eventually { dao.pending["N"] != null }
        vm.type("Groceries\nmilk\neggs\nham")

        // The push of the first save lands and comes back from iCloud.
        runBlocking {
            dao.upsertNotes(listOf(serverNote("Groceries", "milk", "eggs")))
            dao.deletePendingIfUnchanged("N", dao.pending["N"]!!.updatedAt)
        }

        Thread.sleep(200)
        assertEquals("Groceries\nmilk\neggs\nham", vm.text())
    }
}
