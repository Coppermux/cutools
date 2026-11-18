package com.coppermux.cutools.ui.homescreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.ui.theme.CuToolsTheme

/**
 * Per-session action dialog surfaced from a long-press on a tile: rename
 * and delete. Rename opens the nested [RenameDialog].
 */
@Composable
fun WorkspaceContextMenu(
    session: WorkspaceSession,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val renameOpen = remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = session.domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionRowButton(
                    icon = Icons.Default.Edit,
                    label = "Rename Session",
                    onClick = { renameOpen.value = true },
                )
                ActionRowButton(
                    icon = Icons.Default.Delete,
                    label = "Delete Session",
                    isDestructive = true,
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.SemiBold)
            }
        },
    )

    if (renameOpen.value) {
        RenameDialog(
            currentName = session.displayName,
            onConfirm = { newName ->
                onRename(newName)
                renameOpen.value = false
                onDismiss()
            },
            onDismiss = { renameOpen.value = false },
        )
    }
}

@Composable
private fun ActionRowButton(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isDestructive) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun WorkspaceContextMenuPreview() {
    CuToolsTheme {
        WorkspaceContextMenu(
            session = WorkspaceSession(
                id = "1",
                targetUrl = "https://example.com",
                domain = "example.com",
                displayName = "Example Session",
            ),
            onDismiss = {},
            onRename = {},
            onDelete = {},
        )
    }
}