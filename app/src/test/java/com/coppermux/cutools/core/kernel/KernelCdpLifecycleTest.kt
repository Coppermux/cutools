package com.coppermux.cutools.core.kernel

import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.connection.ConnectionState
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.target.TargetManagerListener
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.ui.shell.ShellViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Verifies the kernel's integration with the real ConnectionManager /
 * TargetManager lifecycle (driven through [FakeCdpTransport] and the event
 * router), and with [ShellViewModel]. JVM-only: no device, WebView or Chrome.
 */
class KernelCdpLifecycleTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var kernel: DiagnosticKernel
    private lateinit var cm: ConnectionManager

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        kernel = DiagnosticKernel()
        cm = ConnectionManager.create(transport, DispatcherConfig(defaultTimeoutMs = 1000), kernel)
        cm.ensureInitialized()
    }

    @After
    fun tearDown() {
        cm.shutdown()
        kernel.shutdown()
    }

    private fun attachedToTarget(targetId: String, sessionId: String, type: String = "page") {
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
    }

    private fun detachedFromTarget(targetId: String, sessionId: String) {
        cm.eventRouter.handleEvent(
            Event(
                method = "Target.detachedFromTarget",
                params = mapOf("targetId" to targetId, "sessionId" to sessionId),
            ),
        )
    }

    private fun targetDestroyed(targetId: String) {
        cm.eventRouter.handleEvent(Event(method = "Target.targetDestroyed", params = mapOf("targetId" to targetId)))
    }

    private fun assertKernel(state: KernelState, epoch: Long? = null) {
        val s = kernel.snapshot
        assertEquals("kernel state", state, s.state)
        epoch?.let { assertEquals("kernel epoch", it, s.epoch) }
        KernelReducerTest.assertInvariants(s)
    }

    @Test
    fun applicationKernel_isInjectedIntoConnectionManager() {
        assertSame(kernel, cm.kernel)
    }

    @Test
    fun normalConnectAndAttach_establishesSingleKernelSession() {
        cm.connect("ws://test") { }
        assertKernel(KernelState.CONNECTING, 0)
        assertEquals(LinkStatus.UP, kernel.snapshot.link)

        attachedToTarget("page-1", "cdp-1")
        assertEquals(ConnectionState.ATTACHED, cm.connectionState)
        assertKernel(KernelState.ATTACHED, 1)
        val session = kernel.currentSession!!
        assertEquals("page-1", session.targetId)
        assertEquals("cdp-1", session.cdpSessionId)
        assertEquals(cm.targetManager?.activeSession?.sessionId, session.cdpSessionId)
    }

    @Test
    fun attachmentWithoutConnection_isRejectedByKernel() {
        // Pre-existing ConnectionManager behaviour moves to ATTACHED regardless;
        // the kernel refuses to create a session without a connection.
        attachedToTarget("page-1", "cdp-1")
        assertKernel(KernelState.IDLE, 0)
        assertNull(kernel.currentSession)
    }

    @Test
    fun targetDetachment_invalidatesSession_andReattachCreatesNewEpoch() {
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        val first = kernel.currentSession!!

        detachedFromTarget("page-1", "cdp-1")
        assertEquals(ConnectionState.CONNECTED, cm.connectionState)
        assertKernel(KernelState.CONNECTING, 2)
        assertNull(kernel.currentSession)

        attachedToTarget("page-1", "cdp-2")
        assertKernel(KernelState.ATTACHED, 3)
        assertNotEquals(first.sessionKey, kernel.currentSession!!.sessionKey)
        assertEquals("cdp-2", kernel.currentSession!!.cdpSessionId)
    }

    @Test
    fun targetDestroyed_invalidatesSession() {
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        targetDestroyed("page-1")
        assertKernel(KernelState.CONNECTING, 2)
    }

    @Test
    fun targetReplacement_supersedesPreviousSession_withoutCompetingSession() {
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        attachedToTarget("page-2", "cdp-2")
        assertKernel(KernelState.ATTACHED, 2)
        assertEquals("page-2", kernel.currentSession!!.targetId)

        // A late detach for the superseded target must not invalidate the new session.
        detachedFromTarget("page-1", "cdp-1")
        assertKernel(KernelState.ATTACHED, 2)
        assertEquals("page-2", kernel.currentSession!!.targetId)
    }

    @Test
    fun disconnect_invalidatesSession_exactlyOnce_andReconnectCreatesNewSession() {
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        val first = kernel.currentSession!!

        cm.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, cm.connectionState)
        assertKernel(KernelState.IDLE, 2)
        assertEquals(LinkStatus.DOWN, kernel.snapshot.link)

        cm.connect("ws://test") { }
        assertKernel(KernelState.CONNECTING, 2)
        attachedToTarget("page-1", "cdp-9")
        assertKernel(KernelState.ATTACHED, 3)
        assertNotEquals(first.sessionKey, kernel.currentSession!!.sessionKey)
    }

    @Test
    fun remoteClose_invalidatesSession() {
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        transport.deliverClosed("remote went away")
        assertEquals(ConnectionState.DISCONNECTED, cm.connectionState)
        assertKernel(KernelState.IDLE, 2)
    }

    @Test
    fun transportError_movesKernelToError_andRecoveryEstablishesNewSession() {
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        transport.deliverError(TransportException("Connection refused"))
        assertEquals(ConnectionState.ERROR, cm.connectionState)
        assertKernel(KernelState.ERROR, 2)
        assertEquals("Connection refused", kernel.snapshot.lastError)

        cm.connect("ws://test") { }
        assertKernel(KernelState.CONNECTING, 2)
        assertNull(kernel.snapshot.lastError)
        // NOTE: a different target is used because TargetManager is not cleared on
        // transport error (pre-existing behaviour) and would suppress onAttached for
        // the same targetId. See Phase 1 report: lifecycle risks.
        attachedToTarget("page-2", "cdp-2")
        assertKernel(KernelState.ATTACHED, 3)
    }

    @Test
    fun connectionManagerReset_returnsKernelToIdle() {
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        cm.reset()
        assertKernel(KernelState.IDLE, 2)
    }

    @Test
    fun rapidReconnection_neverProducesCompetingSessions() {
        repeat(5) { i ->
            cm.connect("ws://test") { }
            attachedToTarget("page-$i", "cdp-$i")
            attachedToTarget("page-$i-b", "cdp-$i-b")
            KernelReducerTest.assertInvariants(kernel.snapshot)
            assertEquals("page-$i-b", kernel.currentSession!!.targetId)
        }
        assertKernel(KernelState.ATTACHED)
        // Each iteration: attach(+1), replace(+1), and every reconnect after the
        // first invalidates the previous session via disconnect (+1).
        assertEquals(5L * 2 + 4, kernel.currentEpoch)
    }

    @Test
    fun downstreamListeners_observeAlreadyUpdatedKernel() {
        val seenOnAttached = mutableListOf<KernelSnapshot>()
        val seenOnDetached = mutableListOf<KernelSnapshot>()
        val seenOnStateChange = mutableListOf<Pair<ConnectionState, KernelState>>()
        cm.targetManager!!.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) {}
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) { seenOnAttached += kernel.snapshot }
            override fun onDetached(targetId: String) { seenOnDetached += kernel.snapshot }
            override fun onCleared() {}
            override fun onError(error: Throwable) {}
        })
        cm.addStateListener(object : ConnectionManager.StateListener {
            override fun onStateChanged(state: ConnectionState, error: Throwable?) {
                seenOnStateChange += state to kernel.snapshot.state
            }
        })
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        detachedFromTarget("page-1", "cdp-1")
        cm.disconnect()

        assertEquals(KernelState.ATTACHED, seenOnAttached.single().state)
        assertEquals("cdp-1", seenOnAttached.single().session?.cdpSessionId)
        assertEquals(KernelState.CONNECTING, seenOnDetached.single().state)
        assertTrue(seenOnStateChange.contains(ConnectionState.CONNECTING to KernelState.CONNECTING))
        assertTrue(seenOnStateChange.contains(ConnectionState.DISCONNECTED to KernelState.IDLE))
    }

    // ------------------------------------------------------------------
    // ShellViewModel integration
    // ------------------------------------------------------------------

    private fun shellViewModel(faultEngine: FaultInterceptorEngine = FaultInterceptorEngine()): ShellViewModel {
        val dom = DomInspector(cm.dispatcher, cm.eventRouter)
        val exec = ExecutionEngine(cm.dispatcher)
        val console = ConsoleInspector(cm.dispatcher, cm.eventRouter, dom, exec)
        val bridge: AndroidCdpBridge = mock()
        return ShellViewModel(
            connectionManager = cm,
            consoleInspector = console,
            executionEngine = exec,
            dashboardEventStream = DashboardEventStream(),
            faultInterceptorEngine = faultEngine,
            cdpBridge = bridge,
        )
    }

    @Test
    fun shellViewModel_exposesKernelState_andUsesKernelSessionForAlignment() {
        val vm = shellViewModel()
        assertSame(kernel.state, vm.kernelState)
        assertFalse(vm.isSessionAlignedAndUsable())

        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        assertTrue(vm.isSessionAlignedAndUsable())

        detachedFromTarget("page-1", "cdp-1")
        assertFalse(vm.isSessionAlignedAndUsable())
    }

    @Test
    fun shellViewModel_alignment_isFalse_forNonPageKernelSession() {
        val vm = shellViewModel()
        cm.connect("ws://test") { }
        attachedToTarget("sw-1", "cdp-sw", type = "service_worker")
        assertKernel(KernelState.ATTACHED)
        assertFalse(vm.isSessionAlignedAndUsable())
    }

    @Test
    fun resetApp_advancesEpoch_invalidatesOldSession_preservesCdpAttachment() {
        val faultEngine = FaultInterceptorEngine()
        val vm = shellViewModel(faultEngine)
        cm.connect("ws://test") { }
        attachedToTarget("page-1", "cdp-1")
        val before = kernel.currentSession!!
        val oldJob = kernel.launchInSession(before.epoch) { kotlinx.coroutines.awaitCancellation() }
        assertNotNull(oldJob)
        faultEngine.upsert(FaultRule(id = "f1", urlPattern = "example.com", latencyMs = 100))
        assertEquals(1, faultEngine.all().size)

        vm.resetApp()

        // Kernel: begin(+1) and successor session on the live attachment (+1).
        assertKernel(KernelState.ATTACHED, before.epoch + 2)
        val after = kernel.currentSession!!
        assertNotEquals(before.sessionKey, after.sessionKey)
        assertEquals(before.attachment, after.attachment)
        assertFalse(kernel.isCurrent(before.epoch))
        assertTrue(oldJob!!.isCancelled)
        // Existing Reset App behaviour preserved.
        assertEquals(ConnectionState.ATTACHED, cm.connectionState)
        assertTrue(vm.isSessionAlignedAndUsable())
        assertTrue(faultEngine.all().isEmpty())
    }

    @Test
    fun resetApp_whileDisconnected_returnsKernelToIdle() {
        val vm = shellViewModel()
        vm.resetApp()
        assertKernel(KernelState.IDLE, 1)
    }
}
