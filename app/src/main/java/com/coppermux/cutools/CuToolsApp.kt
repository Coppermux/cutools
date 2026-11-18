package com.coppermux.cutools

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import com.coppermux.cutools.data.local.CuToolsDatabase
import com.coppermux.cutools.data.repository.SessionRepository
import com.coppermux.cutools.data.repository.SessionRepositoryImpl
import com.coppermux.cutools.domain.SessionNameGenerator
import com.coppermux.cutools.core.accessibility.AccessibilityEngine
import dagger.hilt.android.HiltAndroidApp
import com.coppermux.cutools.core.audits.AuditEngine
import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.ui.browser.DashboardViewModel
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.connection.ConnectionState
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer
import com.coppermux.cutools.core.emulation.EmulationEngine
import com.coppermux.cutools.core.kernel.DiagnosticKernel
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.memory.MemoryEngine
import com.coppermux.cutools.core.network.FetchInterceptionEngine
import com.coppermux.cutools.core.network.MockResponseRuleManager
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.core.performance.PerformanceEngine
import com.coppermux.cutools.core.profiling.CpuProfilerEngine
import com.coppermux.cutools.core.profiling.HeapProfilerEngine
import com.coppermux.cutools.core.profiling.TracingEngine
import com.coppermux.cutools.core.security.SecurityEngine
import com.coppermux.cutools.core.storage.CacheStorageEngine
import com.coppermux.cutools.core.storage.IndexedDbEngine
import com.coppermux.cutools.core.storage.StorageEngine
import com.coppermux.cutools.core.target.TargetEngine
import com.coppermux.cutools.core.target.TargetManagerListener
import com.coppermux.cutools.core.transport.CdpTransport
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import com.coppermux.cutools.tools.profiling.viewmodel.PerformanceToolViewModel
import com.coppermux.cutools.tools.securitystorage.viewmodel.SecurityStorageToolViewModel
import com.coppermux.cutools.ui.accessibility.AccessibilityViewModel
import com.coppermux.cutools.ui.audits.AuditViewModel
import com.coppermux.cutools.ui.emulation.EmulationViewModel
import com.coppermux.cutools.ui.memory.MemoryViewModel
import com.coppermux.cutools.ui.performance.PerformanceViewModel
import com.coppermux.cutools.ui.security.SecurityViewModel
import com.coppermux.cutools.ui.shell.F12ButtonPreferences
import com.coppermux.cutools.ui.shell.SharedPreferencesF12ButtonPreferences
import com.coppermux.cutools.ui.shell.ShellViewModel
import com.coppermux.cutools.ui.target.TargetViewModel

private const val TAG = "CuTools/CuToolsApp"

private fun logTrace(tag: String, message: String) {
    try {
        Log.d(tag, message)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
    println("[$tag] $message")
}

/**
 * CuTools application class.
 *
 * Manages the singleton [ConnectionManager] that coordinates the Chrome
 * debugging connection lifecycle, the [DomInspector] (Phase 2) which inspects
 * the DOM of the currently attached target, the [CssInspector] (Phase 3), and
 * the [JavaConsoleBackend] (SPEC 6B) which evaluates expressions directly
 * against the active target session.
 *
 * Lifecycle wiring: inspectors are bound to a target's CDP session when a target
 * is attached, and detached/cleared on detachment and on connection loss, so no
 * stale tool state leaks across target switches.
 */
@HiltAndroidApp
class CuToolsApp : Application() {

    /**
     * The DiagnosticKernel (architecture consolidation, Phase 1): the single
     * authority for diagnostic session identity, session epochs and lifecycle
     * state. It is fed by [connectionManager] (connection + TargetManager
     * attach/detach events) and observed by [shellViewModel]. Phase 1 does not
     * move any diagnostic engine, store, Reset App cleanup or fault injection
     * into the kernel.
     */
    val diagnosticKernel: DiagnosticKernel by lazy {
        DiagnosticKernel()
    }

    /**
     * The application's connection manager, lazily initialized with a
     * real WebSocket transport and the shared [diagnosticKernel].
     */
    val connectionManager: ConnectionManager by lazy {
        val transport: CdpTransport = WebSocketCdpTransport()
        val cm = ConnectionManager.create(transport, DispatcherConfig(), kernel = diagnosticKernel)
        cm.ensureInitialized()
        cm
    }

    /**
     * The DOM inspector (Phase 2). Shares the connection manager's
     * [com.coppermux.cutools.core.transport.CommandDispatcher] and
     * [com.coppermux.cutools.core.transport.CdpEventRouter], so it never opens a
     * second Chrome connection.
     */
    val domInspector: DomInspector by lazy {
        val cm = connectionManager
        DomInspector(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The CSS inspector (Phase 3). Shares the connection manager's
     * [com.coppermux.cutools.core.transport.CommandDispatcher], observes the
     * [domInspector] selection so CSS inspection is always coherent with the
     * selected DOM node, and listens for stylesheet events so it never opens a
     * second Chrome connection. CSS inspection requires DOM inspection: it
     * inspects exactly the node selected in the Elements inspector.
     */
    val cssInspector: CssInspector by lazy {
        val cm = connectionManager
        CssInspector(
            dispatcher = cm.dispatcher,
            eventRouter = cm.eventRouter,
            domInspector = domInspector,
        )
    }

    /**
     * The Execution engine (SPEC 6B / Phase 4B). Dynamic provider prevents
     * dispatcher orphanage on reconnect.
     */
    val executionEngine: ExecutionEngine by lazy {
        val cm = connectionManager
        ExecutionEngine(
            dispatcher = cm.dispatcher,
            dispatcherProvider = { cm.dispatcher },
        )
    }

    /**
     * The Console inspector (Phase 4A/4B/4C/4D). Dynamic providers prevent
     * dispatcher and event router orphanage on reconnect.
     */
    val consoleInspector: ConsoleInspector by lazy {
        val cm = connectionManager
        ConsoleInspector(
            dispatcher = cm.dispatcher,
            eventRouter = cm.eventRouter,
            domInspector = domInspector,
            executionEngine = executionEngine,
            dispatcherProvider = { cm.dispatcher },
            eventRouterProvider = { cm.eventRouter },
        )
    }

    /**
     * The Network engine (Phase 5A/5B). Shares the connection manager's
     * dispatcher/event router, so it never opens a second Chrome connection.
     */
    val networkEngine: NetworkEngine by lazy {
        val cm = connectionManager
        NetworkEngine(
            dispatcher = cm.dispatcher,
            eventRouter = cm.eventRouter,
            dashboardEventStream = dashboardEventStream,
        )
    }

    /**
     * The Fetch interception engine (Phase 5C) with its shared rule manager.
     * The rule manager is intentionally shared so the Phase 5D rule editor and
     * the engine resolve the exact same authored rules.
     */
    val fetchInterceptionEngine: FetchInterceptionEngine by lazy {
        val cm = connectionManager
        FetchInterceptionEngine(
            dispatcher = cm.dispatcher,
            eventRouter = cm.eventRouter,
            ruleManager = MockResponseRuleManager(),
            faultInterceptorEngine = faultInterceptorEngine,
        )
    }

    /**
     * The Tracing engine (Phase 7A). Shares the connection manager's
     * dispatcher/event router, so it never opens a second Chrome connection.
     */
    val tracingEngine: TracingEngine by lazy {
        val cm = connectionManager
        TracingEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The CPU Profiler engine (Phase 7B). Shares the connection manager's
     * dispatcher/event router, so it never opens a second Chrome connection.
     */
    val cpuProfilerEngine: CpuProfilerEngine by lazy {
        val cm = connectionManager
        CpuProfilerEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The Heap Profiler engine (Phase 7C). Shares the connection manager's
     * dispatcher/event router, so it never opens a second Chrome connection.
     */
    val heapProfilerEngine: HeapProfilerEngine by lazy {
        val cm = connectionManager
        HeapProfilerEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The unified Performance tool ViewModel (Phase 7D), orchestrating the
     * Tracing, CPU Profiler, and Heap Profiler engines into one thread-safe
     * snapshot for the Performance tool UI.
     */
    val performanceToolViewModel: PerformanceToolViewModel by lazy {
        PerformanceToolViewModel(
            tracing = tracingEngine,
            cpu = cpuProfilerEngine,
            heap = heapProfilerEngine,
        )
    }

    /**
     * The Security engine (Phase 8A). Shares the connection manager's
     * dispatcher/event router, so it never opens a second Chrome connection.
     */
    val securityEngine: SecurityEngine by lazy {
        val cm = connectionManager
        SecurityEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The Target domain engine (Phase 12). Shares the connection manager's
     * dispatcher/event router, so it never opens a second Chrome connection.
     */
    val targetEngine: TargetEngine by lazy {
        val cm = connectionManager
        TargetEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The Storage quota engine (Phase 8B). Shares the connection manager's
     * dispatcher/event router, so it never opens a second Chrome connection.
     */
    val storageEngine: StorageEngine by lazy {
        val cm = connectionManager
        StorageEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The IndexedDB introspection engine (Phase 8C). Shares the connection
     * manager's dispatcher, so it never opens a second Chrome connection.
     */
    val indexedDbEngine: IndexedDbEngine by lazy {
        IndexedDbEngine(dispatcher = connectionManager.dispatcher)
    }

    /**
     * The CacheStorage introspection engine (Phase 8C). Shares the connection
     * manager's dispatcher, so it never opens a second Chrome connection.
     */
    val cacheStorageEngine: CacheStorageEngine by lazy {
        CacheStorageEngine(dispatcher = connectionManager.dispatcher)
    }

    /**
     * The unified Security & Storage tool ViewModel (Phase 8D), orchestrating
     * the Security, Storage, IndexedDB, and CacheStorage engines into one
     * thread-safe snapshot for the Security & Storage tool UI.
     */
    val securityStorageToolViewModel: SecurityStorageToolViewModel by lazy {
        SecurityStorageToolViewModel(
            security = securityEngine,
            storage = storageEngine,
            indexedDb = indexedDbEngine,
            cacheStorage = cacheStorageEngine,
        )
    }

    /**
     * The Accessibility engine (Phase 9A). Shares the connection manager's
     * dispatcher, so it never opens a second Chrome connection.
     */
    val accessibilityEngine: AccessibilityEngine by lazy {
        AccessibilityEngine(dispatcher = connectionManager.dispatcher)
    }

    /**
     * The Emulation engine (Phase 9B). Shares the connection manager's
     * dispatcher, so it never opens a second Chrome connection.
     */
    val emulationEngine: EmulationEngine by lazy {
        EmulationEngine(dispatcher = connectionManager.dispatcher)
    }

    /**
     * The Accessibility ViewModel (Phase 9C), binding the accessibility engine
     * into a state flow for the accessibility tree UI.
     */
    val accessibilityViewModel: AccessibilityViewModel by lazy {
        AccessibilityViewModel(engine = accessibilityEngine)
    }

    /**
     * The Emulation ViewModel (Phase 9C), binding the emulation engine into a
     * state flow for the device metrics / network / touch / media UI.
     */
    val emulationViewModel: EmulationViewModel by lazy {
        EmulationViewModel(engine = emulationEngine)
    }

    /**
     * The Audits engine (Phase 10). Shares the connection manager's dispatcher
     * and event router, so it never opens a second Chrome connection.
     */
    val auditEngine: AuditEngine by lazy {
        val cm = connectionManager
        AuditEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The Performance engine (Phase 10). Shares the connection manager's
     * dispatcher and event router, so it never opens a second Chrome connection.
     */
    val performanceEngine: PerformanceEngine by lazy {
        val cm = connectionManager
        PerformanceEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The Memory & Heap Profiler engine (Phase 11A). Shares the connection
     * manager's dispatcher and event router, so it never opens a second Chrome
     * connection.
     */
    val memoryEngine: MemoryEngine by lazy {
        val cm = connectionManager
        MemoryEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)
    }

    /**
     * The Audits ViewModel (Phase 11B), binding the audit engine into a state
     * flow for the audits issue UI.
     */
    val auditViewModel: AuditViewModel by lazy {
        AuditViewModel(engine = auditEngine)
    }

    /**
     * The Performance ViewModel (Phase 11B), binding the performance engine into
     * a state flow for the performance metrics UI.
     */
    val performanceViewModel: PerformanceViewModel by lazy {
        PerformanceViewModel(engine = performanceEngine)
    }

    /**
     * The Memory ViewModel (Phase 11B), binding the memory engine into a state
     * flow for the memory profiler UI.
     */
    val memoryViewModel: MemoryViewModel by lazy {
        MemoryViewModel(engine = memoryEngine)
    }

    /**
     * The Target ViewModel (Phase 13), binding the target domain engine into a
     * state flow for the target discovery UI.
     */
    val targetViewModel: TargetViewModel by lazy {
        TargetViewModel(engine = targetEngine)
    }

    /**
     * The Security ViewModel (Phase 13), binding the security engine into a
     * state flow for the security inspection UI.
     */
    val securityViewModel: SecurityViewModel by lazy {
        SecurityViewModel(engine = securityEngine)
    }

    /**
     * The unified shell ViewModel (Phase 17/18/19), orchestrating the core UI
     * shell: connection status, fault/log counters, home workspace navigation,
     * the address-bar navigation axis, the persistent Java Console drawer, the
     * Phase 18 DevTools Dashboard feed fed by the embedded WebView
     * interceptor engine, and the Phase 19 fault-injection rules that drive
     * the dashboard's in-card fault badges and live interception.
     */
    /**
     * The native in-process CDP bridge for the embedded Android WebView.
     * Bridges WebSocketCdpTransport to the abstract DevTools UNIX socket.
     */
    val cdpBridge: AndroidCdpBridge by lazy {
        AndroidCdpBridge()
    }

    val database: CuToolsDatabase by lazy {
        Room.databaseBuilder(
            this,
            CuToolsDatabase::class.java,
            "cutools_database",
        )
        .addMigrations(CuToolsDatabase.MIGRATION_1_2)
        .build()
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(database.workspaceSessionDao())
    }

    val sessionNameGenerator: SessionNameGenerator by lazy {
        SessionNameGenerator()
    }

    val f12Preferences: F12ButtonPreferences by lazy {
        val prefs = getSharedPreferences(SharedPreferencesF12ButtonPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        SharedPreferencesF12ButtonPreferences(prefs)
    }

    val shellViewModel: ShellViewModel by lazy {
        ShellViewModel(
            connectionManager = connectionManager,
            consoleInspector = consoleInspector,
            executionEngine = executionEngine,
            dashboardEventStream = dashboardEventStream,
            faultInterceptorEngine = faultInterceptorEngine,
            cdpBridge = cdpBridge,
            networkEngine = networkEngine,
            webViewEngine = webViewEngine,
            sessionRepository = sessionRepository,
            sessionNameGenerator = sessionNameGenerator,
            f12Preferences = f12Preferences,
        )
    }

    /**
     * The centralized dashboard interceptor event stream (Phase 18). Captured
     * WebView network + console events are appended here and streamed to the
     * DevTools Dashboard via the shell ViewModel.
     */
    val dashboardEventStream: DashboardEventStream by lazy {
        DashboardEventStream(kernel = diagnosticKernel)
    }

    /**
     * Dashboard ViewModel providing reactive controls (record/pause) over
     * [dashboardEventStream].
     */
    val dashboardViewModel: DashboardViewModel by lazy {
        DashboardViewModel(dashboardEventStream)
    }

    /**
     * The shared fault-interceptor engine (Phase 19). This single instance is
     * shared between the [webViewEngine] (which evaluates its [com.coppermux.cutools.core.browser.FaultRule]s
     * against every outgoing request) and the [shellViewModel] (which mirrors
     * its active rules into the dashboard's in-card fault badges). Sharing the
     * instance guarantees the badge state and the live interception always
     * agree.
     */
    val faultInterceptorEngine: FaultInterceptorEngine by lazy {
        FaultInterceptorEngine()
    }

    /**
     * The embedded WebView interceptor engine (Phase 18/19). Configures a
     * WebView for standalone browsing + DevTools interception and routes
     * genuine network/console events into [dashboardEventStream]; Phase 19 the
     * engine also applies the shared [faultInterceptorEngine]'s latency /
     * status-override / connection-drop / mock-body rules in real time.
     */
    val webViewEngine: WebViewEngine by lazy {
        WebViewEngine(
            eventStream = dashboardEventStream,
            faultInterceptorEngine = faultInterceptorEngine,
            kernel = diagnosticKernel,
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            DiagnosticTracer.init(this)
        }
        // Complete the ConnectionManager initialization sequence before wiring
        // lifecycle observers, so targetManager is never accessed while still
        // uninitialized during startup / standalone mode.
        connectionManager.ensureInitialized()

        // Phase 2: Centralize Reset Ownership in DiagnosticKernel ResetCoordinator
        diagnosticKernel.resetCoordinator.bindSubsystems(
            dispatcher = connectionManager.dispatcher,
            targetManagerProvider = { connectionManager.targetManager },
            fetchEngine = fetchInterceptionEngine,
            faultEngine = faultInterceptorEngine,
            networkEngine = networkEngine,
            consoleInspector = consoleInspector,
            dashboardEventStream = dashboardEventStream,
            executionEngine = executionEngine,
            domInspector = domInspector,
            cssInspector = cssInspector,
            webViewEngine = webViewEngine,
        )

        wireInspectorLifecycle()
    }

    /**
     * Binds the tool inspectors/backend to the active session on target attach
     * and clears them on detach/connection loss.
     *
     * DiagnosticKernel Phase 1: [diagnosticKernel] has already processed each
     * attach/detach/clear before this listener runs (the kernel adapter is the
     * first TargetManager listener). The auto-attach heuristics and inspector
     * bind/detach below are retained as operational responsibilities and
     * intentionally unchanged in this phase.
     */
    private fun wireInspectorLifecycle() {
        // Null-safe accessor: returns null before initialization, so standalone
        // mode / startup never crashes the app process.
        connectionManager.targetManager?.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: com.coppermux.cutools.core.target.TargetInfo) {
                val tm = connectionManager.targetManager ?: return
                if (target.isPage) {
                    val best = tm.findBestPageTarget(webViewEngine.currentDocumentUrl)
                    if (best != null && (tm.activeSession == null || tm.selectedTarget?.isWindowAttached == false)) {
                        tm.selectTarget(best.targetId)
                        tm.attachToSelectedTarget { }
                    }
                }
            }
            override fun onTargetUpdated(target: com.coppermux.cutools.core.target.TargetInfo) {
                val tm = connectionManager.targetManager ?: return
                if (target.isPage) {
                    val best = tm.findBestPageTarget(webViewEngine.currentDocumentUrl)
                    if (best != null && (tm.activeSession == null || (best.targetId != tm.selectedTarget?.targetId && tm.selectedTarget?.isWindowAttached == false))) {
                        tm.selectTarget(best.targetId)
                        tm.attachToSelectedTarget { }
                    }
                }
            }
            override fun onTargetDestroyed(targetId: String) {
                val tm = connectionManager.targetManager ?: return
                if (tm.activeSession == null) {
                    val remainingPage = tm.findBestPageTarget(webViewEngine.currentDocumentUrl)
                    if (remainingPage != null && remainingPage.targetId != targetId) {
                        tm.selectTarget(remainingPage.targetId)
                        tm.attachToSelectedTarget { }
                    } else {
                        tm.discoverTargets { }
                    }
                }
            }
            override fun onTargetSelected(target: com.coppermux.cutools.core.target.TargetInfo) {}
            override fun onTargetsUpdated(targets: List<com.coppermux.cutools.core.target.TargetInfo>) {
                val tm = connectionManager.targetManager ?: return
                val best = tm.findBestPageTarget(webViewEngine.currentDocumentUrl)
                if (best != null) {
                    if (tm.activeSession == null || (tm.selectedTarget?.isWindowAttached == false && best.isWindowAttached == true)) {
                        tm.selectTarget(best.targetId)
                        tm.attachToSelectedTarget { }
                    }
                }
            }
            override fun onAttached(session: com.coppermux.cutools.core.target.SessionInfo) {
                logTrace(TAG, "onAttached targetId=${session.targetId} sessionId=${session.sessionId}")
                runCatching { domInspector.bindSession(session) }.onFailure { Log.w(TAG, "domInspector.bindSession failed", it) }
                runCatching { executionEngine.bindSession(session) }.onFailure { Log.w(TAG, "executionEngine.bindSession failed", it) }
                runCatching { consoleInspector.bindSession(session) }.onFailure { Log.w(TAG, "consoleInspector.bindSession failed", it) }
                runCatching { networkEngine.bindSession(session) }.onFailure { Log.w(TAG, "networkEngine.bindSession failed", it) }
                if (diagnosticKernel.isNetworkDropped) {
                    runCatching { networkEngine.setNetworkDrop(true) }.onFailure { Log.w(TAG, "networkEngine.setNetworkDrop failed", it) }
                }
                runCatching { fetchInterceptionEngine.bindSession(session) }.onFailure { Log.w(TAG, "fetchInterceptionEngine.bindSession failed", it) }
                runCatching { performanceToolViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "performanceToolViewModel.attachTarget failed", it) }
                runCatching { securityStorageToolViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "securityStorageToolViewModel.attachTarget failed", it) }
                runCatching { accessibilityViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "accessibilityViewModel.attachTarget failed", it) }
                runCatching { emulationViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "emulationViewModel.attachTarget failed", it) }
                runCatching { auditViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "auditViewModel.attachTarget failed", it) }
                runCatching { performanceViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "performanceViewModel.attachTarget failed", it) }
                runCatching { memoryViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "memoryViewModel.attachTarget failed", it) }
                runCatching { targetViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "targetViewModel.attachTarget failed", it) }
                runCatching { securityViewModel.attachTarget(session) }.onFailure { Log.w(TAG, "securityViewModel.attachTarget failed", it) }
            }
            override fun onDetached(targetId: String) {
                val activeTargetId = connectionManager.targetManager?.activeSession?.targetId
                if (activeTargetId != null && activeTargetId != targetId) {
                    return
                }
                logTrace(TAG, "onDetached targetId=$targetId")
                runCatching { domInspector.detach() }.onFailure { Log.w(TAG, "domInspector.detach failed", it) }
                runCatching { executionEngine.detach() }.onFailure { Log.w(TAG, "executionEngine.detach failed", it) }
                runCatching { consoleInspector.detach() }.onFailure { Log.w(TAG, "consoleInspector.detach failed", it) }
                runCatching { networkEngine.detach() }.onFailure { Log.w(TAG, "networkEngine.detach failed", it) }
                runCatching { fetchInterceptionEngine.detach() }.onFailure { Log.w(TAG, "fetchInterceptionEngine.detach failed", it) }
                runCatching { performanceToolViewModel.detachTarget() }.onFailure { Log.w(TAG, "performanceToolViewModel.detachTarget failed", it) }
                runCatching { securityStorageToolViewModel.detachTarget() }.onFailure { Log.w(TAG, "securityStorageToolViewModel.detachTarget failed", it) }
                runCatching { accessibilityViewModel.detachTarget() }.onFailure { Log.w(TAG, "accessibilityViewModel.detachTarget failed", it) }
                runCatching { emulationViewModel.detachTarget() }.onFailure { Log.w(TAG, "emulationViewModel.detachTarget failed", it) }
                runCatching { auditViewModel.detachTarget() }.onFailure { Log.w(TAG, "auditViewModel.detachTarget failed", it) }
                runCatching { performanceViewModel.detachTarget() }.onFailure { Log.w(TAG, "performanceViewModel.detachTarget failed", it) }
                runCatching { memoryViewModel.detachTarget() }.onFailure { Log.w(TAG, "memoryViewModel.detachTarget failed", it) }
                runCatching { targetViewModel.detachTarget() }.onFailure { Log.w(TAG, "targetViewModel.detachTarget failed", it) }
                runCatching { securityViewModel.detachTarget() }.onFailure { Log.w(TAG, "securityViewModel.detachTarget failed", it) }
            }
            override fun onCleared() {
                logTrace(TAG, "onCleared")
                runCatching { domInspector.detach() }.onFailure { Log.w(TAG, "domInspector.detach failed", it) }
                runCatching { executionEngine.detach() }.onFailure { Log.w(TAG, "executionEngine.detach failed", it) }
                runCatching { consoleInspector.detach() }.onFailure { Log.w(TAG, "consoleInspector.detach failed", it) }
                runCatching { networkEngine.detach() }.onFailure { Log.w(TAG, "networkEngine.detach failed", it) }
                runCatching { fetchInterceptionEngine.detach() }.onFailure { Log.w(TAG, "fetchInterceptionEngine.detach failed", it) }
                runCatching { performanceToolViewModel.detachTarget() }.onFailure { Log.w(TAG, "performanceToolViewModel.detachTarget failed", it) }
                runCatching { securityStorageToolViewModel.detachTarget() }.onFailure { Log.w(TAG, "securityStorageToolViewModel.detachTarget failed", it) }
                runCatching { accessibilityViewModel.detachTarget() }.onFailure { Log.w(TAG, "accessibilityViewModel.detachTarget failed", it) }
                runCatching { emulationViewModel.detachTarget() }.onFailure { Log.w(TAG, "emulationViewModel.detachTarget failed", it) }
                runCatching { auditViewModel.detachTarget() }.onFailure { Log.w(TAG, "auditViewModel.detachTarget failed", it) }
                runCatching { performanceViewModel.detachTarget() }.onFailure { Log.w(TAG, "performanceViewModel.detachTarget failed", it) }
                runCatching { memoryViewModel.detachTarget() }.onFailure { Log.w(TAG, "memoryViewModel.detachTarget failed", it) }
                runCatching { targetViewModel.detachTarget() }.onFailure { Log.w(TAG, "targetViewModel.detachTarget failed", it) }
                runCatching { securityViewModel.detachTarget() }.onFailure { Log.w(TAG, "securityViewModel.detachTarget failed", it) }
            }
            override fun onError(error: Throwable) {}
        })

        connectionManager.addStateListener(object : ConnectionManager.StateListener {
            override fun onStateChanged(state: ConnectionState, error: Throwable?) {
                // On connection loss, clear tool state so no stale data remains.
                if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                    logTrace(TAG, "connection lost state=$state error=${error?.message}")
                    if (domInspector.session != null) {
                        domInspector.onConnectionLost()
                    }
                    if (executionEngine.session != null) {
                        executionEngine.onConnectionLost()
                    }
                    if (consoleInspector.session != null) {
                        consoleInspector.onConnectionLost()
                    }
                    if (networkEngine.session != null) {
                        networkEngine.onConnectionLost()
                    }
                    if (fetchInterceptionEngine.session != null) {
                        fetchInterceptionEngine.onConnectionLost()
                    }
                    if (performanceToolViewModel.isAttached) {
                        performanceToolViewModel.onConnectionLost()
                    }
                    if (securityStorageToolViewModel.isAttached) {
                        securityStorageToolViewModel.onConnectionLost()
                    }
                    if (accessibilityViewModel.isAttached) {
                        accessibilityViewModel.onConnectionLost()
                    }
                    if (emulationViewModel.isAttached) {
                        emulationViewModel.onConnectionLost()
                    }
                    if (auditViewModel.isAttached) {
                        auditViewModel.onConnectionLost()
                    }
                    if (performanceViewModel.isAttached) {
                        performanceViewModel.onConnectionLost()
                    }
                    if (memoryViewModel.isAttached) {
                        memoryViewModel.onConnectionLost()
                    }
                    if (targetViewModel.isAttached) {
                        targetViewModel.onConnectionLost()
                    }
                    if (securityViewModel.isAttached) {
                        securityViewModel.onConnectionLost()
                    }
                }
            }
        })
    }
}
