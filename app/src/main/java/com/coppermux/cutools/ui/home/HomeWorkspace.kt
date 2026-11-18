package com.coppermux.cutools.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.coppermux.cutools.ui.theme.concentricIcon
import com.coppermux.cutools.ui.theme.concentricInner
import com.coppermux.cutools.ui.theme.concentricMiddle
import com.coppermux.cutools.ui.theme.concentricOuter
import com.coppermux.cutools.ui.theme.success
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coppermux.cutools.ui.shell.WorkspaceProject
import java.text.SimpleDateFormat
import java.util.*

enum class TargetReachability {
    CONNECTED, CONNECTING, DISCONNECTED
}

enum class DeviceMode {
    MOBILE, DESKTOP
}

data class WorkspaceSession(
    val id: String,
    val name: String,
    val domain: String,
    val lastActiveTimestamp: Long,
    val reachability: TargetReachability = TargetReachability.DISCONNECTED,
    val deviceMode: DeviceMode = DeviceMode.MOBILE,
    val isIncognito: Boolean = false,
    val isPinned: Boolean = false
)

class WorkspaceSessionManager {
    private val _sessions = mutableStateListOf<WorkspaceSession>()
    val sessions: List<WorkspaceSession> get() = _sessions

    fun addOrUpdateSession(
        domain: String,
        reachability: TargetReachability,
        deviceMode: DeviceMode,
        isIncognito: Boolean
    ) {
        val existingIndex = _sessions.indexOfFirst { it.domain == domain }
        val now = System.currentTimeMillis()
        val autoName = generateAutoName(domain, now)

        if (existingIndex != -1) {
            val existing = _sessions[existingIndex]
            _sessions.removeAt(existingIndex)
            _sessions.add(
                0,
                existing.copy(
                    lastActiveTimestamp = now,
                    reachability = reachability,
                    deviceMode = deviceMode,
                    isIncognito = isIncognito
                )
            )
        } else {
            val newSession = WorkspaceSession(
                id = UUID.randomUUID().toString(),
                name = autoName,
                domain = domain,
                lastActiveTimestamp = now,
                reachability = reachability,
                deviceMode = deviceMode,
                isIncognito = isIncognito
            )
            _sessions.add(0, newSession)
            enforceLruEviction()
        }
    }

    fun pinSession(id: String) {
        val pinnedCount = _sessions.count { it.isPinned }
        val index = _sessions.indexOfFirst { it.id == id }
        if (index != -1 && !_sessions[index].isPinned) {
            if (pinnedCount < 3) {
                _sessions[index] = _sessions[index].copy(isPinned = true)
            }
        }
    }

    fun unpinSession(id: String) {
        val index = _sessions.indexOfFirst { it.id == id }
        if (index != -1) {
            _sessions[index] = _sessions[index].copy(isPinned = false)
        }
    }

    fun renameSession(id: String, newName: String) {
        val index = _sessions.indexOfFirst { it.id == id }
        if (index != -1 && newName.isNotBlank()) {
            _sessions[index] = _sessions[index].copy(name = newName)
        }
    }

    fun deleteSession(id: String) {
        _sessions.removeAll { it.id == id }
    }

    fun updateReachabilityAll(pings: Map<String, TargetReachability>) {
        _sessions.indices.forEach { i ->
            val session = _sessions[i]
            pings[session.domain]?.let { newStatus ->
                _sessions[i] = session.copy(reachability = newStatus)
            }
        }
    }

    private fun enforceLruEviction() {
        if (_sessions.size > 9) {
            val lastUnpinnedIndex = _sessions.indexOfLast { !it.isPinned }
            if (lastUnpinnedIndex != -1) {
                _sessions.removeAt(lastUnpinnedIndex)
            } else if (_sessions.size > 9) {
                _sessions.removeAt(_sessions.lastIndex)
            }
        }
    }

    private fun generateAutoName(domain: String, timestamp: Long): String {
        val dateStr = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date(timestamp))
        val cleanDomain = domain.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val shortDomain = cleanDomain.take(8).uppercase(Locale.US)
        return "$shortDomain-$dateStr"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
    sessionManager: WorkspaceSessionManager,
    onRestoreSession: (WorkspaceSession) -> Unit,
    onRefreshPing: () -> Unit,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedSessionForMenu by remember { mutableStateOf<WorkspaceSession?>(null) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameTargetSession by remember { mutableStateOf<WorkspaceSession?>(null) }
    var renameInputText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Workspaces",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (sessionManager.sessions.isEmpty()) {
                EmptyWorkspacesState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = sessionManager.sessions,
                        key = { it.id }
                    ) { session ->
                        WorkspaceTile(
                            session = session,
                            onTap = {
                                sessionManager.addOrUpdateSession(
                                    domain = session.domain,
                                    reachability = session.reachability,
                                    deviceMode = session.deviceMode,
                                    isIncognito = session.isIncognito
                                )
                                onRestoreSession(session)
                            },
                            onLongPress = {
                                selectedSessionForMenu = session
                            }
                        )
                    }
                }
            }
        }
    }

    selectedSessionForMenu?.let { session ->
        DropdownMenu(
            expanded = true,
            onDismissRequest = { selectedSessionForMenu = null }
        ) {
            DropdownMenuItem(
                text = { Text(if (session.isPinned) "Unpin" else "Pin") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null
                    )
                },
                onClick = {
                    if (session.isPinned) {
                        sessionManager.unpinSession(session.id)
                    } else {
                        sessionManager.pinSession(session.id)
                    }
                    selectedSessionForMenu = null
                }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    renameTargetSession = session
                    renameInputText = session.name
                    renameDialogOpen = true
                    selectedSessionForMenu = null
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    sessionManager.deleteSession(session.id)
                    selectedSessionForMenu = null
                }
            )
        }
    }

    if (renameDialogOpen && renameTargetSession != null) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text("Rename Workspace") },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    singleLine = true,
                    label = { Text("Session Name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTargetSession?.let { target ->
                            sessionManager.renameSession(target.id, renameInputText)
                        }
                        renameDialogOpen = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceTile(
    session: WorkspaceSession,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusDot(reachability = session.reachability)
                    Text(
                        text = if (session.deviceMode == DeviceMode.MOBILE) "M" else "D",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (session.isIncognito) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "INC",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                            )
                        }
                    }
                }

                if (session.isPinned) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = "Pinned",
                            modifier = Modifier
                                .padding(2.dp)
                                .size(10.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = session.domain,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = formatRelativeTime(session.lastActiveTimestamp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun StatusDot(reachability: TargetReachability) {
    val color = when (reachability) {
        TargetReachability.CONNECTED -> MaterialTheme.colorScheme.success
        TargetReachability.CONNECTING -> MaterialTheme.colorScheme.tertiary
        TargetReachability.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun EmptyWorkspacesState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.concentricOuter),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.concentricMiddle),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.concentricInner),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.concentricIcon,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No sessions yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Navigate to a URL to launch your first active inspection session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diffSec = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        diffSec < 60 -> "just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86400}d ago"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeWorkspace(
    projects: List<WorkspaceProject> = emptyList(),
    onLoadWorkspace: (WorkspaceProject) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Workspaces",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${projects.size} saved project${if (projects.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (projects.isEmpty()) {
                EmptyWorkspacesState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = projects,
                        key = { it.id },
                    ) { project ->
                        WorkspaceProjectTile(
                            project = project,
                            onTap = { onLoadWorkspace(project) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceProjectTile(
    project: WorkspaceProject,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp)),
        onClick = onTap,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "${project.activeInterceptorsCount} rules",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = project.targetUrl,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = project.lastModified,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}
