package dev.rortega.orchardnotes.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rortega.orchardnotes.data.NoteSummary
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.data.SharingIndex
import dev.rortega.orchardnotes.data.SharingInfo
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

data class NoteRow(
    val summary: NoteSummary,
    val dateLabel: String,
    val folderTitle: String?,
    /** How the note is shared; null when it isn't. */
    val sharing: SharingInfo? = null,
)

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

    private val sharingIndex: StateFlow<SharingIndex?> = repository.sharingIndex()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Notes shared with this account or by it, wherever they live. */
    private val sharedNotes = combine(repository.allNotes(), repository.sharingIndex()) { notes, sharing ->
        notes.filter { sharing.ofNote(it) != null }
    }

    val folders: StateFlow<List<FolderItem>> = combine(
        repository.folders(),
        repository.folderCounts(),
        repository.sharingIndex(),
        sharedNotes,
    ) { folders, counts, sharing, shared ->
        buildFolderItems(folders, counts.associate { it.folderRecordName to it.count }, sharing, shared.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<NotesListState> = combine(selection, query.debounce { if (it.isEmpty()) 0 else 150 }) { selection, query ->
        selection to query.trim()
    }.flatMapLatest { (selection, query) ->
        val source = when {
            query.isNotEmpty() -> repository.search(query)
            selection is FolderSelection.Folder -> repository.notesInFolder(selection.recordName)
            selection == FolderSelection.Shared -> sharedNotes
            else -> repository.allNotes()
        }
        // Lists that span folders say which folder each note is in.
        val showFolder = query.isNotEmpty() || selection !is FolderSelection.Folder
        combine(source, folders, repository.sharingIndex()) { notes, folders, sharing ->
            val folderTitles = folders.mapNotNull { item ->
                (item.selection as? FolderSelection.Folder)?.let { it.recordName to item.title }
            }.toMap()
            buildSections(notes, folderTitles.takeIf { showFolder }, sharing)
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

    /** Opens a new, empty note in the current folder (the default folder from "All iCloud" and "Shared"). */
    fun createNote() {
        val folder = (selection.value as? FolderSelection.Folder)?.recordName?.takeIf { it != SpecialFolders.TRASH }
            ?: SpecialFolders.DEFAULT
        savedState[KEY_NEW_NOTE_FOLDER] = folder
        savedState[KEY_NOTE] = UUID.randomUUID().toString()
    }

    /** Not in Recently Deleted, nor in a folder shared with this account to view only. */
    val canCreateNotes: Boolean get() {
        val folder = (selection.value as? FolderSelection.Folder)?.recordName ?: return true
        return folder != SpecialFolders.TRASH && sharingIndex.value?.ofFolder(folder)?.canEdit != false
    }

    fun setQuery(query: String) {
        savedState[KEY_QUERY] = query
    }

    fun folderTitle(selection: FolderSelection): String = when (selection) {
        FolderSelection.AllNotes -> "All iCloud"
        FolderSelection.Shared -> "Shared"
        is FolderSelection.Folder -> folders.value.firstOrNull { it.selection == selection }?.title
            ?: if (selection.recordName == SpecialFolders.TRASH) "Recently Deleted" else "Notes"
    }

    private fun buildSections(notes: List<NoteSummary>, folderTitles: Map<String, String>?, sharing: SharingIndex): NotesListState {
        val today = LocalDate.now()
        val pinned = notes.filter { it.isPinned }
        val sections = mutableListOf<NoteSection>()
        fun row(note: NoteSummary) = NoteRow(
            summary = note,
            dateLabel = NoteDates.shortLabel(note.modificationDate, today),
            folderTitle = folderTitles?.let { titles -> note.folderRecordName?.let(titles::get) },
            sharing = sharing.ofNote(note),
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
        const val SHARED = "shared"
        const val FOLDER_PREFIX = "folder:"

        fun encodeSelection(selection: FolderSelection): String = when (selection) {
            FolderSelection.AllNotes -> ALL_NOTES
            FolderSelection.Shared -> SHARED
            is FolderSelection.Folder -> FOLDER_PREFIX + selection.recordName
        }

        fun decodeSelection(key: String): FolderSelection = when {
            key.startsWith(FOLDER_PREFIX) -> FolderSelection.Folder(key.removePrefix(FOLDER_PREFIX))
            key == SHARED -> FolderSelection.Shared
            else -> FolderSelection.AllNotes
        }
    }
}
