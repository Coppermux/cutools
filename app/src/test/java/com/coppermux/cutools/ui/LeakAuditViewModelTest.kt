package com.coppermux.cutools.ui

import com.coppermux.cutools.core.audits.AuditEngine
import com.coppermux.cutools.core.memory.MemoryEngine
import com.coppermux.cutools.core.performance.PerformanceEngine
import com.coppermux.cutools.core.profiling.CpuProfilerEngine
import com.coppermux.cutools.core.profiling.HeapProfilerEngine
import com.coppermux.cutools.core.profiling.TracingEngine
import com.coppermux.cutools.core.security.SecurityEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetEngine
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.profiling.viewmodel.PerformanceToolViewModel
import com.coppermux.cutools.ui.audits.AuditViewModel
import com.coppermux.cutools.ui.memory.MemoryViewModel
import com.coppermux.cutools.ui.performance.PerformanceViewModel
import com.coppermux.cutools.ui.security.SecurityViewModel
import com.coppermux.cutools.ui.target.TargetViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 15 — ViewModel scope-closure leak audit.
 *
 * Verifies that calling the ViewModel's lifecycle-scope teardown ([shutdown])
 * clears every active CDP event listener its engine registered on the shared
 * [CdpEventRouter]. Each domain ViewModel (Memory / Performance / Audits /
 * Target / Security + the unified Performance tool) is constructed against the
 * same fake transport/router pipeline the app uses, bound to a session, and then
 * torn down — asserting zero leaked listeners afterward (No-Fake-Capability:
 * handlers are verified to exist before teardown, so a zero-count is a genuine
 * cleanup, never a doctored pass).
 */
class LeakAuditViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
    }

    private fun assertSubscribersRegistered(methods: List<String>) {
        methods.forEach { method ->
            assertTrue("expected a live listener on $method", router.subscriptionCount(method) > 0)
        }
    }

    private fun assertSubscriptionsCleared(methods: List<String>) {
        methods.forEach { method ->
            assertEquals("zero leaked event listeners after scope closure for $method", 0, router.subscriptionCount(method))
        }
    }

    // ================================================================
    // Memory ViewModel
    // ================================================================

    @Test
    fun memoryViewModel_shutdown_clearsHeapProfilerListeners() {
        val engine = MemoryEngine(dispatcher, router)
        val vm = MemoryViewModel(engine)
        vm.attachTarget(session)
        val methods = listOf("HeapProfiler.addHeapSnapshotChunk", "HeapProfiler.reportHeapSnapshotProgress")
        assertSubscribersRegistered(methods)
        vm.shutdown()
        assertSubscriptionsCleared(methods)
    }

    // ================================================================
    // Performance ViewModel
    // ================================================================

    @Test
    fun performanceViewModel_shutdown_clearsMetricsListener() {
        val engine = PerformanceEngine(dispatcher, router)
        val vm = PerformanceViewModel(engine)
        vm.attachTarget(session)
        val methods = listOf("Performance.metrics")
        assertSubscribersRegistered(methods)
        vm.shutdown()
        assertSubscriptionsCleared(methods)
    }

    // ================================================================
    // Audits ViewModel
    // ================================================================

    @Test
    fun auditViewModel_shutdown_clearsAuditIssueListener() {
        val engine = AuditEngine(dispatcher, router)
        val vm = AuditViewModel(engine)
        vm.attachTarget(session)
        val methods = listOf("Audits.issueAdded")
        assertSubscribersRegistered(methods)
        vm.shutdown()
        assertSubscriptionsCleared(methods)
    }

    // ================================================================
    // Security ViewModel
    // ================================================================

    @Test
    fun securityViewModel_shutdown_clearsSecurityListeners() {
        val engine = SecurityEngine(dispatcher, router)
        val vm = SecurityViewModel(engine)
        vm.attachTarget(session)
        val methods = listOf("Security.securityStateChanged", "Security.certificateError")
        assertSubscribersRegistered(methods)
        vm.shutdown()
        assertSubscriptionsCleared(methods)
    }

    // ================================================================
    // Target ViewModel
    // ================================================================

    @Test
    fun targetViewModel_shutdown_clearsTargetListeners() {
        val engine = TargetEngine(dispatcher, router)
        val vm = TargetViewModel(engine)
        vm.attachTarget(session)
        val methods = listOf("Target.targetCreated", "Target.targetDestroyed", "Target.targetInfoChanged")
        assertSubscribersRegistered(methods)
        vm.shutdown()
        assertSubscriptionsCleared(methods)
    }

    // ================================================================
    // Performance Tools ViewModel (Tracing + CPU + Heap Profiler)
    // ================================================================

    @Test
    fun performanceToolViewModel_shutdown_clearsAllProfilerListeners() {
        val tracing = TracingEngine(dispatcher, router)
        val cpu = CpuProfilerEngine(dispatcher, router)
        val heap = HeapProfilerEngine(dispatcher, router)
        val vm = PerformanceToolViewModel(tracing, cpu, heap)
        vm.attachTarget(session)

        val profilerMethods = listOf(
            "Profiler.consoleProfileStarted",
            "Profiler.consoleProfileFinished",
            "HeapProfiler.addHeapSnapshotChunk",
            "HeapProfiler.reportHeapSnapshotProgress",
        )
        assertSubscribersRegistered(profilerMethods)

        // Tracing registers its listeners only when a recording starts.
        vm.startTracing { }
        val tracingMethods = listOf("Tracing.dataCollected", "Tracing.tracingComplete")
        assertSubscribersRegistered(tracingMethods)

        vm.shutdown()
        assertSubscriptionsCleared(profilerMethods + tracingMethods)
    }

    // ================================================================
    // Rebind lifecycle: teardown-then-rebind never leaks
    // ================================================================

    @Test
    fun memoryViewModel_rebindAfterShutdown_registersExactlyOnce() {
        val engine = MemoryEngine(dispatcher, router)
        val vm = MemoryViewModel(engine)
        vm.attachTarget(session)
        vm.shutdown()
        val methods = listOf("HeapProfiler.addHeapSnapshotChunk", "HeapProfiler.reportHeapSnapshotProgress")
        assertSubscriptionsCleared(methods)

        // The engine rebinds cleanly — exactly one listener per method.
        engine.bindSession(session)
        methods.forEach { method ->
            assertEquals(1, router.subscriptionCount(method))
        }
        engine.shutdown()
        assertSubscriptionsCleared(methods)
    }
}