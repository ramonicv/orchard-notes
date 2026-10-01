package dev.rortega.orchardnotes.ui.note

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rortega.orchardnotes.ui.appContainer
import dev.rortega.orchardnotes.ui.browse.NoteDates
import dev.rortega.orchardnotes.ui.editor.EditorToolbar
import dev.rortega.orchardnotes.ui.editor.NoteEditor
import dev.rortega.orchardnotes.ui.editor.RequestFocusOnce

/** Shows one note, and edits it when it can be edited safely. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePane(
    recordName: String,
    newNoteFolder: String?,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = appContainer().notesRepository
    val viewModel: NoteViewModel = viewModel(key = "note-$recordName") { NoteViewModel(repository, recordName, newNoteFolder) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Leaving the note (or the app) always persists what was typed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) viewModel.saveNow() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.saveNow()
        }
    }
    val close = {
        viewModel.stopEditing()
        onBack()
    }
    BackHandler(enabled = editing) { close() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                navigationIcon = {
                    if (showBack) IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val ready = state as? NoteUiState.Ready
                    if (ready?.pending != null && ready.pending.error == null) {
                        Icon(
                            Icons.Outlined.CloudOff,
                            contentDescription = "Not yet synced to iCloud",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        )
                    }
                    if (editing) {
                        TextButton(onClick = viewModel::stopEditing) { Text("Done") }
                    } else if (ready != null) {
                        if (ready.editable) {
                            IconButton(onClick = { viewModel.startEditing() }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, ready.note?.title)
                                putExtra(Intent.EXTRA_TEXT, ready.content.text.replace("￼", ""))
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                    }
                },
            )
        },
        bottomBar = {
            if (editing) {
                val editor by viewModel.editor.collectAsStateWithLifecycle()
                val value by viewModel.value.collectAsStateWithLifecycle()
                EditorToolbar(
                    state = editor,
                    selection = value.selection,
                    onSetKind = viewModel::setLineKind,
                    onToggleInline = viewModel::toggleInline,
                    onIndent = viewModel::indent,
                    modifier = Modifier.navigationBarsPadding().imePadding(),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (val current = state) {
                NoteUiState.Loading -> CircularProgressIndicator(Modifier.padding(top = 48.dp))
                NoteUiState.NotFound -> Message(Icons.Outlined.WarningAmber, "This note was deleted.")
                is NoteUiState.Locked -> Message(
                    Icons.Outlined.Lock,
                    "“${current.note.title.ifBlank { "This note" }}” is locked. Open it on your iPhone, iPad or Mac to view it.",
                )
                is NoteUiState.Unavailable -> Message(Icons.Outlined.WarningAmber, current.reason)
                is NoteUiState.Ready -> Column(
                    Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                ) {
                    current.pending?.error?.let { error ->
                        SyncProblemBanner(
                            message = error,
                            onDiscard = viewModel::discardLocalChanges,
                            onSaveAsNew = { viewModel.saveLocalChangesAsNewNote(onOpenNote) },
                        )
                    }
                    Text(
                        NoteDates.longLabel(current.note?.modificationDate ?: System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                    if (editing) {
                        val editor by viewModel.editor.collectAsStateWithLifecycle()
                        val value by viewModel.value.collectAsStateWithLifecycle()
                        val focusRequester = remember { FocusRequester() }
                        NoteEditor(
                            state = editor,
                            value = value,
                            onValueChange = viewModel::onValueChange,
                            onToggleDone = viewModel::toggleDoneInEditor,
                            focusRequester = focusRequester,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                        )
                        RequestFocusOnce(focusRequester)
                    } else {
                        if (!current.editable && current.readOnlyReason != null) ReadOnlyNotice(current.readOnlyReason)
                        val body = @Composable {
                            NoteBody(
                                content = current.content,
                                paragraphs = current.paragraphs,
                                onToggleChecklist = if (current.editable) viewModel::toggleChecklist else null,
                                attachment = { AttachmentPlaceholder(it.typeUti) },
                                onTextTap = if (current.editable) { offset -> viewModel.startEditing(offset) } else null,
                            )
                        }
                        // Read-only notes support text selection; editable ones turn taps into editing.
                        if (current.editable) body() else SelectionContainer { body() }
                        if (current.editable) {
                            // Tapping the empty space below the text starts editing at the end.
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 160.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        viewModel.startEditing()
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncProblemBanner(message: String, onDiscard: () -> Unit, onSaveAsNew: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your changes couldn't be saved to iCloud", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSaveAsNew) { Text("Save as new note") }
                TextButton(onClick = onDiscard) { Text("Discard changes") }
            }
        }
    }
}

@Composable
private fun ReadOnlyNotice(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text("Read-only: $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.widthIn(max = 420.dp).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Placeholder for the detail pane when no note is selected. */
@Composable
fun NoNoteSelected(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No note selected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
