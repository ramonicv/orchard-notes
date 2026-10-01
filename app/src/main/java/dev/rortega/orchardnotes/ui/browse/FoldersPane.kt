package dev.rortega.orchardnotes.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.data.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersPane(
    folders: List<FolderItem>,
    selected: FolderSelection?,
    account: IcloudAccount,
    syncStatus: SyncStatus,
    showChevrons: Boolean,
    onSelect: (FolderSelection) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAccount by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                actions = {
                    IconButton(onClick = { showAccount = true }) {
                        Icon(Icons.Outlined.AccountCircle, contentDescription = "Account")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    "iCloud",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(folders, key = { _, item -> item.selection.toString() }) { index, item ->
                val shape = groupShape(index, folders.size)
                FolderRow(
                    item = item,
                    selected = item.selection == selected,
                    showChevron = showChevrons,
                    onClick = { onSelect(item.selection) },
                    modifier = Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLowest),
                )
                if (index < folders.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
    if (showAccount) {
        AccountDialog(account, syncStatus, onDismiss = { showAccount = false }, onSignOut = {
            showAccount = false
            onSignOut()
        })
    }
}

@Composable
private fun FolderRow(
    item: FolderItem,
    selected: Boolean,
    showChevron: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (item.kind) {
        FolderKind.AllNotes -> Icons.Outlined.Cloud
        FolderKind.Trash -> Icons.Outlined.Delete
        else -> Icons.Outlined.Folder
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(start = 14.dp + (item.depth * 20).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(item.count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        } else {
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** Rounded corners on the first and last rows of an inset-grouped list. */
@Composable
internal fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val radius = 14.dp
    return remember(index, count) {
        RoundedCornerShape(
            topStart = if (index == 0) radius else 0.dp,
            topEnd = if (index == 0) radius else 0.dp,
            bottomStart = if (index == count - 1) radius else 0.dp,
            bottomEnd = if (index == count - 1) radius else 0.dp,
        )
    }
}

@Composable
private fun AccountDialog(account: IcloudAccount, syncStatus: SyncStatus, onDismiss: () -> Unit, onSignOut: () -> Unit) {
    var confirmingSignOut by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
        title = { Text(account.fullName ?: "iCloud") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account.appleId, style = MaterialTheme.typography.bodyMedium)
                val synced = syncStatus.lastSyncedAt?.let { "Last synced ${NoteDates.longLabel(it)}" } ?: "Not synced yet"
                Text(synced, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (confirmingSignOut) {
                    Text(
                        "Signing out removes the notes cached on this device. Your notes stay in iCloud.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (confirmingSignOut) onSignOut() else confirmingSignOut = true }) {
                Text(if (confirmingSignOut) "Sign out now" else "Sign out", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
