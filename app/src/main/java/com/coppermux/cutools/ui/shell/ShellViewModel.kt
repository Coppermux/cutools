package com.coppermux.cutools.ui.shell

import android.util.Log
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.DashboardEventStreamListener
import com.coppermux.cutools.core.browser.DashboardFilter
import com.coppermux.cutools.core.browser.DashboardStreamState
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.connection.ConnectionState
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.console.ConsoleInspectorListener
import com.coppermux.cutools.core.console.ConsoleSeverity
import com.coppermux.cutools.core.console.ConsoleState
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer
import com.coppermux.cutools.core.kernel.KernelSnapshot
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceCategory
import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.data.repository.SaveSessionResult
import com.coppermux.cutools.data.repository.SessionRepository
import com.coppermux.cutools.domain.SessionNameGenerator
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "CuTools/ShellViewModel"

private fun logTrace(tag: String, message: String) {
    try {
        Log.d(tag, message)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
    println("[$tag] $message")
}

/**
 * Phase 17/18 — shell ViewModel.
 *
 * Owns the shell UI state ([state]) and bridges the genuine application engines
 * into the UI:
 * - Connection status: subscribes to the [ConnectionManager] state listener and
 *   mirrors the exact connection state name into [ShellUiState.statusLabel].
 * - Fault/log counters: subscribes to the [ConsoleInspector] and derives
 *   [ShellUiState.faultCount] (ERROR/WARNING entries) and
 *   [ShellUiState.logCount] (all entries) from genuine console entries.
 * - Java Console drawer: mirrors the live [ConsoleState] and forwards drawer
 *   input/submit to the real [ConsoleInspector] and [ExecutionEngine]. Evaluation only runs
 *   when a session is bound; otherwise an honest "attach a target" message is
 *   published by the backend — never a fabricated console entry.
 * - Phase 18 dashboard feed: subscribes to the [DashboardEventStream] fed by
 *   the embedded WebView interceptor engine and mirrors the live
 *   [DashboardStreamState] into [ShellUiState.dashboardState] for the
 *   chronological DevTools Dashboard. Filter chips and the live search query
 *   are forwarded to the stream (presentation state only).
 * - Phase 19 fault injection: owns the shared [FaultInterceptorEngine] (the
 *   same instance driving [com.coppermux.cutools.core.browser.WebViewEngine]
 *   interception) and mirrors its active [FaultRule]s into
 *   [ShellUiState.activeFaultRules], which the Dashboard uses for in-card
 *   `⚡ Fault Active` badges. [applyFaultRule] / [removeFaultRule] /
 *   [clearFaultRules] operate the engine directly.
 *
 * [state] is a thread-safe [StateFlow]; engine callbacks are delivered on
 * worker threads and only flow fields are mutated here.
 */
class ShellViewModel(
    private val connectionManager: ConnectionManager,
    private val consoleInspector: ConsoleInspector,
    private val executionEngine: ExecutionEngine,
    private val dashboardEventStream: DashboardEventStream,
    private val faultInterceptorEngine: FaultInterceptorEngine,
    val cdpBridge: AndroidCdpBridge? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val networkEngine: NetworkEngine? = null,
    val webViewEngine: WebViewEngine? = null,
    val sessionRepository: SessionRepository? = null,
    val sessionNameGenerator: SessionNameGenerator? = null,
    val f12Preferences: F12ButtonPreferences = InMemoryF12ButtonPreferences(),
) {

    private val _state = MutableStateFlow(
        ShellUiState.empty().copy(
            isF12ButtonVisible = f12Preferences.isVisible(),
            f12PositionXRatio = f12Preferences.getPosition().first,
            f12PositionYRatio = f12Preferences.getPosition().second,
        )
    )
    val state: StateFlow<ShellUiState> = _state

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private val _isRecording = MutableStateFlow(dashboardEventStream.state.isRecording)
    val isRecording: StateFlow<Boolean> = _isRecording

    /**
     * Observable DiagnosticKernel state (session identity, epoch, lifecycle).
     * Exposed for observation only; Phase 1 does not render it in the UI.
     */
    val kernelState: StateFlow<KernelSnapshot> = connectionManager.kernel.state

    private val dashboardListener = object : DashboardEventStreamListener {
        override fun onDashboardStateChanged(streamState: DashboardStreamState) {
            _isRecording.value = streamState.isRecording
            publish {
                it.copy(
                    dashboardState = streamState,
                    isRecording = streamState.isRecording,
                )
            }
        }
    }

    init {
        connectionManager.addStateListener(object : ConnectionManager.StateListener {
            override fun onStateChanged(state: ConnectionState, error: Throwable?) {
                publish { it.copy(statusLabel = state.name) }
            }
        })
        consoleInspector.addListener(object : ConsoleInspectorListener {
            override fun onConsoleStateChanged(consoleState: ConsoleState) {
                publishConsoleState(consoleState)
            }

            override fun onError(error: Throwable) {
                publish { it.copy(transientMessage = error.message ?: "Console error") }
            }
        })
        dashboardEventStream.addListener(dashboardListener)
        publish { it.copy(statusLabel = connectionManager.connectionState.name) }
        publish {
            it.copy(
                dashboardState = dashboardEventStream.state,
                isRecording = dashboardEventStream.state.isRecording,
            )
        }
        publish { it.copy(activeFaultRules = faultInterceptorEngine.all()) }
        publishConsoleState(consoleInspector.state)

        webViewEngine?.addDocumentUrlListener { committedUrl ->
            onDocumentUrlCommitted(committedUrl)
        }

        publish { it.copy(isNetworkDropped = connectionManager.kernel.isNetworkDropped) }

        // Bind shell-managed subsystems to DiagnosticKernel ResetCoordinator (Phase 2)
        connectionManager.kernel.resetCoordinator.bindSubsystems(
            dispatcher = connectionManager.dispatcher,
            targetManagerProvider = { connectionManager.targetManager },
            faultEngine = faultInterceptorEngine,
            networkEngine = networkEngine,
            consoleInspector = consoleInspector,
            dashboardEventStream = dashboardEventStream,
            executionEngine = executionEngine,
            webViewEngine = webViewEngine,
        )
    }

    private fun publishConsoleState(consoleState: ConsoleState) {
        publish {
            it.copy(
                consoleState = consoleState,
                faultCount = consoleState.entries.count { entry ->
                    entry.severity == ConsoleSeverity.ERROR || entry.severity == ConsoleSeverity.WARNING
                },
                logCount = consoleState.entries.size,
            )
        }
    }

    // ================================================================
    // Navigation
    // ================================================================

    fun updateAddress(text: String) {
        publish { it.copy(addressQuery = text) }
    }

    /**
     * Synchronizes [ShellUiState.currentUrl] and [ShellUiState.addressQuery] with
     * the committed WebView URL after navigation and redirects.
     */
    fun onDocumentUrlCommitted(committedUrl: String) {
        if (committedUrl.isBlank() || committedUrl == "about:blank") return
        if (_state.value.workspaceVisible) return
        val current = _state.value.currentUrl
        if (current != committedUrl) {
            publish {
                it.copy(
                    currentUrl = committedUrl,
                    addressQuery = committedUrl,
                )
            }
        }
    }

    /**
     * Connects to a Chrome debugging endpoint.
     */
    fun connect(url: String = ConnectionManager.DEFAULT_DEBUG_URL) {
        val current = connectionManager.connectionState
        if (current == ConnectionState.DISCONNECTED || current == ConnectionState.ERROR) {
            connectionManager.connect(url, onError = { error ->
                publish { it.copy(transientMessage = "CDP connection error: ${error.message}") }
            })
        }
    }

    /**
     * Disconnects cleanly from the Chrome debugging endpoint.
     */
    fun disconnect() {
        connectionManager.disconnect()
        cdpBridge?.stop()
    }

    /**
     * Executes the address bar: normalizes the query and swaps the Home
     * Workspace grid for the active web viewport. Also initiates CDP connection
     * if the input is a ws:// URL or triggers default CDP connection via native bridge.
     */
    fun navigate() {
        val raw = _state.value.addressQuery.trim()
        if (raw.isEmpty()) return
        if (raw.startsWith("ws://") || raw.startsWith("wss://")) {
            publish {
                it.copy(
                    transientMessage = "Connecting to CDP endpoint...",
                    addressQuery = raw,
                )
            }
            connectionManager.connect(raw, onError = { error ->
                publish { it.copy(transientMessage = "CDP connection error: ${error.message}") }
            }) {
                publish { it.copy(transientMessage = "Connected to CDP endpoint") }
            }
            return
        }
        val normalized = normalizeUrl(raw)
        publish { it.copy(activeWorkspaceId = null) }
        navigateToUrl(normalized)
    }

    /**
     * Verifies that the kernel's active diagnostic session is attached, is
     * present in discovered targets, and belongs to a valid page target.
     *
     * DiagnosticKernel Phase 1: session identity is taken from the kernel
     * (the authority), not from ConnectionState or TargetManager selection.
     * The kernel session is the target every inspector was last bound to.
     */
    fun isSessionAlignedAndUsable(): Boolean {
        val session = connectionManager.kernel.currentSession ?: return false
        val tm = connectionManager.targetManager ?: return false
        val targetInfo = tm.discoveredTargets.firstOrNull { it.targetId == session.targetId } ?: return false
        if (!targetInfo.isPage) return false
        if (targetInfo.isWindowAttached == false) return false
        return true
    }

    private fun navigateToUrl(normalized: String, statusMessage: String? = null) {
        val currentStatus = connectionManager.connectionState
        val isAlreadyAttached = isSessionAlignedAndUsable()

        if (isAlreadyAttached || cdpBridge == null) {
            publish {
                it.copy(
                    currentUrl = normalized,
                    workspaceVisible = false,
                    addressQuery = normalized,
                    transientMessage = statusMessage,
                    navigationId = it.navigationId + 1,
                )
            }
            if (cdpBridge == null && currentStatus == ConnectionState.DISCONNECTED) {
                connect(ConnectionManager.DEFAULT_DEBUG_URL)
            }
            return
        }

        // Bridge is available, but CDP session is not yet attached.
        // Swap to the BrowserView first to let WebView initialize WebContents debugging
        // and create the abstract Unix domain socket, while delaying loadUrl until CDP attaches.
        publish {
            it.copy(
                currentUrl = null,
                workspaceVisible = false,
                addressQuery = normalized,
                transientMessage = statusMessage,
            )
        }
        connectViaBridge {
            publish {
                it.copy(
                    currentUrl = normalized,
                    navigationId = it.navigationId + 1,
                )
            }
        }
    }

    private fun connectViaBridge(onAttached: (() -> Unit)? = null) {
        val bridge = cdpBridge ?: return
        coroutineScope.launch {
            try {
                // If connection is already active and only target attachment is missing or misaligned, recover at target level
                if (connectionManager.connectionState.isActive()) {
                    val tm = connectionManager.targetManager
                    if (tm != null && !isSessionAlignedAndUsable()) {
                        val pageTarget = tm.findBestPageTarget(webViewEngine?.currentDocumentUrl)
                        if (pageTarget != null) {
                            tm.selectTarget(pageTarget.targetId)
                            tm.attachToSelectedTarget { }
                        } else {
                            tm.discoverTargets { }
                        }
                        var waitedMs = 0
                        while (waitedMs < 2000 &&
                            !isSessionAlignedAndUsable() &&
                            connectionManager.connectionState != ConnectionState.ERROR
                        ) {
                            kotlinx.coroutines.delay(50)
                            waitedMs += 50
                        }
                        if (isSessionAlignedAndUsable()) {
                            onAttached?.invoke()
                            return@launch
                        }
                    }
                }

                bridge.start()
                val isReady = bridge.awaitReady(timeoutMs = 4000)
                if (isReady) {
                    val debugUrl = bridge.debugUrl
                    connect(debugUrl)
                    // Wait for isSessionAlignedAndUsable() or ERROR, with a 2000 ms timeout.
                    var waitedMs = 0
                    while (waitedMs < 2000 &&
                        !isSessionAlignedAndUsable() &&
                        connectionManager.connectionState != ConnectionState.ERROR
                    ) {
                        kotlinx.coroutines.delay(50)
                        waitedMs += 50
                    }
                    if (isSessionAlignedAndUsable()) {
                        onAttached?.invoke()
                    } else if (connectionManager.connectionState == ConnectionState.ERROR) {
                        val errMsg = connectionManager.lastErrorValue?.message
                        val detail = if (!errMsg.isNullOrBlank()) ": $errMsg" else ""
                        publish {
                            it.copy(transientMessage = "CDP connection error$detail; navigation aborted.")
                        }
                    } else {
                        publish {
                            it.copy(transientMessage = "CDP target attachment timed out; navigation aborted.")
                        }
                    }
                } else {
                    publish {
                        it.copy(transientMessage = "WebView DevTools endpoint failed to initialize; navigation aborted.")
                    }
                }
            } catch (e: Exception) {
                publish {
                    it.copy(transientMessage = "CDP bridge error: ${e.message}; navigation aborted.")
                }
            }
        }
    }

    /** Returns to the Workspaces grid, dropping the active navigation. */
    fun backToWorkspace() {
        publish {
            it.copy(
                currentUrl = null,
                workspaceVisible = true,
                addressQuery = "",
                navigationId = it.navigationId + 1,
                activeWorkspaceId = null,
                showSaveCategoryDialog = false,
            )
        }
    }

    /**
     * Loads a saved workspace session by navigating to its target URL and
     * swapping the Workspaces launcher for the active web viewport.
     */
    fun loadWorkspace(session: WorkspaceSession) {
        val normalized = normalizeUrl(session.targetUrl)
        publish {
            it.copy(
                activeWorkspaceId = session.id,
            )
        }
        navigateToUrl(normalized, statusMessage = "Loaded workspace: ${session.displayName}")
    }

    /**
     * Legacy overload for WorkspaceProject launcher.
     */
    fun loadWorkspace(project: WorkspaceProject) {
        val normalized = normalizeUrl(project.targetUrl)
        publish {
            it.copy(
                activeWorkspaceId = project.id,
            )
        }
        navigateToUrl(normalized, statusMessage = "Loaded workspace: ${project.name}")
    }

    /**
     * Resolves the authoritative current URL of the active browser session.
     */
    fun getAuthoritativeUrl(): String? {
        val docUrl = webViewEngine?.currentDocumentUrl
        if (!docUrl.isNullOrBlank() && docUrl != "about:blank" && isValidUrl(docUrl)) {
            return docUrl
        }
        val currentUrl = _state.value.currentUrl
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank" && isValidUrl(currentUrl)) {
            return currentUrl
        }
        return null
    }

    /**
     * Validates whether a URL is a usable, non-blank http/https URL with a host.
     */
    fun isValidUrl(url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            (scheme == "http" || scheme == "https") && !host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Initiates the manual Save Session workflow.
     * Rejects blank/invalid URLs with clear feedback without opening category dialog.
     */
    fun openSaveCategoryChoice() {
        val activeUrl = getAuthoritativeUrl()
        if (activeUrl == null) {
            publish {
                it.copy(
                    transientMessage = "Cannot save workspace: no active web session.",
                )
            }
            return
        }
        publish { it.copy(showSaveCategoryDialog = true) }
    }

    /**
     * Dismisses the Save Session category selection dialog.
     */
    fun dismissSaveCategoryChoice() {
        publish { it.copy(showSaveCategoryDialog = false) }
    }

    /**
     * Saves the current active browser session under the specified category (WORK or HOME).
     */
    fun saveCurrentSession(category: WorkspaceCategory) {
        publish { it.copy(showSaveCategoryDialog = false) }
        val repository = sessionRepository ?: run {
            publish { it.copy(transientMessage = "Session repository is not available.") }
            return
        }
        val activeUrl = getAuthoritativeUrl()
        if (activeUrl == null) {
            publish { it.copy(transientMessage = "Cannot save workspace: no active web session.") }
            return
        }

        coroutineScope.launch {
            val now = System.currentTimeMillis()
            val domain = sessionNameGenerator?.extractDomain(activeUrl) ?: extractDomainFallback(activeUrl)
            val currentActiveId = _state.value.activeWorkspaceId

            val sessionToSave: WorkspaceSession = if (currentActiveId != null) {
                val existing = repository.getSessionById(currentActiveId)
                if (existing != null) {
                    existing.copy(
                        targetUrl = activeUrl,
                        domain = domain,
                        category = category,
                        lastActiveAt = now,
                    )
                } else {
                    val displayName = sessionNameGenerator?.generateName(activeUrl, now)
                        ?: "$domain · ${formatTime(now)}"
                    WorkspaceSession(
                        id = UUID.randomUUID().toString(),
                        targetUrl = activeUrl,
                        domain = domain,
                        displayName = displayName,
                        category = category,
                        lastActiveAt = now,
                        createdAt = now,
                        status = if (_state.value.statusLabel == "ATTACHED") ConnectionStatus.ACTIVE else ConnectionStatus.UNKNOWN,
                    )
                }
            } else {
                val displayName = sessionNameGenerator?.generateName(activeUrl, now)
                    ?: "$domain · ${formatTime(now)}"
                WorkspaceSession(
                    id = UUID.randomUUID().toString(),
                    targetUrl = activeUrl,
                    domain = domain,
                    displayName = displayName,
                    category = category,
                    lastActiveAt = now,
                    createdAt = now,
                    status = if (_state.value.statusLabel == "ATTACHED") ConnectionStatus.ACTIVE else ConnectionStatus.UNKNOWN,
                )
            }

            when (val result = repository.saveSession(sessionToSave)) {
                is SaveSessionResult.Created -> {
                    publish {
                        it.copy(
                            activeWorkspaceId = result.session.id,
                            transientMessage = "Saved ${result.session.displayName} to ${category.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                        )
                    }
                }
                is SaveSessionResult.Updated -> {
                    publish {
                        it.copy(
                            activeWorkspaceId = result.session.id,
                            transientMessage = "Updated ${result.session.displayName}",
                        )
                    }
                }
                is SaveSessionResult.CapacityReached -> {
                    _toastEvents.emit("Workspaces is full. Delete a workspace to free a slot.")
                    publish {
                        it.copy(
                            transientMessage = "Workspaces is full. Delete a workspace to free a slot.",
                        )
                    }
                }
                is SaveSessionResult.Failure -> {
                    publish {
                        it.copy(
                            transientMessage = "Failed to save workspace: ${result.cause.message}",
                        )
                    }
                }
            }
        }
    }

    private fun extractDomainFallback(url: String): String {
        return try {
            val uri = URI(url)
            uri.host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    /** Sets the shell axis directly (bottom carousel tap). */
    fun setPage(page: ShellPage) {
        publish { it.copy(currentPage = page) }
    }

    /** Mirrors a horizontal pager swipe into the shell axis. */
    fun onPageShown(index: Int) {
        val page = if (index <= 0) ShellPage.BROWSER else ShellPage.DASHBOARD
        if (_state.value.currentPage != page) {
            publish { it.copy(currentPage = page) }
        }
    }

    // ================================================================
    // Menu toggles & actions
    // ================================================================

    /**
     * Coordinated global Reset App action.
     *
     * Requests a kernel-coordinated reset from [DiagnosticKernel.performReset] (Phase 2),
     * centralizing subsystem cleanup, store resets, and session isolation in the kernel.
     * Resets presentation UI state while strictly preserving persistent preferences
     * (dark theme preference, saved projects, database, browser cookies, and valid connection).
     */
    fun resetApp() {
        val result = connectionManager.kernel.performReset("Reset App")
        logTrace(TAG, "resetApp: kernel performReset returned $result")
        publish {
            it.copy(
                currentPage = ShellPage.BROWSER,
                addressQuery = "",
                currentUrl = null,
                workspaceVisible = true,
                menuOpen = false,
                drawerOpen = false,
                showAbout = false,
                transientMessage = null,
                activeFaultRules = emptyList(),
                navigationId = it.navigationId + 1,
                showSaveCategoryDialog = false,
                activeWorkspaceId = null,
            )
        }
        dashboardEventStream.reset()
        clearLogs()
    }

    fun setMenuOpen(open: Boolean) {
        publish { it.copy(menuOpen = open) }
    }

    fun hideF12Button() {
        f12Preferences.setVisible(false)
        publish { it.copy(isF12ButtonVisible = false) }
    }

    fun restoreF12Button() {
        f12Preferences.setVisible(true)
        publish { it.copy(isF12ButtonVisible = true) }
    }

    fun updateF12Position(xRatio: Float, yRatio: Float) {
        f12Preferences.setPosition(xRatio, yRatio)
        publish { it.copy(f12PositionXRatio = xRatio, f12PositionYRatio = yRatio) }
    }

    fun toggleTheme() {
        publish { it.copy(useDarkTheme = !it.useDarkTheme) }
    }

    fun showAbout() {
        publish { it.copy(showAbout = true) }
    }

    fun dismissAbout() {
        publish { it.copy(showAbout = false) }
    }

    fun clearTransient() {
        publish { it.copy(transientMessage = null) }
    }

    // ================================================================
    // Java Console drawer
    // ================================================================

    fun setDrawerOpen(open: Boolean) {
        publish { it.copy(drawerOpen = open) }
    }

    fun onConsoleInput(text: String) {
        consoleInspector.setInput(text)
    }

    fun onConsoleSubmit(expression: String? = null) {
        val currentSession = consoleInspector.session
        val inspectorId = consoleInspector.diagnosticInstanceId
        if (currentSession != null) {
            logTrace(
                TAG,
                "submit attempted [inspector=$inspectorId] sessionIsNull=false targetId=${currentSession.targetId} sessionId=${currentSession.sessionId} expression='$expression'"
            )
            consoleInspector.submitInput(expression)
        } else {
            logTrace(
                TAG,
                "submit attempted [inspector=$inspectorId] sessionIsNull=true targetId=null sessionId=null expression='$expression'"
            )
            if (expression != null && expression.isNotBlank()) {
                consoleInspector.setInput(expression)
            }
            publish { it.copy(transientMessage = "Attach a target before evaluating in the Java Console.") }
        }
    }

    fun onConsoleHistoryBack() {
        consoleInspector.historyBack()
    }

    fun onConsoleHistoryForward() {
        consoleInspector.historyForward()
    }

    // ================================================================
    // Phase 18 — DevTools Dashboard feed
    // ================================================================

    /** Selects the active dashboard filter chip. */
    fun setDashboardFilter(filter: DashboardFilter) {
        dashboardEventStream.setFilter(filter)
    }

    /** Updates the live dashboard search query (URL/message text). */
    fun setDashboardSearch(query: String) {
        dashboardEventStream.setSearchQuery(query)
    }

    /**
     * Non-destructive coordinated action to clear diagnostic feeds and logs:
     * - Clears console inspector entries and input
     * - Clears underlying network engine request store (if configured)
     * - Clears intercepted dashboard event stream
     * - Resets presentation logCount and faultCount badges
     *
     * Strictly maintains the active page session, WebView history, URL, DOM,
     * fault injection rules, and kernel epoch without navigation disruption.
     */
    fun clearLogs() {
        val currentFilter = dashboardEventStream.state.filter
        val currentSearch = dashboardEventStream.state.searchQuery
        val currentRecording = dashboardEventStream.state.isRecording

        if (networkEngine != null) {
            networkEngine.clear()
            // Preserve user-selected dashboard presentation filter and search query
            dashboardEventStream.setFilter(currentFilter)
            dashboardEventStream.setSearchQuery(currentSearch)
            dashboardEventStream.setRecording(currentRecording)
        } else {
            dashboardEventStream.clear()
        }
        consoleInspector.clear()
        publish {
            it.copy(
                faultCount = 0,
                logCount = 0,
                consoleState = it.consoleState.copy(
                    entries = emptyList(),
                    input = "",
                ),
                dashboardState = dashboardEventStream.state,
            )
        }
    }

    /**
     * Clears the intercepted dashboard feed and diagnostic logs.
     * Delegates to [clearLogs] to ensure synchronized clearing of the dashboard feed,
     * underlying network requests, console inspector logs, and shell badge counts.
     */
    fun clearDashboardFeed() {
        clearLogs()
    }

    /** Toggles active feed recording on / off in the event ingestion stream. */
    fun toggleRecording() {
        dashboardEventStream.toggleRecording()
    }

    /** Sets active feed recording on / off in the event ingestion stream. */
    fun setRecording(recording: Boolean) {
        dashboardEventStream.setRecording(recording)
    }

    // ================================================================
    // Phase 19 — fault injection rules
    // ================================================================

    /**
     * Authoritatively toggles network drop state across Kotlin DiagnosticKernel,
     * WebViewEngine URL preservation, and CDP network emulation.
     */
    fun setNetworkDrop(dropped: Boolean) {
        val kernelBefore = connectionManager.kernel.snapshot
        val engineBefore = webViewEngine?.isNetworkDropped
        DiagnosticTracer.trace(
            category = "NETWORK_TOGGLE",
            event = "VIEWMODEL_SET_NETWORK_DROP_START",
            "dropped" to dropped,
            "kernelDroppedBefore" to kernelBefore.isNetworkDropped,
            "kernelEpochBefore" to kernelBefore.epoch,
            "engineDroppedBefore" to engineBefore,
        )
        connectionManager.kernel.setNetworkDrop(dropped)
        webViewEngine?.setNetworkDrop(dropped)
        networkEngine?.setNetworkDrop(dropped)
        if (dropped && !isSessionAlignedAndUsable()) {
            reconcileTargetOrConnect()
        }
        val kernelAfter = connectionManager.kernel.snapshot
        DiagnosticTracer.trace(
            category = "NETWORK_TOGGLE",
            event = "VIEWMODEL_SET_NETWORK_DROP_END",
            "dropped" to dropped,
            "kernelDroppedAfter" to kernelAfter.isNetworkDropped,
            "kernelEpochAfter" to kernelAfter.epoch,
        )
        publish { it.copy(isNetworkDropped = connectionManager.kernel.isNetworkDropped) }
    }

    /** Upserts an active fault rule into the shared interceptor engine. */
    fun applyFaultRule(rule: FaultRule) {
        faultInterceptorEngine.upsert(rule)
        publish { it.copy(activeFaultRules = faultInterceptorEngine.all()) }

        if (rule.dropConnection) {
            setNetworkDrop(true)
        }
    }

    /**
     * Attempts target reconciliation or establishes a bridge connection if unattached.
     */
    fun reconcileTargetOrConnect() {
        if (!isSessionAlignedAndUsable()) {
            connectViaBridge()
        }
    }

    /** Removes the active fault rule with [id] (one-tap badge removal). */
    fun removeFaultRule(id: String) {
        if (faultInterceptorEngine.remove(id)) {
            publish { it.copy(activeFaultRules = faultInterceptorEngine.all()) }
        }
    }

    /** Removes every active fault rule. */
    fun clearFaultRules() {
        faultInterceptorEngine.clear()
        publish { it.copy(activeFaultRules = emptyList()) }
    }

    // ================================================================
    // Helpers
    // ================================================================

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun publish(transform: (ShellUiState) -> ShellUiState) {
        _state.value = transform(_state.value)
    }
}