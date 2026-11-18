package com.coppermux.cutools.ui.shell

import com.coppermux.cutools.core.browser.DashboardStreamState
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.console.ConsoleState
import com.coppermux.cutools.core.connection.ConnectionState

/**
 * Phase 17 — shell navigation axis.
 *
 * Screen 0 is the Browser view (Home Workspace grid when no page is active,
 * otherwise the active web viewport); Screen 1 is the DevTools Dashboard.
 */
enum class ShellPage {
    BROWSER,
    DASHBOARD,
}

/**
 * Phase 20 — one saved developer workspace session.
 *
 * Encapsulates the persisted state of a target environment so the Home
 * workspace launcher can restore it on selection: the [targetUrl] the session
 * points at, how many network interceptors are active ([activeInterceptorsCount]),
 * and when the session was last touched ([lastModified]). The launcher renders
 * one card per project and notifies the [com.coppermux.cutools.ui.shell.ShellViewModel]
 * to load the saved state when a project is tapped.
 */
data class WorkspaceProject(
    val id: String,
    val name: String,
    val targetUrl: String,
    val activeInterceptorsCount: Int,
    val lastModified: String,
)

/**
 * Phase 17 — immutable shell UI snapshot.
 *
 * Mirrors genuine app state only: [statusLabel] is derived from the real
 * [com.coppermux.cutools.core.connection.ConnectionManager] connection state,
 * [faultCount]/[logCount] are derived from genuine console entries, and
 * [consoleState] is the live [ConsoleState] published by the Console
 * inspector. [currentUrl] only becomes non-null after a genuine address-bar
 * navigation, and the workspace grid stays visible until that happens.
 *
 * @property currentPage The active shell axis ([ShellPage.BROWSER] /
 *   DASHBOARD).
 * @property addressQuery The current address-bar text.
 * @property currentUrl The normalized URL of the active navigation, or null
 *   while the Home Workspace grid is showing.
 * @property workspaceVisible Whether the 4 x 3 Home Workspace grid is visible
 *   (true until a URL navigation swaps in the active web viewport).
 * @property statusLabel The genuine connection state name (e.g. ATTACHED).
 * @property faultCount Count of console entries classified ERROR or WARNING.
 * @property logCount Total genuine console entries received.
 * @property useDarkTheme Whether the dark color scheme is active.
 * @property menuOpen Whether the top-right hamburger menu popup is showing.
 * @property drawerOpen Whether the left Java Console drawer is open.
 * @property showAbout Whether the About dialog is showing.
 * @property transientMessage A one-shot shell message (snackbar), or null.
 * @property consoleState The live genuine [ConsoleState] for the Java drawer.
 * @property dashboardState The live Phase 18 interceptor feed (WebView network
 *   + console events) rendered by the DevTools Dashboard.
 * @property activeFaultRules The live Phase 19 [FaultRule]s mirrored from the
 *   shared [com.coppermux.cutools.core.browser.FaultInterceptorEngine], driving
 *   the per-card `⚡ Fault Active` badges and their one-tap removal.
 * @property savedProjects The saved developer workspace sessions rendered by
 *   the Home workspace launcher ([com.coppermux.cutools.ui.home.HomeWorkspace]).
 */
data class ShellUiState(
    val currentPage: ShellPage = ShellPage.BROWSER,
    val addressQuery: String = "",
    val currentUrl: String? = null,
    val workspaceVisible: Boolean = true,
    val statusLabel: String = "DISCONNECTED",
    val faultCount: Int = 0,
    val logCount: Int = 0,
    val useDarkTheme: Boolean = false,
    val menuOpen: Boolean = false,
    val drawerOpen: Boolean = false,
    val showAbout: Boolean = false,
    val transientMessage: String? = null,
    val consoleState: ConsoleState = ConsoleState.empty(),
    val dashboardState: DashboardStreamState = DashboardStreamState.empty(),
    val activeFaultRules: List<FaultRule> = emptyList(),
    val savedProjects: List<WorkspaceProject> = emptyList(),
    val isRecording: Boolean = true,
    val navigationId: Long = 0L,
    val showSaveCategoryDialog: Boolean = false,
    val activeWorkspaceId: String? = null,
    val isF12ButtonVisible: Boolean = true,
    val f12PositionXRatio: Float = 1.0f,
    val f12PositionYRatio: Float = 0.75f,
    val isNetworkDropped: Boolean = false,
) {
    /** Whether a genuine URL navigation has replaced the Home Workspace grid. */
    val isNavigated: Boolean get() = currentUrl != null

    companion object {
        fun empty(): ShellUiState = ShellUiState()
    }
}