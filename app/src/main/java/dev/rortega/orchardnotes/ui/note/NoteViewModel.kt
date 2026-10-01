package dev.rortega.orchardnotes.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rortega.orchardnotes.data.NoteEntity
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.FormatResult
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Base64

sealed interface NoteUiState {
    data object Loading : NoteUiState
    data object NotFound : NoteUiState
    data class Locked(val note: NoteEntity) : NoteUiState

    /** The note exists but its body can't be shown (too large, encrypted, or an unknown format). */
    data class Unavailable(val note: NoteEntity, val reason: String) : NoteUiState

    data class Ready(
        val note: NoteEntity,
        val content: NoteContent,
        val paragraphs: List<FormatParagraph>,
    ) : NoteUiState
}

class NoteViewModel(repository: NotesRepository, recordName: String) : ViewModel() {

    val state: StateFlow<NoteUiState> = repository.note(recordName)
        .distinctUntilChangedBy { it?.recordChangeTag to it?.textData }
        .map { note -> decode(note) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteUiState.Loading)

    private fun decode(note: NoteEntity?): NoteUiState {
        if (note == null) return NoteUiState.NotFound
        if (note.isLocked) return NoteUiState.Locked(note)
        val textData = note.textData
            ?: return NoteUiState.Unavailable(note, "This note's content is stored in a format Orchard can't read yet.")
        val content = runCatching { NoteContent.decode(Base64.getDecoder().decode(textData)) }.getOrElse {
            return NoteUiState.Unavailable(
                note,
                "This note's content couldn't be read. It may be end-to-end encrypted (Advanced Data Protection) or use a newer format.",
            )
        }
        return when (val format = content.format()) {
            is FormatResult.Ok -> NoteUiState.Ready(note, content, format.paragraphs)
            // Show unformatted text rather than nothing when the formatting isn't understood.
            is FormatResult.Unsupported -> NoteUiState.Ready(note, content, NoteFormat.plainParagraphs(content.text))
        }
    }
}
