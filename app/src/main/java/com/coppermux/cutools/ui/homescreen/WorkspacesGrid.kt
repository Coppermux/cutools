package com.coppermux.cutools.ui.homescreen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceSession

/** Three-column responsive grid of session tiles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspacesGrid(
    sessions: List<WorkspaceSession>,
    onTileClick: (WorkspaceSession) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = sessions,
            key = { it.id },
        ) { session ->
            WorkspaceTile(
                session = session,
                onClick = { onTileClick(session) },
                onRename = { newName -> onRename(session.id, newName) },
                onDelete = { onDelete(session.id) },
            )
        }
    }
}

@Composable
fun WorkspacesGridPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        WorkspacesGrid(
            sessions = sampleSessions(),
            onTileClick = {},
            onRename = { _, _ -> },
            onDelete = {},
        )
    }
}

private fun sampleSessions(): List<WorkspaceSession> {
    val now = System.currentTimeMillis()
    return listOf(
        WorkspaceSession(id = "a", targetUrl = "https://github.com", domain = "github.com", displayName = "GitHub", isPinned = true, isDesktopMode = true, lastActiveAt = now - 60_000, status = ConnectionStatus.ACTIVE),
        WorkspaceSession(id = "b", targetUrl = "https://stackoverflow.com", domain = "stackoverflow.com", displayName = "Stack Overflow", isIncognito = true, lastActiveAt = now - 300_000, status = ConnectionStatus.CONNECTING),
        WorkspaceSession(id = "c", targetUrl = "https://developer.android.com", domain = "developer.android.com", displayName = "Android Docs", isDesktopMode = true, lastActiveAt = now - 3_600_000, status = ConnectionStatus.INACTIVE),
        WorkspaceSession(id = "d", targetUrl = "http://example.org", domain = "example.org", displayName = "Example Org", isPinned = true, lastActiveAt = now - 86_400_000, status = ConnectionStatus.ACTIVE),
        WorkspaceSession(id = "e", targetUrl = "https://kotlinlang.org", domain = "kotlinlang.org", displayName = "Kotlin", isIncognito = true, isDesktopMode = true, lastActiveAt = now - 604_800_000, status = ConnectionStatus.UNKNOWN),
        WorkspaceSession(id = "f", targetUrl = "https://www.wikipedia.org", domain = "wikipedia.org", displayName = "Wikipedia", lastActiveAt = now - 1_000_000_000, status = ConnectionStatus.INACTIVE),
    )
}