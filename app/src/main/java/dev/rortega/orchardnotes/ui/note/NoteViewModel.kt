package dev.rortega.orchardnotes.ui.note

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rortega.orchardnotes.data.NoteEntity
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.data.PendingEditEntity
import dev.rortega.orchardnotes.data.SavedDraft
import dev.rortega.orchardnotes.data.SharingInfo
import dev.rortega.orchardnotes.data.SpecialFolders
import dev.rortega.orchardnotes.notes.LiveMerge
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
        /** How the note is shared; null when it isn't. */
        val sharing: SharingInfo? = null,
        /** When this version was stored ([dev.rortega.orchardnotes.data.LocalClock]): later versions are larger. */
        val version: Long = 0,
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

    val state: StateFlow<NoteUiState> = combine(
        repository.note(recordName),
        repository.pendingEdit(recordName),
        repository.sharingIndex(),
    ) { note, pending, sharing ->
        buildState(note, pending, note?.let { sharing.ofNote(it) })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, NoteUiState.Loading)

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _editor = MutableStateFlow(EditorState.newNote())
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    private val _value = MutableStateFlow(TextFieldValue(""))
    val value: StateFlow<TextFieldValue> = _value.asStateFlow()

    /**
     * The saved version of the note (local edit or iCloud's) the editor is based on: the
     * editor shows it plus whatever was typed since. Saves record this as their starting
     * point, so changes that arrived in between are merged rather than overwritten.
     */
    private var reconciled: List<FormatParagraph>? = null
    private var lastSaved: List<FormatParagraph>? = null
    private var autosaveJob: Job? = null

    /** The latest save; the next one waits for it. */
    private var latestSave: Deferred<SavedDraft>? = null

    /**
     * The newest version seen or saved. A version read from the cache before a save landed
     * can arrive after it; anything older than this is such a leftover, and is ignored.
     */
    private var latestVersion = 0L

    /** Saves whose result hasn't been brought back into the editor yet. */
    private var savesInFlight = 0

    /** Only an editing session has content to save; the editor's initial state must never be written. */
    private var sessionActive = false

    init {
        if (newNoteFolder != null) {
            _editing.value = true
            sessionActive = true
            lastSaved = EditorState.newNote().toParagraphs()
            reconciled = lastSaved
        }
        // A newer version (someone's edit, or our own push coming back) reaches an open editor.
        viewModelScope.launch {
            state.collect { current -> (current as? NoteUiState.Ready)?.let(::onVersion) }
        }
        // While others may be editing this note too, keep its zone closely in sync.
        viewModelScope.launch {
            repository.note(recordName)
                .map { note -> note?.let { repository.liveZoneOf(it) } }
                .distinctUntilChanged()
                .collectLatest { zone ->
                    if (zone == null) return@collectLatest
                    val stopWatching = repository.watch(zone)
                    try {
                        awaitCancellation()
                    } finally {
                        stopWatching()
                    }
                }
        }
    }

    fun startEditing(cursor: Int? = null) {
        val ready = state.value as? NoteUiState.Ready ?: return
        if (!ready.editable || _editing.value) return
        reconciled = ready.paragraphs
        lastSaved = ready.paragraphs
        val editorState = EditorState.fromParagraphs(ready.paragraphs)
        _editor.value = editorState
        val at = (cursor ?: editorState.text.length).coerceIn(0, editorState.text.length)
        _value.value = TextFieldValue(editorState.text, TextRange(at))
        sessionActive = true
        _editing.value = true
    }

    fun stopEditing() {
        if (!_editing.value) return
        saveNow()
        sessionActive = false
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
        viewModelScope.launch { repository.saveDraft(recordName, ready.paragraphs, updated) }
    }

    fun discardLocalChanges() {
        viewModelScope.launch { repository.discardPending(recordName) }
    }

    fun saveLocalChangesAsNewNote(onCreated: (String) -> Unit) {
        viewModelScope.launch { repository.savePendingAsNewNote(recordName)?.let(onCreated) }
    }

    /** Persists the editor's content right away (leaving the note, app going to background). */
    fun saveNow() {
        autosaveJob?.cancel()
        if (!sessionActive) return
        val desired = _editor.value.toParagraphs()
        if (!shouldSave(desired)) return
        // Typed on top of the previous save while that one is unfinished, else on the reconciled version.
        val previous = latestSave.takeIf { savesInFlight > 0 }
        val base = if (previous != null) lastSaved else reconciled
        lastSaved = desired
        val save = repository.saveDraftAsync(recordName, base, desired, newNoteFolder, after = previous)
        latestSave = save
        savesInFlight++
        viewModelScope.launch {
            val saved = runCatching { save.await() }.getOrNull()
            savesInFlight--
            // An older save's result is already part of the newer one's.
            if (latestSave !== save) return@launch
            if (saved != null) onSaved(desired, saved) else lastSaved = reconciled
        }
    }

    override fun onCleared() {
        saveNow()
    }

    private fun scheduleSave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveNow()
        }
    }

    /** [desired] was saved as [saved]: the same, or with changes that arrived meanwhile merged in. */
    private fun onSaved(desired: List<FormatParagraph>, saved: SavedDraft) {
        latestVersion = maxOf(latestVersion, saved.savedAt)
        reconciled = saved.paragraphs
        lastSaved = saved.paragraphs
        if (!sameContent(desired, saved.paragraphs)) applyToEditor(base = desired, theirs = saved.paragraphs)
        // A version that arrived while saving was skipped; catch up with it now.
        (state.value as? NoteUiState.Ready)?.let(::onVersion)
    }

    private fun onVersion(ready: NoteUiState.Ready) {
        if (ready.version < latestVersion) return
        latestVersion = ready.version
        onSavedVersion(ready.paragraphs)
    }

    /** Brings a newer saved version of the note into the open editor. */
    private fun onSavedVersion(version: List<FormatParagraph>) {
        if (!_editing.value || !sessionActive) return
        val base = reconciled ?: return
        // A save is running: when it finishes, it accounts for this version.
        if (savesInFlight > 0 || sameContent(base, version)) return
        if (sameContent(_editor.value.toParagraphs(), base)) {
            // Nothing typed since: show the new version as is, the cursor staying put.
            applyToEditor(base, version)
            reconciled = version
            lastSaved = version
        } else {
            // Save what was typed; the save merges it into the new version and updates the editor.
            saveNow()
        }
    }

    /** Applies the changes from [base] to [theirs] to the editor, keeping what was typed and the cursor. */
    private fun applyToEditor(base: List<FormatParagraph>, theirs: List<FormatParagraph>) {
        val merged = LiveMerge.merge(base, _editor.value.toParagraphs(), theirs)
        val editorState = EditorState.fromParagraphs(merged.paragraphs)
        val selection = _value.value.selection
        _editor.value = editorState
        _value.value = TextFieldValue(editorState.text, TextRange(merged.mapOursOffset(selection.start), merged.mapOursOffset(selection.end)))
    }

    private fun sameContent(a: List<FormatParagraph>, b: List<FormatParagraph>): Boolean =
        NoteFormat.formatsEqual(ParagraphMerge.withOffsets(a), ParagraphMerge.withOffsets(b))

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

    private fun buildState(note: NoteEntity?, pending: PendingEditEntity?, sharing: SharingInfo?): NoteUiState {
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
            sharing?.canEdit == false -> "${sharing.ownerName ?: "The owner"} shared this note with you to view only."
            isLocalOnly -> null
            serverRaw == null || serverContent == null -> "This note's content couldn't be read."
            serverFormat !is FormatResult.Ok -> "This note uses formatting Orchard can't edit yet."
            !NoteDocument.roundTrips(serverRaw) -> "This note uses content Orchard can't edit safely yet."
            else -> null
        }

        if (pending == null) {
            return NoteUiState.Ready(note, serverContent!!, serverParagraphs!!, serverParagraphs, readOnlyReason, null, sharing, note.syncedAt)
        }
        val desired = repository.decodeParagraphs(pending.desiredJson)
        val version = maxOf(note.syncedAt, pending.updatedAt)
        return NoteUiState.Ready(note, preview(serverRaw, desired), desired, serverParagraphs, readOnlyReason, pending, sharing, version)
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
