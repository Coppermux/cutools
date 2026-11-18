package com.coppermux.cutools.core.profiling

import com.coppermux.cutools.core.model.Protocol
import com.coppermux.cutools.core.profiling.models.CpuProfileModel
import com.coppermux.cutools.core.profiling.models.CpuProfileTiming
import com.coppermux.cutools.core.profiling.models.CpuProfilerActionResult
import com.coppermux.cutools.core.profiling.models.CpuProfilerError
import com.coppermux.cutools.core.profiling.models.CpuProfilerStatus
import com.coppermux.cutools.core.profiling.models.CpuNodeTiming
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 7B — JS CPU Profiler (Profiler Domain).
 *
 * Raw CDP `Profiler` commands and `Profiler.consoleProfileStarted` /
 * `Profiler.consoleProfileFinished` events are delivered via the fake transport
 * and flow through the analyzer → dispatcher → eventRouter → engine, proving
 * serialization, call-tree self-time / total-time derivation, console-profile
 * event tracking, and the full enable → start → stop lifecycle. No-Fake-Capability
 * is enforced: protocol failures map to explicit Unsupported / Unavailable /
 * ExecutionFailed / ProfilingInProgress errors.
 */
class CpuProfilerEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var router: CdpEventRouter
    private lateinit var engine: CpuProfilerEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = CpuProfilerEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
    }

    private fun enableSuccessfully() {
        engine.enable {}
        val id = parseIdFor("Profiler.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
    }

    private fun startSuccessfully() {
        engine.startProfiling {}
        val id = parseIdFor("Profiler.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
    }

    private fun deliverEvent(method: String, paramsJson: String) {
        transport.deliverMessage("""{"method":"$method","sessionId":"session-1","params":$paramsJson}""")
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

    /** A small deterministic profile used across lifecycle / timing tests. */
    private fun sampleProfileJson(): String = """{
        "startTime":1000,
        "endTime":21000,
        "nodes":[
            {"id":1,"functionName":"root","scriptId":"1","url":"app.js","lineNumber":0,"columnNumber":0,"hitCount":0,"children":[2,3]},
            {"id":2,"functionName":"foo","scriptId":"1","url":"app.js","lineNumber":1,"columnNumber":2,"hitCount":0,"children":[]},
            {"id":3,"functionName":"bar","scriptId":"1","url":"app.js","lineNumber":2,"columnNumber":2,"hitCount":0,"children":[4]},
            {"id":4,"functionName":"baz","scriptId":"1","url":"app.js","lineNumber":3,"columnNumber":2,"hitCount":0,"children":[]}
        ],
        "samples":[2,2,3,4,4,2],
        "timeDeltas":[1000,2000,3000,4000,5000,6000]
    }"""

    // ============ payload generation & subscription registration ============

    @Test
    fun startProfiling_serializesMethodAndSessionAndRegistersSubscriptions() {
        bind()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.startProfiling { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Profiler.start") }
        assertTrue(raw.contains("\"method\":\"Profiler.start\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertEquals(1, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals(1, router.subscriptionCount("Profiler.consoleProfileFinished"))

        val id = parseIdFor("Profiler.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is CpuProfilerActionResult.Success)
        assertEquals(CpuProfilerStatus.PROFILING, engine.status)
    }

    @Test
    fun enable_serializesMethodAndSession() {
        bind()
        engine.enable {}
        val raw = transport.sentMessages.last { it.contains("Profiler.enable") }
        assertTrue(raw.contains("\"method\":\"Profiler.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesMethodAndSession() {
        bind()
        engine.disable {}
        val raw = transport.sentMessages.last { it.contains("Profiler.disable") }
        assertTrue(raw.contains("\"method\":\"Profiler.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ full lifecycle: enable -> start -> stop -> extraction ============

    @Test
    fun fullLifecycle_enableStartStop_extractsProfileWithNodeTimings() {
        bind()
        enableSuccessfully()
        assertEquals(CpuProfilerStatus.IDLE, engine.status)

        val startResults = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.startProfiling { startResults.add(it) }
        val startId = parseIdFor("Profiler.start")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { startResults.size == 1 })
        assertEquals(CpuProfilerStatus.PROFILING, engine.status)

        val stopResults = CopyOnWriteArrayList<CpuProfilerActionResult<CpuProfileTiming>>()
        engine.stopProfiling { stopResults.add(it) }
        val stopId = parseIdFor("Profiler.stop")
        transport.deliverMessage(
            """{"id":$stopId,"sessionId":"session-1","result":{"profile":${sampleProfileJson()}}}"""
        )
        assertTrue(waitFor { stopResults.size == 1 })
        assertTrue(stopResults[0] is CpuProfilerActionResult.Success)
        val timing = (stopResults[0] as CpuProfilerActionResult.Success).value
        assertEquals(CpuProfilerStatus.STOPPED, engine.status)
        assertEquals(CpuProfilerStatus.STOPPED, timing.status)
        assertEquals("session-1", timing.sessionId)
        assertEquals(4, timing.nodeCount)
        assertEquals(20000.0, timing.duration, 0.001)
        assertEquals(timing, engine.profile)
    }

    // ============ call-tree self-time / total-time & duration ============

    @Test
    fun callTree_computesSelfTimeAndTotalTime() {
        val profile = CpuProfileAnalyzer.parseProfile(
            Protocol.fromJsonValue<Any>(sampleProfileJson())
        )!!
        val timing = CpuProfileAnalyzer.analyze(profile)

        val byId = timing.timings.associateBy { it.nodeId }
        // self-times
        assertEquals(9000.0, byId.getValue(2L).selfTime, 0.001)
        assertEquals(3000.0, byId.getValue(3L).selfTime, 0.001)
        assertEquals(9000.0, byId.getValue(4L).selfTime, 0.001)
        assertEquals(0.0, byId.getValue(1L).selfTime, 0.001)
        // total-times (inclusive)
        assertEquals(9000.0, byId.getValue(2L).totalTime, 0.001)
        assertEquals(12000.0, byId.getValue(3L).totalTime, 0.001)
        assertEquals(9000.0, byId.getValue(4L).totalTime, 0.001)
        assertEquals(21000.0, byId.getValue(1L).totalTime, 0.001)
    }

    @Test
    fun totalDuration_isDerivedFromStartAndEndTime() {
        val profile = CpuProfileAnalyzer.parseProfile(
            Protocol.fromJsonValue<Any>(sampleProfileJson())
        )!!
        assertEquals(20000.0, CpuProfileAnalyzer.analyze(profile).duration, 0.001)
    }

    @Test
    fun analyzer_emptyProfile_hasZeroDurationAndNoTimings() {
        val timing = CpuProfileAnalyzer.analyze(CpuProfileModel())
        assertEquals(0.0, timing.duration, 0.001)
        assertTrue(timing.timings.isEmpty())
        assertEquals(0, timing.nodeCount)
    }

    @Test
    fun analyzer_nodeWithoutChildren_stillCountsItsOwnSamples() {
        val profile = CpuProfileAnalyzer.parseProfile(Protocol.fromJsonValue<Any>("""{
            "startTime":0,"endTime":3000,
            "nodes":[{"id":7,"functionName":"leaf","children":[]}],
            "samples":[7,7],
            "timeDeltas":[1000,2000]
        }"""))!!
        val timing = CpuProfileAnalyzer.analyze(profile)
        assertEquals(3000.0, timing.duration, 0.001)
        val timingNode = timing.timings.single { it.nodeId == 7L }
        assertEquals(3000.0, timingNode.selfTime, 0.001)
        assertEquals(3000.0, timingNode.totalTime, 0.001)
    }

    // ============ console-profile event tracking ============

    @Test
    fun consoleProfileStarted_setsActiveFlag() {
        bind()
        assertEquals(false, engine.consoleProfilingActive)
        deliverEvent("Profiler.consoleProfileStarted", """{"id":"console-1"}""")
        assertEquals(true, engine.consoleProfilingActive)
    }

    @Test
    fun consoleProfileFinished_clearsFlagAndTransitionsProfiling() {
        bind()
        startSuccessfully()
        assertEquals(CpuProfilerStatus.PROFILING, engine.status)
        deliverEvent("Profiler.consoleProfileStarted", """{"id":"console-1"}""")
        assertEquals(true, engine.consoleProfilingActive)
        deliverEvent("Profiler.consoleProfileFinished", """{"id":"console-1"}""")
        assertEquals(false, engine.consoleProfilingActive)
        assertEquals(CpuProfilerStatus.PROCESSING, engine.status)
    }

    @Test
    fun consoleProfileEvents_fromOtherSession_areIgnored() {
        bind()
        transport.deliverMessage(
            """{"method":"Profiler.consoleProfileStarted","sessionId":"session-OTHER","params":{"id":"console-9"}}"""
        )
        assertEquals(false, engine.consoleProfilingActive)
        transport.deliverMessage(
            """{"method":"Profiler.consoleProfileFinished","sessionId":"session-OTHER","params":{"id":"console-9"}}"""
        )
        assertEquals(false, engine.consoleProfilingActive)
    }

    // ============ error mapping ============

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as CpuProfilerActionResult.Failure).error is CpuProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as CpuProfilerActionResult.Failure).error is CpuProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun startProfiling_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.startProfiling { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as CpuProfilerActionResult.Failure).error is CpuProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopProfiling_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<CpuProfileTiming>>()
        engine.stopProfiling { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as CpuProfilerActionResult.Failure).error is CpuProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun startProfiling_protocolError_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.startProfiling { results.add(it) }
        val id = parseIdFor("Profiler.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"start broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as CpuProfilerActionResult.Failure
        assertTrue(failure.error is CpuProfilerError.ExecutionFailed)
        assertTrue((failure.error as CpuProfilerError.ExecutionFailed).cause.message.orEmpty().contains("start broke"))
        assertEquals(CpuProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun startProfiling_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.startProfiling { results.add(it) }
        val id = parseIdFor("Profiler.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as CpuProfilerActionResult.Failure
        assertTrue(failure.error is CpuProfilerError.Unsupported)
        assertEquals(CpuProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun startProfiling_whileProfiling_mapsToProfilingInProgress() {
        bind()
        startSuccessfully()
        assertEquals(CpuProfilerStatus.PROFILING, engine.status)
        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<Unit>>()
        engine.startProfiling { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as CpuProfilerActionResult.Failure
        assertTrue(failure.error is CpuProfilerError.ProfilingInProgress)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopProfiling_withoutActiveSession_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<CpuProfileTiming>>()
        engine.stopProfiling { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as CpuProfilerActionResult.Failure
        assertTrue(failure.error is CpuProfilerError.ExecutionFailed)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopProfiling_protocolError_mapsToExecutionFailed() {
        bind()
        startSuccessfully()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<CpuProfileTiming>>()
        engine.stopProfiling { results.add(it) }
        val id = parseIdFor("Profiler.stop")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"stop broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as CpuProfilerActionResult.Failure
        assertTrue(failure.error is CpuProfilerError.ExecutionFailed)
        assertEquals(CpuProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun stopProfiling_malformedProfile_mapsToExecutionFailed() {
        bind()
        startSuccessfully()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<CpuProfileTiming>>()
        engine.stopProfiling { results.add(it) }
        val id = parseIdFor("Profiler.stop")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"profile":{"no":"nodes"}}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as CpuProfilerActionResult.Failure
        assertTrue(failure.error is CpuProfilerError.ExecutionFailed)
        assertEquals(CpuProfilerStatus.IDLE, engine.status)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndSubscriptions() {
        bind()
        startSuccessfully()
        deliverEvent("Profiler.consoleProfileStarted", """{"id":"console-1"}""")
        assertEquals(true, engine.consoleProfilingActive)
        assertEquals(1, router.subscriptionCount("Profiler.consoleProfileStarted"))

        engine.detach()
        assertNull(engine.session)
        assertEquals(CpuProfilerStatus.IDLE, engine.status)
        assertNull(engine.profile)
        assertEquals(false, engine.consoleProfilingActive)
        assertEquals("no event-listener leak", 0, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals("no event-listener leak", 0, router.subscriptionCount("Profiler.consoleProfileFinished"))
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        startSuccessfully()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<CpuProfileTiming>>()
        engine.stopProfiling { results.add(it) }
        val stopId = parseIdFor("Profiler.stop")
        transport.deliverMessage(
            """{"id":$stopId,"sessionId":"session-1","result":{"profile":${sampleProfileJson()}}}"""
        )
        assertTrue(waitFor { engine.profile?.nodeCount == 4 })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertEquals(CpuProfilerStatus.IDLE, engine.status)
        assertNull(engine.profile)
        assertEquals(false, engine.consoleProfilingActive)
        assertEquals("rebind re-subscribes once for the new session", 1, router.subscriptionCount("Profiler.consoleProfileStarted"))
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        startSuccessfully()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertEquals(CpuProfilerStatus.IDLE, engine.status)
        assertEquals(0, router.subscriptionCount("Profiler.consoleProfileStarted"))
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        startSuccessfully()
        val results = CopyOnWriteArrayList<CpuProfilerActionResult<CpuProfileTiming>>()
        engine.stopProfiling { results.add(it) }
        val stopId = parseIdFor("Profiler.stop")

        engine.detach()
        transport.deliverMessage(
            """{"id":$stopId,"sessionId":"session-1","result":{"profile":${sampleProfileJson()}}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.profile)
        assertNull(engine.session)
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        bind()
        assertEquals(1, router.subscriptionCount("Profiler.consoleProfileStarted"))

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        assertEquals("rebinding must not leak listeners", 1, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals("rebinding must not leak listeners", 1, router.subscriptionCount("Profiler.consoleProfileFinished"))
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        bind()
        assertEquals(1, router.subscriptionCount("Profiler.consoleProfileStarted"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("Profiler.consoleProfileStarted"))
        assertEquals(0, router.subscriptionCount("Profiler.consoleProfileFinished"))
    }

    // ============ model-level mapping ============

    @Test
    fun cpuProfilerActionResult_successAndFailureCarryExpectedValues() {
        val success = CpuProfilerActionResult.Success("data")
        assertEquals("data", success.value)

        val unsupported = CpuProfilerActionResult.Failure(CpuProfilerError.Unsupported("nope"))
        assertTrue((unsupported.error as CpuProfilerError.Unsupported).reason == "nope")

        val unavailable = CpuProfilerActionResult.Failure(CpuProfilerError.Unavailable("no session"))
        assertTrue((unavailable.error as CpuProfilerError.Unavailable).detail == "no session")

        val failed = CpuProfilerActionResult.Failure(CpuProfilerError.ExecutionFailed(RuntimeException("boom")))
        assertTrue((failed.error as CpuProfilerError.ExecutionFailed).cause.message == "boom")

        val inProgress = CpuProfilerActionResult.Failure(CpuProfilerError.ProfilingInProgress("busy"))
        assertTrue((inProgress.error as CpuProfilerError.ProfilingInProgress).detail == "busy")
    }

    @Test
    fun cpuProfileAnalyzer_parse_handlesMissingFieldsSafely() {
        val node = CpuProfileAnalyzer.parseNode(mapOf("id" to 5, "functionName" to "f"))
        assertTrue(node != null)
        assertEquals(5L, node!!.id)
        assertEquals("f", node.functionName)
        assertNull(node.url)
        assertTrue(node.children.isEmpty())
        assertNull(CpuProfileAnalyzer.parseNode(null))
        assertNull(CpuProfileAnalyzer.parseNode(listOf("not a map")))
        assertNull(CpuProfileAnalyzer.parseNode(mapOf("functionName" to "no id")))

        val profile = CpuProfileAnalyzer.parseProfile(Protocol.fromJsonValue<Any>("""{"nodes":[{"id":1}]}"""))
        assertTrue(profile != null)
        assertEquals(1, profile!!.nodes.size)
        assertTrue(profile.samples.isEmpty())
        assertTrue(profile.timeDeltas.isEmpty())
        assertNull(CpuProfileAnalyzer.parseProfile(null))
        assertNull(CpuProfileAnalyzer.parseProfile(mapOf("startTime" to 1.0)))
    }
}
