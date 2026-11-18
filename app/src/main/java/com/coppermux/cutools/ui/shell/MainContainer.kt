package com.coppermux.cutools.ui.shell

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer
import com.coppermux.cutools.data.model.WorkspaceCategory
import com.coppermux.cutools.ui.browser.BrowserView
import com.coppermux.cutools.ui.browser.DevToolsDashboard
import com.coppermux.cutools.ui.homescreen.WorkspacesScreen
import com.coppermux.cutools.ui.theme.CuToolsTheme

/**
 * Phase 17/18 / Redesign — the core dual-axis navigation shell.
 *
 * Hosts:
 * - A [Scaffold] whose [TopBar] surfaces the address bar + hamburger menu.
 * - A [HorizontalPager] with Screen 0 (Browser — Home Workspace grid, or the
 *   active web viewport after a URL navigation) and Screen 1 (DevTools
 *   Dashboard live interceptor feed). Users swipe horizontally to navigate
 *   between the two screens.
 * - A persistent ChatGPT-style [JavaScriptConsoleShell] input pill anchored at
 *   the bottom of the shell across both Browser and Dashboard views, supporting
 *   syntax shortcuts and an expandable console output overlay positioned above
 *   the software keyboard.
 */
@Composable
fun MainContainer(
    viewModel: ShellViewModel,
    webViewEngine: WebViewEngine,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var isUrlBarFocused by remember { mutableStateOf(false) }

    // Capacity & save feedback toasts
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // One-shot shell messages surface through the snackbar.
    LaunchedEffect(state.transientMessage) {
        val message = state.transientMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearTransient()
    }

    CuToolsTheme(darkTheme = state.useDarkTheme) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopBar(
                    viewModel = viewModel,
                    state = state,
                    onUrlBarFocusChanged = { isUrlBarFocused = it },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            val coroutineScope = rememberCoroutineScope()
            val pagerState = rememberPagerState(
                initialPage = state.currentPage.ordinal,
                pageCount = { 2 },
            )
            LaunchedEffect(state.currentPage) {
                pagerState.animateScrollToPage(state.currentPage.ordinal)
            }
            LaunchedEffect(pagerState.currentPage) {
                DiagnosticTracer.trace(
                    category = "F12_NAVIGATION",
                    event = "PAGER_PAGE_SHOWN",
                    "pageIndex" to pagerState.currentPage,
                    "pageName" to (if (pagerState.currentPage == 0) "BROWSER" else "DEVTOOLS"),
                )
                viewModel.onPageShown(pagerState.currentPage)
            }

            // Android Back closes DevTools Dashboard (Screen 1) and returns to Browser (Screen 0)
            BackHandler(enabled = pagerState.currentPage == 1) {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(0)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 68.dp),
                ) { page ->
                    when (page) {
                        0 -> BrowserWorkspacePage(
                            viewModel = viewModel,
                            state = state,
                            webViewEngine = webViewEngine,
                        )
                        1 -> DevToolsDashboard(
                            dashboardState = state.dashboardState,
                            activeFaultRules = state.activeFaultRules,
                            currentPageUrl = viewModel.getAuthoritativeUrl() ?: state.currentUrl ?: state.addressQuery.takeIf { it.isNotBlank() },
                            isRecording = state.isRecording,
                            isNetworkDropped = state.isNetworkDropped,
                            onFilterSelected = viewModel::setDashboardFilter,
                            onSearchQueryChanged = viewModel::setDashboardSearch,
                            onClearFeed = viewModel::clearLogs,
                            onApplyFaultRule = viewModel::applyFaultRule,
                            onRemoveFaultRule = viewModel::removeFaultRule,
                            onToggleDropConnection = viewModel::setNetworkDrop,
                            toggleRecording = viewModel::toggleRecording,
                            onShareTrace = { DiagnosticTracer.shareTrace(context) },
                        )
                    }
                }

                // Persistent bottom JavaScript Console pill & expanded overlay
                JavaScriptConsoleShell(
                    viewModel = viewModel,
                    state = state,
                    isUrlBarFocused = isUrlBarFocused,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // Floating F12 navigation button
                if (state.isF12ButtonVisible) {
                    FloatingF12Button(
                        onTap = {
                            val targetPage = if (pagerState.currentPage == 0) 1 else 0
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(targetPage)
                            }
                        },
                        onHide = {
                            viewModel.hideF12Button()
                        },
                        onPositionChanged = { xRatio, yRatio ->
                            viewModel.updateF12Position(xRatio, yRatio)
                        },
                        initialXRatio = state.f12PositionXRatio,
                        initialYRatio = state.f12PositionYRatio,
                    )
                }
            }
        }

        if (state.showAbout) {
            AboutDialog(
                onDismiss = viewModel::dismissAbout,
            )
        }

        if (state.showSaveCategoryDialog) {
            SaveCategoryDialog(
                onSelectCategory = viewModel::saveCurrentSession,
                onDismiss = viewModel::dismissSaveCategoryChoice,
            )
        }
    }
}

@Composable
private fun BrowserWorkspacePage(
    viewModel: ShellViewModel,
    state: ShellUiState,
    webViewEngine: WebViewEngine,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        BrowserView(
            url = state.currentUrl ?: "",
            connectionActive = state.statusLabel == "ATTACHED",
            webViewEngine = webViewEngine,
            isVisible = !state.workspaceVisible,
            navigationId = state.navigationId,
            isBackEnabled = !state.workspaceVisible && state.currentPage == ShellPage.BROWSER,
            onBackToWorkspace = { viewModel.backToWorkspace() },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.workspaceVisible) {
            WorkspacesScreen(
                onNavigateToBrowser = { session ->
                    viewModel.loadWorkspace(session)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SaveCategoryDialog(
    onSelectCategory: (WorkspaceCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Save Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Choose a workspace category:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    onClick = { onSelectCategory(WorkspaceCategory.WORK) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Work,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "Work",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "High-priority work workspaces",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Surface(
                    onClick = { onSelectCategory(WorkspaceCategory.HOME) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "Home",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Standard personal workspaces",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "Cu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Column {
                    Text(
                        text = "CuTools",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "V1.0.0 (GA) · DevTools Suite",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "A mobile-first Chrome DevTools Protocol (CDP) client and embedded browser inspection suite for Android.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "• 23 Backing CDP Domains · 98 Commands",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "• Live Chronological Network/Console Feed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "• Real-Time Fault Injection Engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "• No-Fake-Capability Real CDP Contract",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.SemiBold)
            }
        },
    )
}