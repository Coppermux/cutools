package com.coppermux.cutools.tools.profiling

import com.coppermux.cutools.core.profiling.CpuProfilerEngine
import com.coppermux.cutools.core.profiling.HeapProfilerEngine
import com.coppermux.cutools.core.profiling.TracingEngine
import com.coppermux.cutools.core.profiling.models.CpuProfilerActionResult
import com.coppermux.cutools.core.profiling.models.CpuProfilerStatus
import com.coppermux.cutools.core.profiling.models.HeapProfilerActionResult
import com.coppermux.cutools.core.profiling.models.HeapProfilerStatus
import com.coppermux.cutools.core.profiling.models.HeapSnapshotResult
import com.coppermux.cutools.core.profiling.models.TraceTimeline
import com.coppermux.cutools.core.profiling.models.TracingActionResult
import com.coppermux.cutools.core.profiling.models.TracingStatus
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.profiling.models.ProfilingMode
import com.coppermux.cutools.tools.profiling.viewmodel.PerformanceToolViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end test suite for the Sub-Phase 7D unified DevTools Performance
 * panel ViewModel.
 *
 * Every profiling engine (Tracing, CPU Profiler, Heap Profiler) is constructed
 * against the same fake transport/router and driven through raw CDP JSON, then
 * the ViewModel's attach / detach / connection-loss coordination, state
 * aggregation, mode transitions, action dispatching, and error propagation into
 * the snapshot are verified in the same pipeline the app uses against Chrome.
 */
class PerformanceToolViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var tracing: TracingEngine
    private lateinit var cpu: CpuProfilerEngine
    private lateinit var heap: HeapProfilerEngine
    private lateinit var viewModel: PerformanceToolViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")
    private val otherSession = SessionInfo("session-2", "target-2", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        tracing = TracingEngine(dispatcher, router)
        cpu = CpuProfilerEngine(dispatcher, router)
        heap = HeapProfilerEngine(dispatcher, router)
        viewModel = PerformanceToolViewModel(tracing, cpu, heap)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun deliverEvent(method: String, paramsJson: String) {
        transport.deliverMessage("""{"method":"$method","sessionId":"session-1","params":$paramsJson}""")
    }

    // ============ populate genuine engine state ============

    private fun recordTimeline() {
        viewModel.startTracing {}
        val startId = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { tracing.status == TracingStatus.RECORDING })

        deliverEvent("Tracing.dataCollected", """{"value":[
            {"name":"RunTask","cat":"toplevel","ph":"X","ts":100,"dur":60000,"pid":1,"tid":1},
            {"name":"FunctionCall","cat":"v8","ph":"X","ts":200,"dur":9000,"pid":1,"tid":1}
        ]}""")

        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        viewModel.stopTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        transport.deliverMessage("""{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[]}}""")
        assertTrue(waitFor { results.size == 1 })
    }

    private fun recordCpuProfile() {
        val enableResults = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        viewModel.enableCpuProfiler { enableResults.add(it) }
        val enableId = parseIdFor("Profiler.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { enableResults.size == 1 })

        val startResults = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        viewModel.startCpuProfile { startResults.add(it) }
        val startId = parseIdFor("Profiler.start")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { startResults.size == 1 })

        val stopResults = CopyOnWriteArrayList<CpuProfilerActionResult<com.coppermux.cutools.core.profiling.models.CpuProfileTiming>>()
        viewModel.stopCpuProfile { stopResults.add(it) }
        val stopId = parseIdFor("Profiler.stop")
        transport.deliverMessage(
            """{"id":$stopId,"sessionId":"session-1","result":{"profile":{
                "startTime":1000,"endTime":21000,
                "nodes":[
                    {"id":1,"functionName":"root","scriptId":"1","url":"app.js","children":[2,3]},
                    {"id":2,"functionName":"foo","scriptId":"1","url":"app.js","children":[]},
                    {"id":3,"functionName":"bar","scriptId":"1","url":"app.js","children":[4]},
                    {"id":4,"functionName":"baz","scriptId":"1","url":"app.js","children":[]}
                ],
                "samples":[2,2,3,4,4,2],
                "timeDeltas":[1000,2000,3000,4000,5000,6000]
            }}}"""
        )
        assertTrue(waitFor { stopResults.size == 1 })
    }

    private fun takeHeapSnapshot() {
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        viewModel.takeHeapSnapshot { results.add(it) }
        val snapId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$snapId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { heap.status == HeapProfilerStatus.TAKING_SNAPSHOT })

        val chunk = """{"snapshot":{"meta":{}},"nodes":[1,2,3],"strings":["a"],"edges":[]}"""
        deliverEvent("HeapProfiler.addHeapSnapshotChunk", """{"chunk":${jsonString(chunk)}}""")
        deliverEvent("HeapProfiler.reportHeapSnapshotProgress", """{"done":1,"total":1,"finished":true}""")
        assertTrue(waitFor { heap.snapshot != null })
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ============ target attach / detach coordination ============

    @Test
    fun attachTarget_bindsAllThreeEnginesInUnison() {
        viewModel.attachTarget(session)
        assertEquals(session, tracing.session)
        assertEquals(session, cpu.session)
        assertEquals(session, heap.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
        assertTrue(viewModel.state.attached)
        assertEquals("session-1", viewModel.state.sessionId)
        assertEquals("target-1", viewModel.state.targetId)
    }

    @Test
    fun detachTarget_clearsAllThreeEnginesInUnison() {
        viewModel.attachTarget(session)
        recordTimeline()
        assertTrue(waitFor { tracing.timeline.eventCount == 2 })

        viewModel.detachTarget()
        assertNull(tracing.session)
        assertNull(cpu.session)
        assertNull(heap.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        assertFalse(viewModel.state.attached)
        assertEquals(TracingStatus.IDLE, viewModel.state.tracingStatus)
        assertEquals(0, viewModel.state.timeline.eventCount)
    }

    @Test
    fun attachTarget_transitionsBetweenTargetsAndResetsState() {
        viewModel.attachTarget(session)
        recordTimeline()
        assertTrue(waitFor { tracing.timeline.eventCount == 2 })

        viewModel.attachTarget(otherSession)
        assertEquals(otherSession, tracing.session)
        assertEquals(otherSession, cpu.session)
        assertEquals(otherSession, heap.session)
        assertEquals(otherSession, viewModel.attachedSession)
        assertEquals(TracingStatus.IDLE, viewModel.state.tracingStatus)
        assertEquals(0, viewModel.state.timeline.eventCount)
        assertEquals("session-2", viewModel.state.sessionId)
    }

    @Test
    fun onConnectionLost_cleansUpAllThreeEngines() {
        viewModel.attachTarget(session)
        viewModel.startTracing {}
        val startId = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { tracing.status == TracingStatus.RECORDING })
        assertEquals(1, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals(1, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))

        viewModel.onConnectionLost()
        assertNull(tracing.session)
        assertNull(cpu.session)
        assertNull(heap.session)
        assertNull(viewModel.attachedSession)
        assertEquals("no tracing listener leak", 0, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals("no cpu listener leak", 0, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals("no heap listener leak", 0, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
    }

    // ============ state aggregation & mode transitions ============

    @Test
    fun refresh_aggregatesStatesFromAllThreeEngines() {
        viewModel.attachTarget(session)
        recordTimeline()
        recordCpuProfile()
        takeHeapSnapshot()
        viewModel.refresh()

        val snapshot = viewModel.state
        assertTrue(snapshot.attached)
        assertEquals("session-1", snapshot.sessionId)
        assertEquals(TracingStatus.STOPPED, snapshot.tracingStatus)
        assertEquals(2, snapshot.timeline.eventCount)
        assertEquals(1, snapshot.timeline.longTasks.size)
        assertEquals(CpuProfilerStatus.STOPPED, snapshot.cpuStatus)
        assertEquals(4, snapshot.cpuProfile!!.nodeCount)
        assertEquals(HeapProfilerStatus.STOPPED, snapshot.heapStatus)
        assertTrue(snapshot.heapSnapshot!!.snapshotJson.contains("\"nodes\""))
    }

    @Test
    fun refresh_reportsEmptyStatesBeforePopulated() {
        viewModel.attachTarget(session)
        viewModel.refresh()
        val snapshot = viewModel.state
        assertTrue(snapshot.attached)
        assertEquals(TracingStatus.IDLE, snapshot.tracingStatus)
        assertEquals(0, snapshot.timeline.eventCount)
        assertTrue(snapshot.timeline.longTasks.isEmpty())
        assertNull(snapshot.cpuProfile)
        assertNull(snapshot.heapSnapshot)
    }

    @Test
    fun selectMode_updatesSnapshotMode() {
        viewModel.attachTarget(session)
        assertEquals(ProfilingMode.TIMELINE, viewModel.state.mode)
        viewModel.selectMode(ProfilingMode.CPU_PROFILE)
        assertEquals(ProfilingMode.CPU_PROFILE, viewModel.state.mode)
        assertEquals(ProfilingMode.CPU_PROFILE, viewModel.mode)
        viewModel.selectMode(ProfilingMode.HEAP_SNAPSHOT)
        assertEquals(ProfilingMode.HEAP_SNAPSHOT, viewModel.state.mode)
    }

    // ============ action invocation & error propagation ============

    @Test
    fun startTracing_protocolFailure_propagatesErrorIntoSnapshot() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        viewModel.startTracing { results.add(it) }
        val id = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"start broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TracingActionResult.Failure)
        assertTrue(viewModel.state.tracingError.orEmpty().contains("start broke"))
        assertEquals(TracingStatus.IDLE, viewModel.state.tracingStatus)
    }

    @Test
    fun startTracing_methodUnavailable_propagatesUnsupportedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        viewModel.startTracing { results.add(it) }
        val id = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(viewModel.state.tracingError.orEmpty().contains("Unsupported:"))
    }

    @Test
    fun startCpuProfile_protocolFailure_propagatesErrorIntoSnapshot() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        viewModel.startCpuProfile { results.add(it) }
        val id = parseIdFor("Profiler.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"profiler broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is CpuProfilerActionResult.Failure)
        assertTrue(viewModel.state.cpuError.orEmpty().contains("profiler broke"))
        assertEquals(CpuProfilerStatus.IDLE, viewModel.state.cpuStatus)
    }

    @Test
    fun takeHeapSnapshot_protocolFailure_propagatesErrorIntoSnapshot() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        viewModel.takeHeapSnapshot { results.add(it) }
        val id = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"snapshot broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is HeapProfilerActionResult.Failure)
        assertTrue(viewModel.state.heapError.orEmpty().contains("snapshot broke"))
        assertEquals(HeapProfilerStatus.IDLE, viewModel.state.heapStatus)
    }

    @Test
    fun stopTracing_success_clearsErrorAndPopulatesTimeline() {
        viewModel.attachTarget(session)
        recordTimeline()
        val snapshot = viewModel.state
        assertEquals(TracingStatus.STOPPED, snapshot.tracingStatus)
        assertNull(snapshot.tracingError)
        assertEquals(2, snapshot.timeline.eventCount)
    }

    @Test
    fun noSession_actions_resolveUnavailableIntoSnapshot() {
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        viewModel.startTracing { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is TracingActionResult.Failure)
        assertTrue(viewModel.state.tracingError.orEmpty().contains("Unavailable:"))
        assertFalse(viewModel.state.attached)
    }

    // ============ shutdown ============

    @Test
    fun shutdown_releasesSubscriptionsAcrossAllEngines() {
        viewModel.attachTarget(session)
        viewModel.startTracing {}
        val startId = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { tracing.status == TracingStatus.RECORDING })
        assertEquals(1, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals(1, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))

        viewModel.shutdown()
        assertEquals(0, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals(0, router.subscriptionCount("Tracing.tracingComplete"))
        assertEquals(0, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals(0, router.subscriptionCount("Profiler.consoleProfileFinished"))
        assertEquals(0, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals(0, router.subscriptionCount("HeapProfiler.reportHeapSnapshotProgress"))
    }
}