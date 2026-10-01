package dev.rortega.orchardnotes.ui.note

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.rortega.orchardnotes.auth.ClientParams
import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.cloudkit.SetupClient
import dev.rortega.orchardnotes.data.FakeNotesDao
import dev.rortega.orchardnotes.data.NoteEntity
import dev.rortega.orchardnotes.data.NoteWriter
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.data.NotesSync
import dev.rortega.orchardnotes.notes.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoteViewModelTest {
    private val dao = FakeNotesDao()
    private lateinit var repository: NotesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = Json { ignoreUnknownKeys = true }
        val params = object : ClientParams {
            override val clientId = "C"
            override val clientBuildNumber = "B"
            override val clientMasteringNumber = "M"
        }
        val prefs = context.getSharedPreferences("test", Context.MODE_PRIVATE)
        val session = SessionManager(prefs, SetupClient(OkHttpClient(), json, params)) {}
        // No account: pushes are skipped, so drafts stay visible in the fake DAO.
        val cloudKit = CloudKitClient(OkHttpClient(), json, params) { null as IcloudAccount? }
        val sync = NotesSync(cloudKit, dao, prefs)
        val writer = NoteWriter(cloudKit, dao, sync::applyRecords, json) { ByteArray(16) }
        repository = NotesRepository(dao, sync, writer, session, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking {
            dao.upsertNotes(
                listOf(
                    NoteEntity(
                        recordName = "N", folderRecordName = "DefaultFolder-CloudKit", title = "Test Note", snippet = "",
                        plainText = "", textData = Base64.getEncoder().encodeToString(Fixtures.compressed(Fixtures.PLAIN)),
                        creationDate = 0, modificationDate = 0, isPinned = false, recordChangeTag = "t",
                        firstAttachmentUti = null, isLocked = false, bodyUnavailable = false,
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun waitForReady(viewModel: NoteViewModel) = runBlocking {
        viewModel.state.first { it is NoteUiState.Ready } as NoteUiState.Ready
    }

    @Test
    fun openingAndLeavingANoteNeverWritesADraft() {
        val viewModel = NoteViewModel(repository, "N", null)
        waitForReady(viewModel)
        viewModel.saveNow()
        Thread.sleep(100)
        assertNull(dao.pending["N"])
    }

    @Test
    fun startingAndStoppingEditingWithoutChangesWritesNothing() {
        val viewModel = NoteViewModel(repository, "N", null)
        waitForReady(viewModel)
        viewModel.startEditing()
        viewModel.stopEditing()
        Thread.sleep(100)
        assertNull(dao.pending["N"])
    }

    @Test
    fun anEditIsSavedAsADraftWithTheServerVersionAsItsBase() {
        val viewModel = NoteViewModel(repository, "N", null)
        val ready = waitForReady(viewModel)
        viewModel.startEditing()
        val text = viewModel.value.value.text + "\nAdded on Android"
        viewModel.onValueChange(TextFieldValue(text, TextRange(text.length)))
        viewModel.stopEditing()
        Thread.sleep(200)
        val pending = dao.pending["N"]!!
        assertTrue(pending.plainText.endsWith("Added on Android"))
        assertEquals(ready.paragraphs.map { it.text }, repository.decodeParagraphs(pending.baseJson!!).map { it.text })
    }
}
