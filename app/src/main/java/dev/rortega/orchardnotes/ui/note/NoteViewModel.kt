package dev.rortega.orchardnotes.ui.note

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rortega.orchardnotes.data.NoteEntity
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.data.PendingEditEntity
import dev.rortega.orchardnotes.data.SpecialFolders
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.FormatReconcile
import dev.rortega.orchardnotes.notes.doc.FormatResult
import dev.rortega.orchardnotes.notes.doc.NoteCompression
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.doc.NoteDocument
import dev.rortega.orchardnotes.notes.doc.NoteEditing
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import dev.rortega.orchardnotes.ui.editor.EditorState
import dev.rortega.orchardnotes.ui.editor.InlineAttribute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Base64

sealed interface NoteUiState {
    data object Loading : NoteUiState
    data object NotFound : NoteUiState
    data class Locked(val note: NoteEntity) : NoteUiState

    /** The note exists but its body can't be shown (too large, encrypted, or an unknown format). */
    data class Unavailable(val note: NoteEntity, val reason: String) : NoteUiState

    data class Ready(
        /** Null for a note created here that hasn't been saved yet. */
        val note: NoteEntity?,
        /** What to render: the server's note, with any local edit applied. */
        val content: NoteContent,
        val paragraphs: List<FormatParagraph>,
        /** The server version's paragraphs (null for a new note). */
        val serverParagraphs: List<FormatParagraph>?,
        /** Null when the note can be edited here; otherwise why not. */
        val readOnlyReason: String?,
        val pending: PendingEditEntity?,
    ) : NoteUiState {
        val editable: Boolean get() = readOnlyReason == null && pending?.blocked != true
    }
}

class NoteViewModel(
    private val repository: NotesRepository,
    private val recordName: String,
    /** Set when this is a brand-new note being created in that folder. */
    private val newNoteFolder: String?,
) : ViewModel() {

    val state: StateFlow<NoteUiState> = combine(repository.note(recordName), repository.pendingEdit(recordName)) { note, pending ->
        buildState(note, pending)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, NoteUiState.Loading)

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _editor = MutableStateFlow(EditorState.newNote())
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    private val _value = MutableStateFlow(TextFieldValue(""))
    val value: StateFlow<TextFieldValue> = _value.asStateFlow()

    /** The content this editing session started from; local edits are merged against it. */
    private var sessionBase: List<FormatParagraph>? = null
    private var lastSaved: List<FormatParagraph>? = null
    private var saveJob: Job? = null

    init {
        if (newNoteFolder != null) {
            _editing.value = true
            lastSaved = EditorState.newNote().toParagraphs()
        }
        // Once our edit reaches iCloud, later edits in this session build on what was pushed.
        viewModelScope.launch {
            var hadPending = false
            repository.pendingEdit(recordName).collect { pending ->
                if (hadPending && pending == null) lastSaved?.let { sessionBase = it }
                hadPending = pending != null
            }
        }
    }

    fun startEditing(cursor: Int? = null) {
        val ready = state.value as? NoteUiState.Ready ?: return
        if (!ready.editable || _editing.value) return
        sessionBase = ready.serverParagraphs
        lastSaved = ready.paragraphs
        val editorState = EditorState.fromParagraphs(ready.paragraphs)
        _editor.value = editorState
        val at = (cursor ?: editorState.text.length).coerceIn(0, editorState.text.length)
        _value.value = TextFieldValue(editorState.text, TextRange(at))
        _editing.value = true
    }

    fun stopEditing() {
        if (!_editing.value) return
        saveNow()
        _editing.value = false
    }

    fun onValueChange(newValue: TextFieldValue) {
        val current = _editor.value
        val previous = _value.value
        if (newValue.text != current.text) {
            val (next, adjusted) = current.applyTextChange(newValue.text, newValue.selection.end)
            _editor.value = next
            _value.value = if (adjusted == null) {
                newValue
            } else {
                val cursor = if (adjusted == current.text) previous.selection else TextRange(newValue.selection.end.coerceAtMost(adjusted.length))
                TextFieldValue(adjusted, cursor)
            }
            scheduleSave()
        } else {
            if (newValue.selection != previous.selection) _editor.value = current.copy(typingStyle = null)
            _value.value = newValue
        }
    }

    fun setLineKind(kind: ParagraphKind) = updateEditor { it.setLineKind(selectionRange(), kind) }

    fun toggleInline(attribute: InlineAttribute) = updateEditor { it.toggleInline(selectionRange(), attribute) }

    fun indent(delta: Int) = updateEditor { it.indent(selectionRange(), delta) }

    fun toggleDoneInEditor(lineIndex: Int) = updateEditor { it.toggleDone(lineIndex) }

    /** Checks or unchecks a checklist item from the read view; saved immediately. */
    fun toggleChecklist(paragraphIndex: Int) {
        val ready = state.value as? NoteUiState.Ready ?: return
        if (!ready.editable) return
        val target = ready.paragraphs.getOrNull(paragraphIndex)?.takeIf { it.kind == ParagraphKind.Checklist } ?: return
        val updated = ready.paragraphs.toMutableList().also { it[paragraphIndex] = target.copy(done = !target.done) }
        viewModelScope.launch { repository.saveDraft(recordName, ready.serverParagraphs, updated) }
    }

    fun discardLocalChanges() {
        viewModelScope.launch { repository.discardPending(recordName) }
    }

    fun saveLocalChangesAsNewNote(onCreated: (String) -> Unit) {
        viewModelScope.launch { repository.savePendingAsNewNote(recordName)?.let(onCreated) }
    }

    /** Persists the editor's content right away (leaving the note, app going to background). */
    fun saveNow() {
        saveJob?.cancel()
        val desired = _editor.value.toParagraphs()
        if (!shouldSave(desired)) return
        lastSaved = desired
        repository.saveDraftInBackground(recordName, sessionBase, desired, newNoteFolder)
    }

    override fun onCleared() {
        if (_editing.value) saveNow()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveNow()
        }
    }

    private fun shouldSave(desired: List<FormatParagraph>): Boolean {
        val previous = lastSaved
        if (previous != null && NoteFormat.formatsEqual(ParagraphMerge.withOffsets(previous), desired)) return false
        // A new note isn't created until it has some text.
        return !(newNoteFolder != null && state.value.let { it !is NoteUiState.Ready || it.note == null } && desired.all { it.text.isBlank() })
    }

    private fun selectionRange(): IntRange = _value.value.selection.let { it.min until it.max }

    private fun updateEditor(transform: (EditorState) -> EditorState) {
        _editor.value = transform(_editor.value)
        scheduleSave()
    }

    private fun buildState(note: NoteEntity?, pending: PendingEditEntity?): NoteUiState {
        if (note == null) {
            if (newNoteFolder == null) return NoteUiState.NotFound
            val empty = EditorState.newNote().toParagraphs()
            return NoteUiState.Ready(null, NoteContent("", emptyList()), empty, null, null, null)
        }
        if (note.isLocked) return NoteUiState.Locked(note)

        val serverRaw = note.textData?.let { data -> runCatching { NoteCompression.decompress(Base64.getDecoder().decode(data)) }.getOrNull() }
        val serverContent = serverRaw?.let { raw -> runCatching { NoteContent.decodeRaw(raw) }.getOrNull() }
        val serverFormat = serverContent?.format()
        val serverParagraphs = when {
            serverFormat is FormatResult.Ok -> serverFormat.paragraphs
            serverContent != null -> NoteFormat.plainParagraphs(serverContent.text)
            else -> null
        }
        val isLocalOnly = pending?.isNew == true && serverRaw == null
        if (serverContent == null && pending == null) {
            return NoteUiState.Unavailable(
                note,
                if (note.textData == null) {
                    "This note's content is stored in a format Orchard can't read yet."
                } else {
                    "This note's content couldn't be read. It may be end-to-end encrypted (Advanced Data Protection) or use a newer format."
                },
            )
        }
        val readOnlyReason = when {
            note.folderRecordName == SpecialFolders.TRASH -> "Recover this note to edit it."
            isLocalOnly -> null
            serverRaw == null || serverContent == null -> "This note's content couldn't be read."
            serverFormat !is FormatResult.Ok -> "This note uses formatting Orchard can't edit yet."
            !NoteDocument.roundTrips(serverRaw) -> "This note uses content Orchard can't edit safely yet."
            else -> null
        }

        if (pending == null) {
            return NoteUiState.Ready(note, serverContent!!, serverParagraphs!!, serverParagraphs, readOnlyReason, null)
        }
        val desired = repository.decodeParagraphs(pending.desiredJson)
        return NoteUiState.Ready(note, preview(serverRaw, desired), desired, serverParagraphs, readOnlyReason, pending)
    }

    /** The local edit applied to the server's note, so it renders with everything it will keep. */
    private fun preview(serverRaw: ByteArray?, desired: List<FormatParagraph>): NoteContent {
        val text = desired.joinToString("\n") { it.text }
        return runCatching {
            val doc = if (serverRaw != null) {
                NoteDocument.parse(serverRaw).also { NoteEditing.applyTextEdit(it, text, PREVIEW_REPLICA) }
            } else {
                NoteEditing.buildInitialDocument(text, PREVIEW_REPLICA)
            }
            FormatReconcile.reconcile(doc, desired, PREVIEW_REPLICA)
            NoteContent(doc.text, doc.attributeRuns)
        }.getOrElse { NoteContent(text, emptyList()) }
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 700L

        /** Only used to render previews locally; never written to iCloud. */
        val PREVIEW_REPLICA = ByteArray(16) { 0x7f }
    }
}
