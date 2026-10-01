package dev.rortega.orchardnotes.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import dev.rortega.orchardnotes.auth.SessionState
import dev.rortega.orchardnotes.ui.appContainer
import dev.rortega.orchardnotes.ui.note.NoNoteSelected
import dev.rortega.orchardnotes.ui.note.NotePane
import kotlinx.coroutines.launch

private enum class PhoneScreen { Folders, List, Note }

/**
 * Folders, notes list and note, laid out for the window size: one pane at a time on
 * phones, list + note on medium widths (folders in a drawer), and all three side by
 * side on large screens.
 */
@Composable
fun NotesBrowser(session: SessionState.SignedIn, onSignInAgain: () -> Unit) {
    val container = appContainer()
    val repository = container.notesRepository
    val viewModel: BrowserViewModel = viewModel { BrowserViewModel(repository, createSavedStateHandle()) }
    val scope = rememberCoroutineScope()

    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val openNoteId by viewModel.openNoteId.collectAsStateWithLifecycle()
    val showingFolders by viewModel.showingFolders.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val syncStatus by repository.syncStatus.collectAsStateWithLifecycle()

    // Refresh whenever the app comes to the foreground.
    LifecycleStartEffect(session.account.dsid, session.expired) {
        if (!session.expired) repository.requestSync()
        onStopOrDispose { }
    }

    val signOut: () -> Unit = { scope.launch { container.sessionManager.signOut() } }

    @Composable
    fun foldersPane(modifier: Modifier, showChevrons: Boolean, onSelected: () -> Unit = {}) = FoldersPane(
        folders = folders,
        selected = selection,
        account = session.account,
        syncStatus = syncStatus,
        showChevrons = showChevrons,
        onSelect = {
            viewModel.selectFolder(it)
            onSelected()
        },
        onSignOut = signOut,
        modifier = modifier,
    )

    @Composable
    fun listPane(modifier: Modifier, navigation: ListNavigation, onNavigate: () -> Unit) = NotesListPane(
        title = if (query.isNotBlank()) "Search" else viewModel.folderTitle(selection),
        state = notes,
        query = query,
        selectedNoteId = openNoteId,
        syncStatus = syncStatus,
        sessionExpired = session.expired,
        navigation = navigation,
        onNavigate = onNavigate,
        onQueryChange = viewModel::setQuery,
        onOpenNote = viewModel::openNote,
        onRefresh = { repository.requestSync() },
        onSignInAgain = onSignInAgain,
        modifier = modifier,
    )

    @Composable
    fun notePane(modifier: Modifier, showBack: Boolean) {
        val noteId = openNoteId
        if (noteId == null) {
            NoNoteSelected(modifier)
        } else {
            NotePane(recordName = noteId, showBack = showBack, onBack = viewModel::closeNote, modifier = modifier)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width: Dp = maxWidth
            when {
                width >= EXPANDED_WIDTH -> {
                    BackHandler(enabled = openNoteId != null) { viewModel.closeNote() }
                    Row(Modifier.fillMaxSize()) {
                        foldersPane(Modifier.width(280.dp).fillMaxHeight(), showChevrons = false)
                        VerticalDivider()
                        listPane(Modifier.width(360.dp).fillMaxHeight(), ListNavigation.None) {}
                        VerticalDivider()
                        notePane(Modifier.weight(1f).fillMaxHeight(), showBack = false)
                    }
                }
                width >= MEDIUM_WIDTH -> {
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    BackHandler(enabled = openNoteId != null && drawerState.isClosed) { viewModel.closeNote() }
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                foldersPane(Modifier.fillMaxHeight(), showChevrons = false) {
                                    scope.launch { drawerState.close() }
                                }
                            }
                        },
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            listPane(Modifier.width(340.dp).fillMaxHeight(), ListNavigation.Drawer) {
                                scope.launch { drawerState.open() }
                            }
                            VerticalDivider()
                            notePane(Modifier.weight(1f).fillMaxHeight(), showBack = false)
                        }
                    }
                }
                else -> {
                    val screen = when {
                        openNoteId != null -> PhoneScreen.Note
                        showingFolders -> PhoneScreen.Folders
                        else -> PhoneScreen.List
                    }
                    BackHandler(enabled = screen == PhoneScreen.Note) { viewModel.closeNote() }
                    BackHandler(enabled = screen == PhoneScreen.List) { viewModel.showFolders() }
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            if (targetState.ordinal > initialState.ordinal) {
                                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
                            } else {
                                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
                            }
                        },
                        label = "phone-navigation",
                    ) { target ->
                        when (target) {
                            PhoneScreen.Folders -> foldersPane(Modifier.fillMaxSize(), showChevrons = true)
                            PhoneScreen.List -> listPane(Modifier.fillMaxSize(), ListNavigation.Back) { viewModel.showFolders() }
                            PhoneScreen.Note -> notePane(Modifier.fillMaxSize(), showBack = true)
                        }
                    }
                }
            }
        }
    }
}

private val MEDIUM_WIDTH = 600.dp
private val EXPANDED_WIDTH = 900.dp
