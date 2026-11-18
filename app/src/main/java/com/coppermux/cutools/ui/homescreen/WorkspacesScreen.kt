package com.coppermux.cutools.ui.homescreen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.data.model.WorkspaceSession

/**
 * The Workspaces home screen: a top app bar, pull-to-refresh content, an empty
 * state, and the three-column session grid. No pager wiring yet — Phase C.
 *
 * Suspend view-model actions are launched through [LaunchedEffect] triggers,
 * since the compose event handlers themselves are not suspend scopes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
    onNavigateToBrowser: (WorkspaceSession) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkspacesViewModel = hiltViewModel(),
) {
    val sessions = viewModel.sessions.collectAsStateWithLifecycle()
    val isRefreshing = viewModel.isRefreshing.collectAsStateWithLifecycle()

    val refreshReq = remember { mutableStateOf(0) }
    val renameReq = remember { mutableStateOf<Pair<String, String>?>(null) }
    val deleteReq = remember { mutableStateOf<String?>(null) }
    val navReq = remember { mutableStateOf<WorkspaceSession?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }
    LaunchedEffect(refreshReq.value) {
        if (refreshReq.value > 0) viewModel.refresh()
    }
    LaunchedEffect(renameReq.value) {
        val request = renameReq.value
        if (request != null) {
            viewModel.onRename(request.first, request.second)
            renameReq.value = null
        }
    }
    LaunchedEffect(deleteReq.value) {
        val id = deleteReq.value
        if (id != null) {
            viewModel.onDelete(id)
            deleteReq.value = null
        }
    }
    LaunchedEffect(navReq.value) {
        val session = navReq.value
        if (session != null) {
            viewModel.onTileClick(session, onNavigateToBrowser)
            navReq.value = null
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Workspaces",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing.value,
            onRefresh = { refreshReq.value += 1 },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            if (sessions.value.isEmpty()) {
                EmptyState()
            } else {
                WorkspacesGrid(
                    sessions = sessions.value,
                    onTileClick = { session -> navReq.value = session },
                    onRename = { id, name -> renameReq.value = id to name },
                    onDelete = { id -> deleteReq.value = id },
                )
            }
        }
    }
}

@Composable
fun WorkspacesScreenPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        WorkspacesScreen(
            onNavigateToBrowser = {},
            viewModel = sampleViewModel(),
        )
    }
}

private fun sampleViewModel(): WorkspacesViewModel = throw UnsupportedOperationException(
    "WorkspacesScreen preview requires a real Hilt WorkspacesViewModel; replace with a fake in the design-time preview."
)