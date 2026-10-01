package dev.rortega.orchardnotes.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rortega.orchardnotes.data.SyncStatus

enum class ListNavigation { None, Back, Drawer }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListPane(
    title: String,
    state: NotesListState,
    query: String,
    selectedNoteId: String?,
    syncStatus: SyncStatus,
    sessionExpired: Boolean,
    navigation: ListNavigation,
    onNavigate: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onMoveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onRefresh: () -> Unit,
    onSignInAgain: () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        floatingActionButton = floatingActionButton,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    when (navigation) {
                        ListNavigation.Back -> IconButton(onClick = onNavigate) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Folders")
                        }
                        ListNavigation.Drawer -> IconButton(onClick = onNavigate) {
                            Icon(Icons.Filled.Menu, contentDescription = "Folders")
                        }
                        ListNavigation.None -> Unit
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = syncStatus.syncing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                item(key = "search") { SearchField(query, onQueryChange) }
                if (sessionExpired) {
                    item(key = "expired") { SessionExpiredBanner(onSignInAgain) }
                } else if (syncStatus.offline) {
                    item(key = "offline") {
                        StatusLine(
                            if (syncStatus.pendingCount > 0) {
                                "Offline. ${syncStatus.pendingCount} ${if (syncStatus.pendingCount == 1) "change" else "changes"} will sync when you're back online."
                            } else {
                                "Offline. Showing notes saved on this device."
                            },
                        )
                    }
                } else if (syncStatus.error != null) {
                    item(key = "error") {
                        Text(
                            syncStatus.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp),
                        )
                    }
                }
                if (state.loaded && state.isEmpty) {
                    item(key = "empty") {
                        Text(
                            if (query.isNotBlank()) "No results" else if (syncStatus.syncing) "Loading notes…" else "No Notes",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                        )
                    }
                }
                state.sections.forEach { section ->
                    item(key = "header-${section.title}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
                        ) {
                            if (section.title == "Pinned") {
                                Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    itemsIndexed(section.rows, key = { _, row -> "${section.title}-${row.summary.recordName}" }) { index, row ->
                        NoteRowItem(
                            row = row,
                            selected = row.summary.recordName == selectedNoteId,
                            onClick = { onOpenNote(row.summary.recordName) },
                            onMove = { onMoveNote(row.summary.recordName) },
                            onDelete = { onDeleteNote(row.summary.recordName) },
                            modifier = Modifier.clip(groupShape(index, section.rows.size)),
                        )
                        if (index < section.rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp),
    )
}

@Composable
private fun SessionExpiredBanner(onSignInAgain: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("You're signed out of iCloud", style = MaterialTheme.typography.titleSmall)
            Text(
                "Your cached notes are still here. Sign in again to sync changes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSignInAgain) { Text("Sign in") }
        }
    }
}

@Composable
private fun NoteRowItem(
    row: NoteRow,
    selected: Boolean,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = row.summary
    var menuOpen by remember { mutableStateOf(false) }
    val inTrash = note.folderRecordName == dev.rortega.orchardnotes.data.SpecialFolders.TRASH
    // Only the person who shared a note can move or delete it.
    val canOrganize = row.sharing?.sharedWithMe != true
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest)
            .combinedClickable(onClick = onClick, onLongClick = if (canOrganize) ({ menuOpen = true }) else null)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (inTrash) "Recover to…" else "Move to…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMove()
                },
            )
            if (!inTrash) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (note.isLocked) {
                    Icon(Icons.Outlined.Lock, contentDescription = "Locked", modifier = Modifier.size(16.dp))
                }
                Text(
                    note.title.ifBlank { "New Note" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.sharing != null) {
                    Icon(
                        Icons.Outlined.People,
                        contentDescription = "Shared",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.dateLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (note.isLocked) "Locked" else note.snippet.ifBlank { "No additional text" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (note.firstAttachmentUti != null) {
                    Icon(
                        Icons.Outlined.Attachment,
                        contentDescription = "Has attachments",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            row.folderTitle?.let { folder ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // A note shared on its own has no folder here: say whose it is instead.
            if (row.folderTitle == null && row.sharing?.sharedWithMe == true) {
                row.sharing.ownerName?.let { owner ->
                    Text("From $owner", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
