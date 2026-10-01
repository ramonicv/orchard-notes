package dev.rortega.orchardnotes.ui.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rortega.orchardnotes.ui.appContainer
import kotlinx.coroutines.launch

/** Interim screen: every synced note, newest first. Replaced by folder browsing next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllNotesScreen() {
    val container = appContainer()
    val repository = container.notesRepository
    val notes by remember { repository.allNotes() }.collectAsState(initial = emptyList())
    val status by repository.syncStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                actions = {
                    TextButton(onClick = { scope.launch { container.sessionManager.signOut() } }) { Text("Sign out") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = status.syncing,
            onRefresh = { repository.requestSync() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                status.error?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                }
                items(notes, key = { it.recordName }) { note ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(note.title.ifBlank { "New Note" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(note.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
