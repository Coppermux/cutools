package com.coppermux.cutools.core.kernel

import com.coppermux.cutools.core.browser.DashboardEventCategory
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.DashboardSeverity
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.connection.ConnectionState
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.network.FetchInterceptionEngine
import com.coppermux.cutools.core.network.MockResponseRuleManager
import com.coppermux.cutools.core.network.MockResponseRuleSpec
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 automated test suite covering:
 * - Old-session console events arriving after reset.
 * - Old-session network events arriving after reset.
 * - In-flight CDP responses.
 * - Pending Fetch interceptions.
 * - Reset during navigation.
 * - Reset during disconnection.
 * - Rapid repeated resets.
 * - Target replacement.
 * - Session epoch correctness.
 * - Store cleanup consistency.
 * - Successful post-reset reconnection.
 * - Stale target cache rejection.
 */
class KernelResetIsolationTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var kernel: DiagnosticKernel
    private lateinit var cm: ConnectionManager

    private lateinit var domInspector: DomInspector
    private lateinit var cssInspector: CssInspector
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var consoleInspector: ConsoleInspector
    private lateinit var dashboardEventStream: DashboardEventStream
    private lateinit var faultInterceptorEngine: FaultInterceptorEngine
    private lateinit var networkEngine: NetworkEngine
    private lateinit var fetchInterceptionEngine: FetchInterceptionEngine
    private lateinit var webViewEngine: WebViewEngine

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        kernel = DiagnosticKernel()
        cm = ConnectionManager.create(transport, DispatcherConfig(defaultTimeoutMs = 2000), kernel)
        cm.ensureInitialized()
        cm.eventRouter.kernel = kernel

        domInspector = DomInspector(cm.dispatcher, cm.eventRouter)
        cssInspector = CssInspector(cm.dispatcher, cm.eventRouter, domInspector)
        executionEngine = ExecutionEngine(cm.dispatcher)
        consoleInspector = ConsoleInspector(cm.dispatcher, cm.eventRouter, domInspector, executionEngine)
        dashboardEventStream = DashboardEventStream(kernel = kernel)
        faultInterceptorEngine = FaultInterceptorEngine()
        networkEngine = NetworkEngine(
            dispatcher = cm.dispatcher,
            eventRouter = cm.eventRouter,
            dashboardEventStream = dashboardEventStream,
        )
        fetchInterceptionEngine = FetchInterceptionEngine(
            dispatcher = cm.dispatcher,
            eventRouter = cm.eventRouter,
            ruleManager = MockResponseRuleManager(),
        )
        webViewEngine = WebViewEngine(
            eventStream = dashboardEventStream,
            faultInterceptorEngine = faultInterceptorEngine,
            kernel = kernel,
        )

        kernel.resetCoordinator.bindSubsystems(
            dispatcher = cm.dispatcher,
            targetManagerProvider = { cm.targetManager },
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
    }

    @After
    fun tearDown() {
        cm.shutdown()
        kernel.shutdown()
        consoleInspector.shutdown()
        networkEngine.shutdown()
        fetchInterceptionEngine.shutdown()
    }

    private fun attachTarget(targetId: String = "page-1", sessionId: String = "cdp-1", type: String = "page") {
        cm.eventRouter.handleEvent(
            Event(
                method = "Target.attachedToTarget",
                params = mapOf(
                    "sessionId" to sessionId,
                    "targetInfo" to mapOf("targetId" to targetId, "type" to type),
                    "waitingForDebugger" to false,
                ),
            ),
        )
        val session = SessionInfo(sessionId, targetId, "ws://test")
        consoleInspector.bindSession(session)
        networkEngine.bindSession(session)
        fetchInterceptionEngine.bindSession(session)
        executionEngine.bindSession(session)
        domInspector.bindSession(session)
    }

    @Test
    fun oldSessionConsoleEvents_arrivingAfterReset_areRejected() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        val epochBefore = kernel.currentEpoch
        assertEquals(1L, epochBefore)

        // Deliver a console event in the first session
        cm.eventRouter.handleEvent(
            Event(
                method = "Runtime.consoleAPICalled",
                params = mapOf("type" to "log", "args" to listOf(mapOf("value" to "first-session log"))),
                sessionId = "cdp-1",
            ),
        )
        consoleInspector.drain()
        assertEquals(1, consoleInspector.state.entries.size)

        // Perform reset with target session recreation
        kernel.performReset("Test Reset")
        attachTarget("page-1", "cdp-2")
        consoleInspector.drain()
        assertTrue("Console entries must be cleared by reset", consoleInspector.state.entries.isEmpty())

        // Deliver a delayed console event from the old session (cdp-1)
        cm.eventRouter.handleEvent(
            Event(
                method = "Runtime.consoleAPICalled",
                params = mapOf("type" to "log", "args" to listOf(mapOf("value" to "stale-session log"))),
                sessionId = "cdp-1",
            ),
        )
        consoleInspector.drain()
        assertTrue("Stale console event must NOT enter cleared console store", consoleInspector.state.entries.isEmpty())
    }

    @Test
    fun oldSessionConsoleEvents_withStaleProvenance_areRejected() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        kernel.performReset("Reset")
        attachTarget("page-1", "cdp-2")

        val staleEvent = Event(
            method = "Runtime.consoleAPICalled",
            params = mapOf("type" to "log", "args" to listOf(mapOf("value" to "stale"))),
            sessionId = "cdp-1",
            provenance = com.coppermux.cutools.core.kernel.EventProvenance(cdpSessionId = "cdp-1", kernelEpoch = 1L),
        )
        cm.eventRouter.handleEvent(staleEvent)
        consoleInspector.drain()
        assertTrue("Stale event with old epoch must be rejected", consoleInspector.state.entries.isEmpty())
    }

    @Test
    fun oldSessionNetworkEvents_arrivingAfterReset_areRejected() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")

        // Initiate a network request in the first session
        cm.eventRouter.handleEvent(
            Event(
                method = "Network.requestWillBeSent",
                params = mapOf(
                    "requestId" to "req-1",
                    "documentURL" to "https://example.com/api",
                    "request" to mapOf("url" to "https://example.com/api", "method" to "GET"),
                ),
                sessionId = "cdp-1",
            ),
        )
        networkEngine.drain()
        assertEquals(1, networkEngine.state.requests.size)

        // Perform kernel reset with session recreation
        kernel.performReset("Test Reset")
        attachTarget("page-1", "cdp-2")
        networkEngine.drain()
        assertTrue("Network requests must be cleared by reset", networkEngine.state.requests.isEmpty())

        // Deliver a late loadingFinished event for the old request from cdp-1
        cm.eventRouter.handleEvent(
            Event(
                method = "Network.loadingFinished",
                params = mapOf("requestId" to "req-1", "timestamp" to 12345.0),
                sessionId = "cdp-1",
            ),
        )
        networkEngine.drain()
        assertTrue("Stale network event must NOT resurrect cleared request", networkEngine.state.requests.isEmpty())
    }

    @Test
    fun inflightCdpResponses_belongingToInvalidatedSession_areCancelledAndIgnored() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")

        val initialCount = cm.dispatcher.pendingCommandCount
        val callbackInvokedWithFailure = AtomicBoolean(false)
        cm.dispatcher.sendCommand(
            method = "Runtime.evaluate",
            params = mapOf("expression" to "1 + 1"),
            sessionId = "cdp-1",
        ) { cmdResult ->
            if (cmdResult is CommandResult.Failure) {
                callbackInvokedWithFailure.set(true)
            }
        }
        assertEquals(initialCount + 1, cm.dispatcher.pendingCommandCount)

        // Perform reset
        kernel.performReset("Test Reset")

        // The pending command callback must have been failed promptly
        assertTrue("Pending command must be failed when session is reset", callbackInvokedWithFailure.get())
        // All session-scoped commands for cdp-1 are cancelled; only browser-level commands remain
        assertEquals(1, cm.dispatcher.pendingCommandCount)

        // Deliver late response from Chrome
        val lastSent = transport.sentMessages.last()
        val id = Regex("\"id\":(\\d+)").find(lastSent)!!.groupValues[1].toLong()
        transport.deliverMessage("""{"id":$id,"sessionId":"cdp-1","result":{"result":{"value":2}}}""")
        // No crash, and dispatcher remains clean
        assertEquals(1, cm.dispatcher.pendingCommandCount)
    }

    @Test
    fun pendingFetchInterceptions_areSafelyResolvedDuringReset() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")

        // Simulate paused request
        cm.eventRouter.handleEvent(
            Event(
                method = "Fetch.requestPaused",
                params = mapOf(
                    "requestId" to "fetch-req-1",
                    "request" to mapOf("url" to "https://example.com/data", "method" to "GET"),
                ),
                sessionId = "cdp-1",
            ),
        )
        fetchInterceptionEngine.ruleManager.upsert(
            MockResponseRuleSpec(id = "m1", urlPattern = "*data*", statusCode = 200),
        )
        assertEquals(1, fetchInterceptionEngine.ruleManager.size())

        // Perform reset
        kernel.performReset("Test Reset")

        // Check that continueRequest was sent to unblock the paused request
        assertTrue(
            "Fetch.continueRequest must be dispatched to unblock paused request",
            transport.sentMessages.any { it.contains("Fetch.continueRequest") && it.contains("fetch-req-1") },
        )
        assertEquals(0, fetchInterceptionEngine.state.pendingCount)
        assertEquals(0, fetchInterceptionEngine.ruleManager.size())
    }

    @Test
    fun reset_rebindsFetchInterceptionEngine_toReconciledActiveSession() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        assertEquals("cdp-1", fetchInterceptionEngine.session?.sessionId)

        // Perform reset
        kernel.performReset("Test Reset")

        // Reconciled attachment is confirmed and fetchInterceptionEngine is re-bound
        assertNotNull(fetchInterceptionEngine.session)
        assertEquals("cdp-1", fetchInterceptionEngine.session?.sessionId)
        assertEquals("page-1", fetchInterceptionEngine.session?.targetId)
    }

    @Test
    fun resetWithTargetChange_rebindsFetchInterceptionEngine_toNewSession() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        assertEquals("cdp-1", fetchInterceptionEngine.session?.sessionId)

        // Detach fetch session to simulate detachment
        fetchInterceptionEngine.detach()
        assertNull(fetchInterceptionEngine.session)

        // Perform reset with active target page-1 cdp-1
        kernel.performReset("Test Reset Rebind")

        // Reconciled active session must re-bind fetchInterceptionEngine
        assertNotNull(fetchInterceptionEngine.session)
        assertEquals("cdp-1", fetchInterceptionEngine.session?.sessionId)
    }

    @Test
    fun resetDuringNavigation_abortsNavigationAndResetsStores() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")

        // Simulate active navigation started
        webViewEngine.interceptorClient.onPageStarted(null, "https://example.com/navigating", null)
        assertEquals("https://example.com/navigating", webViewEngine.currentDocumentUrl)

        // Perform reset during navigation
        kernel.performReset("Reset during navigation")

        assertNull("activeDocumentUrl must be null after reset", webViewEngine.currentDocumentUrl)
        assertTrue(dashboardEventStream.state.events.isEmpty())
        assertTrue(consoleInspector.state.entries.isEmpty())
        assertTrue(networkEngine.state.requests.isEmpty())
    }

    @Test
    fun resetDuringDisconnection_settlesKernelToIdle() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        cm.disconnect()
        assertEquals(KernelState.IDLE, kernel.snapshot.state)

        val result = kernel.performReset("Reset while disconnected")
        assertTrue(result is ResetResult.Success)
        assertEquals(KernelState.IDLE, kernel.snapshot.state)
        assertNull(kernel.currentSession)
    }

    @Test
    fun rapidRepeatedResets_areHandledDeterministically() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")

        val initialEpoch = kernel.currentEpoch
        repeat(5) { i ->
            val res = kernel.performReset("Rapid reset #$i")
            assertTrue(res is ResetResult.Success)
            assertEquals(KernelState.ATTACHED, kernel.snapshot.state)
            assertTrue(dashboardEventStream.state.events.isEmpty())
            assertTrue(consoleInspector.state.entries.isEmpty())
            assertTrue(networkEngine.state.requests.isEmpty())
        }
        assertEquals(initialEpoch + 10, kernel.currentEpoch)
    }

    @Test
    fun targetReplacement_duringReset_establishesSuccessorOnNewTarget() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        val ticket = kernel.beginReset("Reset with target replacement")!!

        // New target attaches while in RESETTING
        cm.eventRouter.handleEvent(
            Event(
                method = "Target.attachedToTarget",
                params = mapOf(
                    "sessionId" to "cdp-2",
                    "targetInfo" to mapOf("targetId" to "page-2", "type" to "page"),
                ),
            ),
        )

        val completeResult = kernel.completeReset(
            ticket = ticket,
            confirmedAttachment = CdpAttachment("page-2", "cdp-2"),
            useExplicitAttachment = true,
        )
        assertTrue(completeResult is TransitionResult.Applied)
        assertEquals(KernelState.ATTACHED, kernel.snapshot.state)
        assertEquals("page-2", kernel.currentSession?.targetId)
        assertEquals("cdp-2", kernel.currentSession?.cdpSessionId)
    }

    @Test
    fun sessionEpochCorrectness_andStoreCleanupConsistency() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        val beforeSession = kernel.currentSession!!
        val oldJob = kernel.launchInSession(beforeSession.epoch) { awaitCancellation() }
        assertNotNull(oldJob)

        // Populate stores
        dashboardEventStream.append(
            com.coppermux.cutools.core.browser.DashboardEvent(
                category = DashboardEventCategory.NETWORK,
                severity = DashboardSeverity.ERROR,
                kind = "HTTP 500",
                url = "https://example.com/err",
            ),
        )
        faultInterceptorEngine.upsert(FaultRule(id = "f1", urlPattern = "*test*", latencyMs = 100))
        consoleInspector.setInput("input text")
        consoleInspector.drain()

        val resetResult = kernel.performReset("Comprehensive Store Reset")
        assertTrue(resetResult is ResetResult.Success)

        // Epoch advanced
        val afterSession = kernel.currentSession!!
        assertEquals(beforeSession.epoch + 2, afterSession.epoch)
        assertNotEquals(beforeSession.sessionKey, afterSession.sessionKey)
        assertTrue("Old session-scoped coroutine must be cancelled", oldJob!!.isCancelled)

        // All stores verified empty
        consoleInspector.drain()
        networkEngine.drain()
        assertTrue(consoleInspector.state.entries.isEmpty())
        assertEquals("", consoleInspector.state.input)
        assertTrue(dashboardEventStream.state.events.isEmpty())
        assertTrue(faultInterceptorEngine.all().isEmpty())
        assertTrue(networkEngine.state.requests.isEmpty())
        assertEquals(0, fetchInterceptionEngine.ruleManager.size())
        assertNull(webViewEngine.currentDocumentUrl)
    }

    @Test
    fun successfulPostResetReconnection_establishesFreshSession() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")
        cm.disconnect()

        // Reset while disconnected
        kernel.performReset("Reset while offline")
        assertEquals(KernelState.IDLE, kernel.snapshot.state)

        // Reconnect
        cm.connect("ws://test") { }
        attachTarget("page-fresh", "cdp-fresh")
        assertEquals(KernelState.ATTACHED, kernel.snapshot.state)
        assertEquals("page-fresh", kernel.currentSession?.targetId)
        assertEquals("cdp-fresh", kernel.currentSession?.cdpSessionId)
    }

    @Test
    fun staleTargetCache_cannotMasqueradeAsValidAttachment() {
        cm.connect("ws://test") { }
        attachTarget("page-1", "cdp-1")

        // Target is destroyed in TargetManager
        cm.eventRouter.handleEvent(
            Event(
                method = "Target.targetDestroyed",
                params = mapOf("targetId" to "page-1"),
            ),
        )
        // Active session removed from TargetManager
        assertNull(cm.targetManager?.activeSession)

        // Reset App executed
        val result = kernel.performReset("Reset after target destruction")
        assertTrue(result is ResetResult.Success)

        // Kernel must NOT publish ATTACHED with stale page-1 attachment; must settle to CONNECTING
        assertEquals(KernelState.CONNECTING, kernel.snapshot.state)
        assertNull("Stale target must not masquerade as active session", kernel.currentSession)
    }
}
