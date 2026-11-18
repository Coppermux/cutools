package com.coppermux.cutools.ui.shell

import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.browser.DashboardEvent
import com.coppermux.cutools.core.browser.DashboardEventCategory
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.DashboardFilter
import com.coppermux.cutools.core.browser.DashboardSeverity
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.connection.ConnectionState
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.data.local.WorkspaceSessionDao
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceCategory
import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.data.repository.SessionRepository
import com.coppermux.cutools.data.repository.SessionRepositoryImpl
import com.coppermux.cutools.domain.SessionNameGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var connectionManager: ConnectionManager
    private lateinit var consoleInspector: ConsoleInspector
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var domInspector: DomInspector
    private lateinit var dashboardEventStream: DashboardEventStream
    private lateinit var faultInterceptorEngine: FaultInterceptorEngine
    private lateinit var cdpBridge: AndroidCdpBridge
    private lateinit var networkEngine: NetworkEngine

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        connectionManager = ConnectionManager.create(transport, DispatcherConfig(defaultTimeoutMs = 1000))
        connectionManager.ensureInitialized()
        router = connectionManager.eventRouter
        dispatcher = connectionManager.dispatcher
        domInspector = DomInspector(dispatcher, router)
        executionEngine = ExecutionEngine(dispatcher)
        consoleInspector = ConsoleInspector(dispatcher, router, domInspector, executionEngine)
        dashboardEventStream = DashboardEventStream()
        faultInterceptorEngine = FaultInterceptorEngine()
        cdpBridge = mock()
        networkEngine = NetworkEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            dashboardEventStream = dashboardEventStream,
        )
    }

    private fun createViewModel(
        bridge: AndroidCdpBridge? = cdpBridge,
        repository: SessionRepository? = null,
        nameGenerator: SessionNameGenerator? = null,
        engine: WebViewEngine? = null,
        f12Preferences: F12ButtonPreferences = InMemoryF12ButtonPreferences(),
    ): ShellViewModel {
        return ShellViewModel(
            connectionManager = connectionManager,
            consoleInspector = consoleInspector,
            executionEngine = executionEngine,
            dashboardEventStream = dashboardEventStream,
            faultInterceptorEngine = faultInterceptorEngine,
            cdpBridge = bridge,
            coroutineScope = testScope,
            networkEngine = networkEngine,
            webViewEngine = engine,
            sessionRepository = repository,
            sessionNameGenerator = nameGenerator,
            f12Preferences = f12Preferences,
        )
    }

    private fun attachTarget(
        targetId: String = "target-1",
        sessionId: String = "session-1",
        type: String = "page",
    ) {
        router.handleEvent(
            Event(
                method = "Target.attachedToTarget",
                params = mapOf(
                    "sessionId" to sessionId,
                    "targetInfo" to mapOf("targetId" to targetId, "type" to type),
                    "waitingForDebugger" to false,
                ),
            )
        )
    }

    @Test
    fun navigate_whenAlreadyAttached_proceedsImmediately() {
        // Transition connectionManager to ATTACHED
        connectionManager.connect("ws://test") { }
        attachTarget()
        assertEquals(ConnectionState.ATTACHED, connectionManager.connectionState)

        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        assertEquals("https://example.com", vm.state.value.currentUrl)
        assertFalse(vm.state.value.workspaceVisible)
    }

    @Test
    fun navigate_whenBridgeFailsToInitialize_abortsNavigationWithDiagnostic() = testScope.runTest {
        whenever(cdpBridge.awaitReady(any())).thenReturn(false)

        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        // Before coroutine runs, currentUrl is null awaiting attachment
        assertNull(vm.state.value.currentUrl)

        testScheduler.advanceUntilIdle()

        // Navigation must not have proceeded
        assertNull(vm.state.value.currentUrl)
        val msg = vm.state.value.transientMessage
        assertNotNull(msg)
        assertTrue("Expected failure diagnostic, got: $msg", msg!!.contains("failed to initialize") || msg.contains("navigation aborted"))
    }

    @Test
    fun navigate_whenAttachmentTimesOut_abortsNavigationWithDiagnostic() = testScope.runTest {
        whenever(cdpBridge.awaitReady(any())).thenReturn(true)
        whenever(cdpBridge.debugUrl).thenReturn("ws://127.0.0.1:9222/devtools/browser")

        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        assertNull(vm.state.value.currentUrl)

        // Advance past bridge readiness but before 2000ms timeout
        testScheduler.advanceTimeBy(500)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connectionManager.connectionState)
        assertNull("Should not proceed before attachment even when CONNECTED", vm.state.value.currentUrl)

        // Advance past the 2000ms timeout
        testScheduler.advanceTimeBy(2000)
        testScheduler.advanceUntilIdle()

        // Navigation must be aborted
        assertNull(vm.state.value.currentUrl)
        val msg = vm.state.value.transientMessage
        assertNotNull(msg)
        assertTrue("Expected timeout diagnostic, got: $msg", msg!!.contains("attachment timed out") && msg.contains("navigation aborted"))
    }

    @Test
    fun navigate_whenConnectionReportsError_abortsNavigationWithDiagnostic() = testScope.runTest {
        whenever(cdpBridge.awaitReady(any())).thenReturn(true)
        whenever(cdpBridge.debugUrl).thenReturn("ws://127.0.0.1:9222/devtools/browser")

        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        // Advance to connect() call and enter waiting loop
        testScheduler.advanceTimeBy(100)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connectionManager.connectionState)

        // Simulate connection error and verify ConnectionManager transitions to ERROR
        transport.deliverError(TransportException("Connection refused"))
        assertEquals(ConnectionState.ERROR, connectionManager.connectionState)

        // Advance past loop poll interval so loop observes ERROR immediately without waiting for timeout
        testScheduler.advanceTimeBy(50)
        runCurrent()

        // Navigation must be aborted
        assertNull(vm.state.value.currentUrl)
        assertEquals(
            "CDP connection error: Connection refused; navigation aborted.",
            vm.state.value.transientMessage
        )
    }

    @Test
    fun navigate_whenBridgeThrowsException_abortsNavigationWithDiagnostic() = testScope.runTest {
        doThrow(RuntimeException("Socket access denied")).whenever(cdpBridge).start()

        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        testScheduler.advanceUntilIdle()

        assertNull(vm.state.value.currentUrl)
        val msg = vm.state.value.transientMessage
        assertNotNull(msg)
        assertTrue("Expected bridge error diagnostic, got: $msg", msg!!.contains("CDP bridge error") && msg.contains("navigation aborted"))
    }

    @Test
    fun navigate_whenAttachmentSucceeds_proceedsWithNavigation() = testScope.runTest {
        whenever(cdpBridge.awaitReady(any())).thenReturn(true)
        whenever(cdpBridge.debugUrl).thenReturn("ws://127.0.0.1:9222/devtools/browser")

        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        assertNull(vm.state.value.currentUrl)

        testScheduler.advanceTimeBy(100)
        runCurrent()

        // Transition to ATTACHED
        attachTarget("target-1", "session-1")
        assertEquals(ConnectionState.ATTACHED, connectionManager.connectionState)

        testScheduler.advanceTimeBy(100)
        runCurrent()

        assertEquals("https://example.com", vm.state.value.currentUrl)
    }

    @Test
    fun onConsoleSubmit_preservesSessionNullGuard() {
        val vm = createViewModel()
        assertNull(consoleInspector.session)

        vm.onConsoleSubmit("1 + 1")

        assertEquals("Attach a target before evaluating in the Java Console.", vm.state.value.transientMessage)
    }

    @Test
    fun resetApp_restoresDefaultNavigationAndTransientOverlays() {
        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.setPage(ShellPage.DASHBOARD)
        vm.setMenuOpen(true)
        vm.showAbout()

        vm.resetApp()

        val state = vm.state.value
        assertEquals(ShellPage.BROWSER, state.currentPage)
        assertEquals("", state.addressQuery)
        assertNull(state.currentUrl)
        assertTrue(state.workspaceVisible)
        assertFalse(state.menuOpen)
        assertFalse(state.drawerOpen)
        assertFalse(state.showAbout)
        assertNull(state.transientMessage)
    }

    @Test
    fun resetApp_preservesThemeAndSavedProjects() {
        val vm = createViewModel()
        assertFalse(vm.state.value.useDarkTheme)
        vm.toggleTheme()
        assertTrue(vm.state.value.useDarkTheme)

        vm.resetApp()

        assertTrue("Dark theme preference must be preserved after reset", vm.state.value.useDarkTheme)
        assertEquals(emptyList<WorkspaceProject>(), vm.state.value.savedProjects)
    }

    @Test
    fun resetApp_clearsDashboardConsoleAndFaultState() {
        val vm = createViewModel()

        dashboardEventStream.append(
            DashboardEvent(
                category = DashboardEventCategory.NETWORK,
                severity = DashboardSeverity.ERROR,
                kind = "HTTP 500",
                url = "https://example.com/api",
            )
        )
        dashboardEventStream.setFilter(DashboardFilter.ERRORS)
        dashboardEventStream.setSearchQuery("api")
        dashboardEventStream.setRecording(false)

        consoleInspector.setInput("console.log('test')")
        consoleInspector.drain()
        vm.applyFaultRule(
            FaultRule(
                id = "rule-1",
                urlPattern = "*api*",
                statusCode = 500,
            )
        )

        assertEquals(1, dashboardEventStream.state.events.size)
        assertEquals("console.log('test')", consoleInspector.state.input)
        assertEquals(1, faultInterceptorEngine.all().size)

        vm.resetApp()

        assertTrue(dashboardEventStream.state.events.isEmpty())
        assertEquals(DashboardFilter.ALL, dashboardEventStream.state.filter)
        assertEquals("", dashboardEventStream.state.searchQuery)
        assertTrue(dashboardEventStream.state.isRecording)

        consoleInspector.drain()
        assertTrue(consoleInspector.state.entries.isEmpty())
        assertEquals("", consoleInspector.state.input)

        assertTrue(faultInterceptorEngine.all().isEmpty())
        networkEngine.drain()
        assertTrue(networkEngine.state.requests.isEmpty())

        val state = vm.state.value
        assertTrue(state.dashboardState.events.isEmpty())
        assertTrue(state.activeFaultRules.isEmpty())
        assertEquals(0, state.faultCount)
        assertEquals(0, state.logCount)
        assertTrue(state.consoleState.entries.isEmpty())
        assertEquals("", state.consoleState.input)
        assertTrue(state.isRecording)
    }

    @Test
    fun resetApp_whenNetworkEngineIsNull_resetsDashboardStreamDirectly() {
        dashboardEventStream.append(
            DashboardEvent(
                category = DashboardEventCategory.NETWORK,
                severity = DashboardSeverity.ERROR,
                kind = "HTTP 500",
                url = "https://example.com/api",
            )
        )
        dashboardEventStream.setFilter(DashboardFilter.ERRORS)
        dashboardEventStream.setSearchQuery("api")
        dashboardEventStream.setRecording(false)

        val vmWithoutNetwork = ShellViewModel(
            connectionManager = connectionManager,
            consoleInspector = consoleInspector,
            executionEngine = executionEngine,
            dashboardEventStream = dashboardEventStream,
            faultInterceptorEngine = faultInterceptorEngine,
            cdpBridge = cdpBridge,
            coroutineScope = testScope,
            networkEngine = null,
        )

        vmWithoutNetwork.resetApp()

        assertTrue(dashboardEventStream.state.events.isEmpty())
        assertEquals(DashboardFilter.ALL, dashboardEventStream.state.filter)
        assertEquals("", dashboardEventStream.state.searchQuery)
        assertTrue(dashboardEventStream.state.isRecording)
    }

    @Test
    fun clearLogs_clearsFeedAndConsoleAndNetwork_whilePreservingNavigationUrlAndFaultRules() {
        connectionManager.connect("ws://test") { }
        attachTarget()
        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        dashboardEventStream.append(
            DashboardEvent(
                category = DashboardEventCategory.NETWORK,
                severity = DashboardSeverity.ERROR,
                kind = "HTTP 500",
                url = "https://example.com/api",
            )
        )
        dashboardEventStream.setFilter(DashboardFilter.ERRORS)
        dashboardEventStream.setSearchQuery("api")

        consoleInspector.setInput("console.log('test')")
        consoleInspector.drain()

        vm.applyFaultRule(
            FaultRule(
                id = "rule-1",
                urlPattern = "*api*",
                statusCode = 500,
            )
        )

        val beforeEpoch = connectionManager.kernel.currentEpoch
        assertEquals(1, dashboardEventStream.state.events.size)
        assertEquals("console.log('test')", consoleInspector.state.input)
        assertEquals(1, faultInterceptorEngine.all().size)
        assertEquals("https://example.com", vm.state.value.currentUrl)
        assertFalse(vm.state.value.workspaceVisible)

        vm.clearLogs()

        // Diagnostic streams and stores are cleared
        assertTrue(dashboardEventStream.state.events.isEmpty())
        consoleInspector.drain()
        assertTrue(consoleInspector.state.entries.isEmpty())
        assertEquals("", consoleInspector.state.input)
        networkEngine.drain()
        assertTrue(networkEngine.state.requests.isEmpty())

        val state = vm.state.value
        assertTrue(state.dashboardState.events.isEmpty())
        assertEquals(0, state.faultCount)
        assertEquals(0, state.logCount)
        assertTrue(state.consoleState.entries.isEmpty())
        assertEquals("", state.consoleState.input)

        // Navigation, active URL, fault injection rules, and kernel epoch are PRESERVED
        assertEquals("https://example.com", state.currentUrl)
        assertEquals("https://example.com", state.addressQuery)
        assertFalse(state.workspaceVisible)
        assertEquals(1, state.activeFaultRules.size)
        assertEquals(beforeEpoch, connectionManager.kernel.currentEpoch)
    }

    @Test
    fun clearLogs_preservesDashboardFilterSearchAndRecordingStatus() {
        val vm = createViewModel()

        dashboardEventStream.append(
            DashboardEvent(
                category = DashboardEventCategory.CONSOLE,
                severity = DashboardSeverity.WARNING,
                kind = "warn",
                url = "https://example.com",
            )
        )
        dashboardEventStream.setFilter(DashboardFilter.WARNINGS)
        dashboardEventStream.setSearchQuery("warn")
        dashboardEventStream.setRecording(false)

        vm.clearLogs()

        assertTrue(dashboardEventStream.state.events.isEmpty())
        assertEquals(DashboardFilter.WARNINGS, dashboardEventStream.state.filter)
        assertEquals("warn", dashboardEventStream.state.searchQuery)
        assertFalse(dashboardEventStream.state.isRecording)
        assertEquals(DashboardFilter.WARNINGS, vm.state.value.dashboardState.filter)
        assertEquals("warn", vm.state.value.dashboardState.searchQuery)
        assertFalse(vm.state.value.isRecording)
    }

    @Test
    fun clearDashboardFeed_delegatesToClearLogs() {
        val vm = createViewModel()

        dashboardEventStream.append(
            DashboardEvent(
                category = DashboardEventCategory.NETWORK,
                severity = DashboardSeverity.ERROR,
                kind = "HTTP 404",
                url = "https://example.com/notfound",
            )
        )
        consoleInspector.setInput("test input")
        consoleInspector.drain()

        vm.clearDashboardFeed()

        assertTrue(dashboardEventStream.state.events.isEmpty())
        consoleInspector.drain()
        assertTrue(consoleInspector.state.entries.isEmpty())
        assertEquals("", consoleInspector.state.input)
        assertEquals(0, vm.state.value.logCount)
        assertEquals(0, vm.state.value.faultCount)
    }

    @Test
    fun dashboardClear_callsClearLogsWithoutBrowserReset_andPreservesActivePageAndEpoch() {
        connectionManager.connect("ws://test") { }
        attachTarget()
        val vm = createViewModel()
        vm.updateAddress("https://example.com")
        vm.navigate()

        dashboardEventStream.append(
            DashboardEvent(
                category = DashboardEventCategory.NETWORK,
                severity = DashboardSeverity.ERROR,
                kind = "HTTP 502",
                url = "https://example.com/api",
            )
        )
        dashboardEventStream.setFilter(DashboardFilter.ERRORS)
        dashboardEventStream.setSearchQuery("api")
        consoleInspector.setInput("console.log(42)")
        consoleInspector.drain()

        val epochBefore = connectionManager.kernel.currentEpoch
        assertEquals("https://example.com", vm.state.value.currentUrl)
        assertFalse(vm.state.value.workspaceVisible)

        // Trigger Dashboard Clear via clearLogs
        vm.clearLogs()

        // Stores are empty
        assertTrue(dashboardEventStream.state.events.isEmpty())
        consoleInspector.drain()
        assertTrue(consoleInspector.state.entries.isEmpty())
        assertEquals("", consoleInspector.state.input)
        networkEngine.drain()
        assertTrue(networkEngine.state.requests.isEmpty())
        assertEquals(0, vm.state.value.logCount)
        assertEquals(0, vm.state.value.faultCount)

        // Browser page, URL, navigation history, and kernel epoch are UNTOUCHED
        assertEquals("https://example.com", vm.state.value.currentUrl)
        assertEquals("https://example.com", vm.state.value.addressQuery)
        assertFalse(vm.state.value.workspaceVisible)
        assertEquals(epochBefore, connectionManager.kernel.currentEpoch)
        assertEquals(com.coppermux.cutools.core.kernel.KernelState.ATTACHED, connectionManager.kernel.snapshot.state)
        assertEquals(DashboardFilter.ERRORS, dashboardEventStream.state.filter)
        assertEquals("api", dashboardEventStream.state.searchQuery)
    }

    @Test
    fun resetApp_invokesBrowserResetAndSharedClearLogs_leavingAllStoresEmptyAndKernelAttached() {
        connectionManager.connect("ws://test") { }
        attachTarget()
        val vm = createViewModel()
        vm.updateAddress("https://example.com/dashboard")
        vm.navigate()

        dashboardEventStream.append(
            DashboardEvent(
                category = DashboardEventCategory.NETWORK,
                severity = DashboardSeverity.ERROR,
                kind = "HTTP 500",
                url = "https://example.com/dashboard",
            )
        )
        dashboardEventStream.setFilter(DashboardFilter.ERRORS)
        dashboardEventStream.setSearchQuery("dash")
        consoleInspector.setInput("window.location")
        consoleInspector.drain()

        val epochBefore = connectionManager.kernel.currentEpoch

        vm.resetApp()

        // 1. Browser is reset to default destination
        val state = vm.state.value
        assertEquals(ShellPage.BROWSER, state.currentPage)
        assertEquals("", state.addressQuery)
        assertNull(state.currentUrl)
        assertTrue(state.workspaceVisible)
        assertTrue(state.activeFaultRules.isEmpty())

        // 2. Shared clearLogs cleared all 3 stores and badge counts
        assertTrue(dashboardEventStream.state.events.isEmpty())
        consoleInspector.drain()
        assertTrue(consoleInspector.state.entries.isEmpty())
        assertEquals("", consoleInspector.state.input)
        networkEngine.drain()
        assertTrue(networkEngine.state.requests.isEmpty())
        assertEquals(0, state.logCount)
        assertEquals(0, state.faultCount)

        // 3. Kernel completed reset lifecycle and advanced epoch
        assertTrue(connectionManager.kernel.currentEpoch > epochBefore)
        assertEquals(com.coppermux.cutools.core.kernel.KernelState.ATTACHED, connectionManager.kernel.snapshot.state)
    }

    @Test
    fun repeatedResetApp_doesNotCrashOrLeaveKernelStuck() {
        connectionManager.connect("ws://test") { }
        attachTarget()
        val vm = createViewModel()

        for (i in 1..5) {
            dashboardEventStream.append(
                DashboardEvent(
                    category = DashboardEventCategory.CONSOLE,
                    severity = DashboardSeverity.WARNING,
                    kind = "log",
                    url = "https://example.com",
                )
            )
            vm.resetApp()
            assertEquals(com.coppermux.cutools.core.kernel.KernelState.ATTACHED, connectionManager.kernel.snapshot.state)
            assertTrue(dashboardEventStream.state.events.isEmpty())
            consoleInspector.drain()
            assertTrue(consoleInspector.state.entries.isEmpty())
        }
    }

    @Test
    fun noConsoleDrawerClearButtonRemains() {
        val clazz = Class.forName("com.coppermux.cutools.ui.shell.JavaConsoleDrawerKt")
        val expandedPanelMethods = clazz.declaredMethods.filter { it.name.startsWith("ExpandedConsolePanel") }
        assertTrue("ExpandedConsolePanel composable must exist", expandedPanelMethods.isNotEmpty())
        expandedPanelMethods.forEach { method ->
            assertFalse(
                "ExpandedConsolePanel must not have onClearLogs parameter in signature: ${method.toGenericString()}",
                method.toGenericString().contains("onClearLogs"),
            )
        }
    }

    @Test
    fun resetApp_preservesCdpConnectionAndBridge() {
        connectionManager.connect("ws://test") { }
        attachTarget()
        assertEquals(ConnectionState.ATTACHED, connectionManager.connectionState)

        val vm = createViewModel()
        assertEquals("ATTACHED", vm.state.value.statusLabel)

        vm.resetApp()

        assertEquals(ConnectionState.ATTACHED, connectionManager.connectionState)
        assertEquals("ATTACHED", vm.state.value.statusLabel)
        verify(cdpBridge, never()).stop()
    }

    @Test
    fun resetApp_followedByNavigationToGumtree_whenAlreadyAttached_preservesConnectionAndTargetsGumtree() {
        connectionManager.connect("ws://test") { }
        attachTarget("target-cnn", "session-cnn")
        assertEquals(ConnectionState.ATTACHED, connectionManager.connectionState)
        val vm = createViewModel()

        vm.updateAddress("https://www.cnn.com")
        vm.navigate()
        assertEquals("https://www.cnn.com", vm.state.value.currentUrl)
        assertFalse(vm.state.value.workspaceVisible)

        vm.resetApp()
        assertEquals(ConnectionState.ATTACHED, connectionManager.connectionState)
        assertTrue(vm.state.value.workspaceVisible)
        assertNull(vm.state.value.currentUrl)
        assertTrue(vm.state.value.dashboardState.events.isEmpty())
        assertTrue(vm.state.value.consoleState.entries.isEmpty())

        vm.updateAddress("https://www.gumtree.co.za")
        vm.navigate()

        assertEquals("https://www.gumtree.co.za", vm.state.value.currentUrl)
        assertFalse(vm.state.value.workspaceVisible)
        assertEquals(ConnectionState.ATTACHED, connectionManager.connectionState)
        assertEquals("ATTACHED", vm.state.value.statusLabel)
        verify(cdpBridge, never()).stop()
    }

    @Test
    fun navigateToUrl_whenActiveSessionAlreadyExists_doesNotRecreateTargetOrBridge() {
        connectionManager.connect("ws://test") { }
        attachTarget("target-1", "session-1")
        val vm = createViewModel()

        vm.updateAddress("https://www.example.com")
        vm.navigate()

        assertEquals("https://www.example.com", vm.state.value.currentUrl)
        assertEquals("session-1", connectionManager.targetManager?.activeSession?.sessionId)
        verify(cdpBridge, never()).start()
    }

    @Test
    fun onConsoleSubmit_whenSessionIsNull_preservesTypedInputAndDoesNotClear() {
        val vm = createViewModel()
        assertNull(consoleInspector.session)

        vm.onConsoleInput("location.href")
        consoleInspector.drain()
        assertEquals("location.href", consoleInspector.state.input)

        vm.onConsoleSubmit("location.href")

        assertEquals("Attach a target before evaluating in the Java Console.", vm.state.value.transientMessage)
        assertEquals("location.href", consoleInspector.state.input)
        assertTrue(consoleInspector.state.entries.isEmpty())
    }

    // ================================================================
    // D6 — Session alignment and usability validation
    // ================================================================

    @Test
    fun isSessionAlignedAndUsable_returnsTrue_whenAttachedToPageTarget() {
        connectionManager.connect("ws://test") { }
        attachTarget("target-page", "session-page", type = "page")
        val vm = createViewModel()
        assertTrue("Attached page target must be reported as aligned and usable", vm.isSessionAlignedAndUsable())
    }

    @Test
    fun isSessionAlignedAndUsable_returnsFalse_whenNotAttached() {
        val vm = createViewModel()
        assertFalse("Unattached connection must report isSessionAlignedAndUsable as false", vm.isSessionAlignedAndUsable())
    }

    @Test
    fun isSessionAlignedAndUsable_returnsFalse_whenAttachedToNonPageTarget() {
        connectionManager.connect("ws://test") { }
        attachTarget("target-worker", "session-worker", type = "service_worker")
        val vm = createViewModel()
        assertFalse("Non-page target (e.g. service_worker) must NOT be reported as usable page target", vm.isSessionAlignedAndUsable())
    }

    @Test
    fun isSessionAlignedAndUsable_returnsFalse_whenTargetDestroyed() {
        connectionManager.connect("ws://test") { }
        attachTarget("target-page", "session-page", type = "page")
        val vm = createViewModel()
        assertTrue(vm.isSessionAlignedAndUsable())

        router.handleEvent(
            Event(
                method = "Target.targetDestroyed",
                params = mapOf("targetId" to "target-page"),
            )
        )
        assertFalse("Destroyed target must not remain usable", vm.isSessionAlignedAndUsable())
    }

    @Test
    fun resetApp_followedByNavigationToSameUrl_incrementsNavigationIdAndSetsCurrentUrl() {
        connectionManager.connect("ws://test") { }
        attachTarget()
        val vm = createViewModel()
        vm.updateAddress("https://www.example.com")
        vm.navigate()
        assertEquals("https://www.example.com", vm.state.value.currentUrl)
        val navId1 = vm.state.value.navigationId
        assertTrue("Initial navigation must set positive navigationId", navId1 > 0)

        vm.resetApp()
        assertNull(vm.state.value.currentUrl)
        val navId2 = vm.state.value.navigationId
        assertTrue("Reset must advance navigationId", navId2 > navId1)

        vm.updateAddress("https://www.example.com")
        vm.navigate()
        assertEquals("https://www.example.com", vm.state.value.currentUrl)
        val navId3 = vm.state.value.navigationId
        assertTrue("Subsequent navigation to same URL must advance navigationId", navId3 > navId2)
    }

    @Test
    fun retrySameUrl_incrementsNavigationId() {
        connectionManager.connect("ws://test") { }
        attachTarget()
        val vm = createViewModel()
        vm.updateAddress("https://www.example.com")
        vm.navigate()
        val navId1 = vm.state.value.navigationId

        // User submits the same URL again (retry)
        vm.navigate()
        val navId2 = vm.state.value.navigationId
        assertTrue("Explicit retry of same URL must advance navigationId", navId2 > navId1)
    }

    // ================================================================
    // Phase 2: Manual Save Session & Workspaces UI tests
    // ================================================================

    @Test
    fun openSaveCategoryChoice_whenNoActiveUrl_doesNotOpenDialogAndShowsFeedback() {
        val vm = createViewModel()
        assertNull(vm.state.value.currentUrl)

        vm.openSaveCategoryChoice()

        assertFalse(vm.state.value.showSaveCategoryDialog)
        assertEquals("Cannot save workspace: no active web session.", vm.state.value.transientMessage)
    }

    @Test
    fun openSaveCategoryChoice_whenActiveUrlIsValid_opensDialog() {
        val dao = FakeShellSessionDao()
        val repo = SessionRepositoryImpl(dao)
        val vm = createViewModel(repository = repo)
        connectionManager.connect("ws://test") { }
        attachTarget()
        vm.updateAddress("https://www.example.com")
        vm.navigate()

        vm.openSaveCategoryChoice()

        assertTrue(vm.state.value.showSaveCategoryDialog)
    }

    @Test
    fun saveCurrentSession_belowCapacity_createsNewWorkspaceWithChosenCategory() = testScope.runTest {
        val dao = FakeShellSessionDao()
        val repo = SessionRepositoryImpl(dao)
        val vm = createViewModel(repository = repo, nameGenerator = SessionNameGenerator())
        connectionManager.connect("ws://test") { }
        attachTarget()
        vm.updateAddress("https://work.example.com")
        vm.navigate()

        vm.saveCurrentSession(WorkspaceCategory.WORK)
        testScope.runCurrent()

        val saved = repo.getAllSessions().first()
        assertEquals(1, saved.size)
        assertEquals(WorkspaceCategory.WORK, saved[0].category)
        assertEquals("https://work.example.com", saved[0].targetUrl)
        assertEquals("work.example.com", saved[0].domain)
        assertNotNull(vm.state.value.activeWorkspaceId)
        assertEquals(saved[0].id, vm.state.value.activeWorkspaceId)
        assertFalse(vm.state.value.showSaveCategoryDialog)
    }

    @Test
    fun saveCurrentSession_repeatedSaves_updatesExistingSessionWithoutDuplicateOrCapacityLoss() = testScope.runTest {
        val dao = FakeShellSessionDao()
        val repo = SessionRepositoryImpl(dao)
        val vm = createViewModel(repository = repo, nameGenerator = SessionNameGenerator())
        connectionManager.connect("ws://test") { }
        attachTarget()
        vm.updateAddress("https://initial.example.com")
        vm.navigate()

        // First save creates
        vm.saveCurrentSession(WorkspaceCategory.HOME)
        testScope.runCurrent()

        val saved1 = repo.getAllSessions().first()
        assertEquals(1, saved1.size)
        val firstId = saved1[0].id

        // Second save in same active session updates category and doesn't duplicate
        vm.saveCurrentSession(WorkspaceCategory.WORK)
        testScope.runCurrent()

        val saved2 = repo.getAllSessions().first()
        assertEquals(1, saved2.size)
        assertEquals(firstId, saved2[0].id)
        assertEquals(WorkspaceCategory.WORK, saved2[0].category)
    }

    @Test
    fun saveCurrentSession_atCapacity_rejectsNewSaveAndEmitsRequiredToast() = testScope.runTest {
        val dao = FakeShellSessionDao()
        val repo = SessionRepositoryImpl(dao)
        for (i in 1..9) {
            repo.saveSession(
                WorkspaceSession(
                    id = "session-$i",
                    targetUrl = "https://example$i.com",
                    domain = "example$i.com",
                    displayName = "Session $i",
                    lastActiveAt = i.toLong(),
                )
            )
        }
        val beforeRecords = repo.getAllSessions().first()
        assertEquals(9, beforeRecords.size)

        val vm = createViewModel(repository = repo, nameGenerator = SessionNameGenerator())
        connectionManager.connect("ws://test") { }
        attachTarget()
        vm.updateAddress("https://tenth.example.com")
        vm.navigate()

        vm.saveCurrentSession(WorkspaceCategory.WORK)
        testScope.runCurrent()

        val afterRecords = repo.getAllSessions().first()
        assertEquals(9, afterRecords.size)
        assertEquals(beforeRecords, afterRecords)
        assertEquals("Workspaces is full. Delete a workspace to free a slot.", vm.state.value.transientMessage)
    }

    @Test
    fun saveCurrentSession_atCapacity_updatesExistingSessionSuccessfully() = testScope.runTest {
        val dao = FakeShellSessionDao()
        val repo = SessionRepositoryImpl(dao)
        for (i in 1..9) {
            repo.saveSession(
                WorkspaceSession(
                    id = "session-$i",
                    targetUrl = "https://example$i.com",
                    domain = "example$i.com",
                    displayName = "Session $i",
                    lastActiveAt = i.toLong(),
                    category = WorkspaceCategory.HOME,
                )
            )
        }

        val vm = createViewModel(repository = repo, nameGenerator = SessionNameGenerator())
        connectionManager.connect("ws://test") { }
        attachTarget()

        // Load existing session 3
        val existingSession = repo.getSessionById("session-3")!!
        vm.loadWorkspace(existingSession)
        assertEquals("session-3", vm.state.value.activeWorkspaceId)

        // Save at capacity updates existing session
        vm.saveCurrentSession(WorkspaceCategory.WORK)
        testScope.runCurrent()

        val afterRecords = repo.getAllSessions().first()
        assertEquals(9, afterRecords.size)
        val updated3 = repo.getSessionById("session-3")!!
        assertEquals(WorkspaceCategory.WORK, updated3.category)
    }

    @Test
    fun loadWorkspace_tracksActiveWorkspaceId() {
        val vm = createViewModel(bridge = null)
        val session = WorkspaceSession(
            id = "sess-abc",
            targetUrl = "https://custom.example.com",
            displayName = "Custom Title",
        )

        vm.loadWorkspace(session)

        assertEquals("sess-abc", vm.state.value.activeWorkspaceId)
        assertEquals("https://custom.example.com", vm.state.value.currentUrl)
        assertFalse(vm.state.value.workspaceVisible)
    }

    @Test
    fun navigate_withAddressBar_clearsActiveWorkspaceId() {
        val vm = createViewModel(bridge = null)
        val session = WorkspaceSession(
            id = "sess-abc",
            targetUrl = "https://custom.example.com",
            displayName = "Custom Title",
        )
        vm.loadWorkspace(session)
        assertEquals("sess-abc", vm.state.value.activeWorkspaceId)

        // Navigating to a new URL typed in address bar resets activeWorkspaceId
        vm.updateAddress("https://new.example.com")
        vm.navigate()

        assertNull(vm.state.value.activeWorkspaceId)
    }

    @Test
    fun backToWorkspace_clearsActiveWorkspaceId_andDialog() {
        val vm = createViewModel(bridge = null)
        vm.loadWorkspace(WorkspaceSession(id = "s1", targetUrl = "https://example.com"))
        assertEquals("s1", vm.state.value.activeWorkspaceId)

        vm.backToWorkspace()

        assertNull(vm.state.value.activeWorkspaceId)
        assertTrue(vm.state.value.workspaceVisible)
        assertFalse(vm.state.value.showSaveCategoryDialog)
    }

    @Test
    fun navigate_doesNotAutomaticallyCreateOrSaveWorkspaceSession() = testScope.runTest {
        val dao = FakeShellSessionDao()
        val repo = SessionRepositoryImpl(dao)
        val vm = createViewModel(repository = repo, bridge = null)
        vm.updateAddress("https://www.example.com")
        vm.navigate()
        testScope.runCurrent()

        val saved = repo.getAllSessions().first()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun disconnect_doesNotAutomaticallyCreateOrSaveWorkspaceSession() = testScope.runTest {
        val dao = FakeShellSessionDao()
        val repo = SessionRepositoryImpl(dao)
        val vm = createViewModel(repository = repo, bridge = null)
        vm.updateAddress("https://www.example.com")
        vm.navigate()
        vm.disconnect()
        testScope.runCurrent()

        val saved = repo.getAllSessions().first()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun f12Button_initialState_loadsFromPreferences() {
        val prefs = InMemoryF12ButtonPreferences(visible = false, xRatio = 0.5f, yRatio = 0.6f)
        val vm = createViewModel(bridge = null, f12Preferences = prefs)

        assertFalse(vm.state.value.isF12ButtonVisible)
        assertEquals(0.5f, vm.state.value.f12PositionXRatio, 0.001f)
        assertEquals(0.6f, vm.state.value.f12PositionYRatio, 0.001f)
    }

    @Test
    fun f12Button_hide_updatesStateAndPreferences() {
        val prefs = InMemoryF12ButtonPreferences(visible = true)
        val vm = createViewModel(bridge = null, f12Preferences = prefs)
        assertTrue(vm.state.value.isF12ButtonVisible)

        vm.hideF12Button()

        assertFalse(vm.state.value.isF12ButtonVisible)
        assertFalse(prefs.isVisible())
    }

    @Test
    fun f12Button_restore_updatesStateAndPreferences() {
        val prefs = InMemoryF12ButtonPreferences(visible = false)
        val vm = createViewModel(bridge = null, f12Preferences = prefs)
        assertFalse(vm.state.value.isF12ButtonVisible)

        vm.restoreF12Button()

        assertTrue(vm.state.value.isF12ButtonVisible)
        assertTrue(prefs.isVisible())
    }

    @Test
    fun f12Button_updatePosition_persistsRatios() {
        val prefs = InMemoryF12ButtonPreferences()
        val vm = createViewModel(bridge = null, f12Preferences = prefs)

        vm.updateF12Position(0.8f, 0.7f)

        assertEquals(0.8f, vm.state.value.f12PositionXRatio, 0.001f)
        assertEquals(0.7f, vm.state.value.f12PositionYRatio, 0.001f)
        assertEquals(0.8f, prefs.getPosition().first, 0.001f)
        assertEquals(0.7f, prefs.getPosition().second, 0.001f)
    }

    @Test
    fun onDocumentUrlCommitted_synchronizesCurrentUrlAndAddressQueryAfterRedirect() {
        val vm = createViewModel(bridge = null)
        vm.updateAddress("wikipedia.org")
        vm.navigate()

        // Initial navigation
        assertEquals("https://wikipedia.org", vm.state.value.currentUrl)
        assertEquals("https://wikipedia.org", vm.state.value.addressQuery)

        // Navigation commits and redirects to committed URL
        vm.onDocumentUrlCommitted("https://www.wikipedia.org/")

        assertEquals("https://www.wikipedia.org/", vm.state.value.currentUrl)
        assertEquals("https://www.wikipedia.org/", vm.state.value.addressQuery)
    }

    @Test
    fun getAuthoritativeUrl_prefersCommittedDocumentUrlFromWebViewEngine() {
        val eventStream = com.coppermux.cutools.core.browser.DashboardEventStream()
        val faultEngine = com.coppermux.cutools.core.browser.FaultInterceptorEngine()
        val webEngine = com.coppermux.cutools.core.browser.WebViewEngine(eventStream, faultEngine)

        val vm = createViewModel(bridge = null, engine = webEngine)
        vm.updateAddress("https://wikipedia.org")
        vm.navigate()

        // Simulate committed URL event in WebViewEngine
        webEngine.interceptorClient.onPageFinished(null, "https://www.wikipedia.org/")

        assertEquals("https://www.wikipedia.org/", vm.getAuthoritativeUrl())
        assertEquals("https://www.wikipedia.org/", vm.state.value.currentUrl)
    }

    @Test
    fun setNetworkDrop_withoutActiveCdpSession_activatesNativeDropDirectly() {
        val vm = createViewModel(bridge = null)
        assertFalse(vm.isSessionAlignedAndUsable())

        // Toggling Drop Connection works immediately without CDP prerequisite
        vm.setNetworkDrop(true)

        assertTrue(vm.state.value.isNetworkDropped)
        assertTrue(vm.kernelState.value.isNetworkDropped)

        vm.setNetworkDrop(false)
        assertFalse(vm.state.value.isNetworkDropped)
        assertFalse(vm.kernelState.value.isNetworkDropped)
    }

    @Test
    fun applyFaultRule_whenDropConnection_withoutActiveCdpSession_activatesDropWithoutWarning() {
        val vm = createViewModel(bridge = null)
        assertFalse(vm.isSessionAlignedAndUsable())

        val dropRule = com.coppermux.cutools.core.browser.FaultRule(
            id = "drop-1",
            urlPattern = "https://example.com/api",
            dropConnection = true,
        )

        vm.applyFaultRule(dropRule)

        assertTrue(vm.state.value.activeFaultRules.any { it.id == "drop-1" })
        assertTrue(vm.state.value.isNetworkDropped)
        // Decoupled architecture: No warning snackbar required because native WebView controls take over
        assertEquals(null, vm.state.value.transientMessage)
    }
}

private class FakeShellSessionDao : WorkspaceSessionDao() {
    private val sessions = mutableListOf<WorkspaceSession>()
    private val mutex = Mutex()

    override suspend fun getAllSessions(): List<WorkspaceSession> = mutex.withLock {
        sessions.sortedWith(
            compareBy<WorkspaceSession> { if (it.category == WorkspaceCategory.WORK) 0 else 1 }
                .thenByDescending { it.lastActiveAt }
        )
    }

    override suspend fun getSessionById(id: String): WorkspaceSession? = mutex.withLock {
        sessions.firstOrNull { it.id == id }
    }

    override suspend fun insertSession(session: WorkspaceSession): Unit = mutex.withLock {
        sessions.add(session)
    }

    override suspend fun updateSession(session: WorkspaceSession): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx != -1) sessions[idx] = session
    }

    override suspend fun upsert(session: WorkspaceSession): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx != -1) sessions[idx] = session else sessions.add(session)
    }

    override suspend fun deleteSession(id: String): Unit = mutex.withLock {
        sessions.removeAll { it.id == id }
    }

    override suspend fun getSessionCount(): Int = mutex.withLock {
        sessions.size
    }

    override suspend fun updateStatus(id: String, status: ConnectionStatus): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx != -1) sessions[idx] = sessions[idx].copy(status = status)
    }

    override suspend fun updateDisplayName(id: String, name: String): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx != -1) sessions[idx] = sessions[idx].copy(displayName = name)
    }

    override suspend fun togglePin(id: String, pinned: Boolean): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx != -1) sessions[idx] = sessions[idx].copy(isPinned = pinned)
    }
}
