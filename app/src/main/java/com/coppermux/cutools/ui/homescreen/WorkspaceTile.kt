package com.coppermux.cutools.ui.homescreen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.ui.components.StatusDot

/**
 * One session tile in the workspaces grid. Long-press surfaces the rename/
 * delete context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceTile(
    session: WorkspaceSession,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val showMenu = remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu.value = true },
            ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            if (session.isIncognito) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = "Incognito",
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "INC",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = session.domain,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(status = session.status, size = 7.dp)
                    Text(
                        text = relativeTime(session.lastActiveAt),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = if (session.isDesktopMode) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                        contentDescription = if (session.isDesktopMode) "Desktop mode" else "Mobile mode",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showMenu.value) {
        WorkspaceContextMenu(
            session = session,
            onDismiss = { showMenu.value = false },
            onRename = onRename,
            onDelete = onDelete,
        )
    }
}

@Composable
fun WorkspaceTilePreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceTile(
                session = WorkspaceSession(
                    id = "1",
                    targetUrl = "https://github.com/coppermux",
                    domain = "github.com",
                    displayName = "GitHub — Code Review",
                    isIncognito = true,
                    isDesktopMode = true,
                    lastActiveAt = System.currentTimeMillis() - 90_000,
                ),
                onClick = {},
                onRename = {},
                onDelete = {},
            )
            WorkspaceTile(
                session = WorkspaceSession(
                    id = "2",
                    targetUrl = "https://developer.android.com",
                    domain = "developer.android.com",
                    displayName = "Android Docs",
                    isDesktopMode = false,
                    lastActiveAt = System.currentTimeMillis() - 3_600_000,
                ),
                onClick = {},
                onRename = {},
                onDelete = {},
            )
        }
    }
}