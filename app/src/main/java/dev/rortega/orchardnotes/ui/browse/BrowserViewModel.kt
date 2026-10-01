package dev.rortega.orchardnotes.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rortega.orchardnotes.data.NoteSummary
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.data.SpecialFolders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.util.UUID

data class NoteRow(val summary: NoteSummary, val dateLabel: String, val folderTitle: String?)

data class NoteSection(val title: String, val rows: List<NoteRow>)

data class NotesListState(val loaded: Boolean = false, val sections: List<NoteSection> = emptyList()) {
    val isEmpty: Boolean get() = sections.all { it.rows.isEmpty() }
}

/**
 * State for browsing: which folder is selected, which note is open, the search
 * query, and the lists derived from the local cache. Navigation state survives
 * process death through [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowserViewModel(
    private val repository: NotesRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val selectionKey: StateFlow<String> = savedState.getStateFlow(KEY_FOLDER, ALL_NOTES)
    val selection: StateFlow<FolderSelection> = selectionKey
        .map(::decodeSelection)
        .stateIn(viewModelScope, SharingStarted.Eagerly, decodeSelection(selectionKey.value))

    val openNoteId: StateFlow<String?> = savedState.getStateFlow(KEY_NOTE, null)

    /** Set while the open note is a new one being created in this folder. */
    val newNoteFolder: StateFlow<String?> = savedState.getStateFlow(KEY_NEW_NOTE_FOLDER, null)

    /** Phone layout only: whether the folders list is showing instead of a notes list. */
    val showingFolders: StateFlow<Boolean> = savedState.getStateFlow(KEY_SHOWING_FOLDERS, false)

    val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")

    val folders: StateFlow<List<FolderItem>> = combine(repository.folders(), repository.folderCounts()) { folders, counts ->
        buildFolderItems(folders, counts.associate { it.folderRecordName to it.count })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<NotesListState> = combine(selection, query.debounce { if (it.isEmpty()) 0 else 150 }) { selection, query ->
        selection to query.trim()
    }.flatMapLatest { (selection, query) ->
        val source = when {
            query.isNotEmpty() -> repository.search(query)
            selection is FolderSelection.Folder -> repository.notesInFolder(selection.recordName)
            else -> repository.allNotes()
        }
        val showFolder = query.isNotEmpty() || selection == FolderSelection.AllNotes
        combine(source, folders) { notes, folders ->
            val folderTitles = folders.mapNotNull { item ->
                (item.selection as? FolderSelection.Folder)?.let { it.recordName to item.title }
            }.toMap()
            buildSections(notes, folderTitles.takeIf { showFolder })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesListState())

    fun selectFolder(selection: FolderSelection) {
        savedState[KEY_FOLDER] = encodeSelection(selection)
        savedState[KEY_SHOWING_FOLDERS] = false
        savedState[KEY_QUERY] = ""
    }

    fun showFolders() {
        savedState[KEY_SHOWING_FOLDERS] = true
    }

    fun openNote(recordName: String) {
        savedState[KEY_NEW_NOTE_FOLDER] = null
        savedState[KEY_NOTE] = recordName
    }

    fun closeNote() {
        savedState[KEY_NOTE] = null
        savedState[KEY_NEW_NOTE_FOLDER] = null
    }

    /** Opens a new, empty note in the current folder (the default folder from "All iCloud"). */
    fun createNote() {
        val folder = (selection.value as? FolderSelection.Folder)?.recordName?.takeIf { it != SpecialFolders.TRASH }
            ?: SpecialFolders.DEFAULT
        savedState[KEY_NEW_NOTE_FOLDER] = folder
        savedState[KEY_NOTE] = UUID.randomUUID().toString()
    }

    val canCreateNotes: Boolean get() = selection.value != FolderSelection.Folder(SpecialFolders.TRASH)

    fun setQuery(query: String) {
        savedState[KEY_QUERY] = query
    }

    fun folderTitle(selection: FolderSelection): String = when (selection) {
        FolderSelection.AllNotes -> "All iCloud"
        is FolderSelection.Folder -> folders.value.firstOrNull { it.selection == selection }?.title
            ?: if (selection.recordName == SpecialFolders.TRASH) "Recently Deleted" else "Notes"
    }

    private fun buildSections(notes: List<NoteSummary>, folderTitles: Map<String, String>?): NotesListState {
        val today = LocalDate.now()
        val pinned = notes.filter { it.isPinned }
        val sections = mutableListOf<NoteSection>()
        fun row(note: NoteSummary) = NoteRow(
            summary = note,
            dateLabel = NoteDates.shortLabel(note.modificationDate, today),
            folderTitle = folderTitles?.let { titles -> note.folderRecordName?.let(titles::get) },
        )
        if (pinned.isNotEmpty()) sections += NoteSection("Pinned", pinned.map(::row))
        notes.filterNot { it.isPinned }
            .groupBy { NoteDates.sectionFor(it.modificationDate, today) }
            .forEach { (title, group) -> sections += NoteSection(title, group.map(::row)) }
        return NotesListState(loaded = true, sections = sections)
    }

    private companion object {
        const val KEY_FOLDER = "folder"
        const val KEY_NOTE = "note"
        const val KEY_SHOWING_FOLDERS = "showing_folders"
        const val KEY_QUERY = "query"
        const val KEY_NEW_NOTE_FOLDER = "new_note_folder"
        const val ALL_NOTES = "all"
        const val FOLDER_PREFIX = "folder:"

        fun encodeSelection(selection: FolderSelection): String = when (selection) {
            FolderSelection.AllNotes -> ALL_NOTES
            is FolderSelection.Folder -> FOLDER_PREFIX + selection.recordName
        }

        fun decodeSelection(key: String): FolderSelection =
            if (key.startsWith(FOLDER_PREFIX)) FolderSelection.Folder(key.removePrefix(FOLDER_PREFIX)) else FolderSelection.AllNotes
    }
}
