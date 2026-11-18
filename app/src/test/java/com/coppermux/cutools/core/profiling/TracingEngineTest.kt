package com.coppermux.cutools.core.profiling

import com.coppermux.cutools.core.profiling.models.TraceTimeline
import com.coppermux.cutools.core.profiling.models.TracingActionResult
import com.coppermux.cutools.core.profiling.models.TracingCategory
import com.coppermux.cutools.core.profiling.models.TracingError
import com.coppermux.cutools.core.profiling.models.TracingStatus
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
 * Unit & protocol test suite for Sub-Phase 7A — Tracing Engine & Performance
 * Timeline.
 *
 * Raw CDP `Tracing` commands and `Tracing.dataCollected` / `Tracing.tracingComplete`
 * events are delivered via the fake transport and flow through the classifier →
 * dispatcher → eventRouter → engine, proving serialization, multi-chunk
 * aggregation, metric classification, and the full start → stream → end lifecycle.
 * No-Fake-Capability is enforced: protocol failures map to explicit
 * Unsupported / Unavailable / ExecutionFailed / RecordingInProgress errors.
 */
class TracingEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var router: CdpEventRouter
    private lateinit var engine: TracingEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = TracingEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
    }

    private fun startSuccessfully() {
        engine.startTracing {}
        val id = parseIdFor("Tracing.start")
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

    // ============ payload generation & subscription registration ============

    @Test
    fun startTracing_serializesStreamingParamsAndRegistersSubscriptions() {
        bind()
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        engine.startTracing { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Tracing.start") }
        assertTrue(raw.contains("\"method\":\"Tracing.start\""))
        assertTrue(raw.contains("\"buffering\":\"streaming\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertEquals(1, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals(1, router.subscriptionCount("Tracing.tracingComplete"))

        val id = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TracingActionResult.Success)
        assertEquals(TracingStatus.RECORDING, engine.status)
    }

    @Test
    fun endTracing_serializesSessionAndMethod() {
        bind()
        startSuccessfully()
        engine.endTracing {}
        val raw = transport.sentMessages.last { it.contains("Tracing.end") }
        assertTrue(raw.contains("\"method\":\"Tracing.end\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ full lifecycle: start -> stream chunks -> tracingComplete -> end ============

    @Test
    fun fullLifecycle_aggregatesAllChunksAndClassifiesTimeline() {
        bind()
        startSuccessfully()

        deliverEvent("Tracing.dataCollected", """{"value":[
            {"name":"RunTask","cat":"toplevel","ph":"X","ts":100,"dur":60000,"pid":1,"tid":1},
            {"name":"UpdateLayoutTree","cat":"blink_rendering","ph":"X","ts":200,"dur":12000,"pid":1,"tid":1}
        ]}""")
        deliverEvent("Tracing.dataCollected", """{"value":[
            {"name":"Paint","cat":"blink_rendering","ph":"X","ts":300,"dur":8000,"pid":1,"tid":1},
            {"name":"FunctionCall","cat":"v8","ph":"X","ts":400,"dur":15000,"pid":1,"tid":1}
        ]}""")

        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        deliverEvent("Tracing.tracingComplete", """{"traceId":"trace-1","error":null}""")
        transport.deliverMessage(
            """{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[
                {"name":"mark_app_ready","cat":"user_timing","ph":"N","ts":500,"dur":0,"pid":1,"tid":1}
            ]}}"""
        )

        assertTrue(waitFor { results.size == 1 })
        val result = results[0]
        assertTrue(result is TracingActionResult.Success)
        val timeline = (result as TracingActionResult.Success).value
        assertEquals(TracingStatus.STOPPED, engine.status)
        assertEquals(TracingStatus.STOPPED, timeline.status)
        assertEquals(5, timeline.eventCount)
        assertEquals("session-1", timeline.sessionId)
        assertEquals(timeline, engine.timeline)
    }

    @Test
    fun multiChunkAggregation_combinesChunkAndEndFlushEvents() {
        bind()
        startSuccessfully()
        deliverEvent("Tracing.dataCollected", """{"value":[{"name":"a","cat":"v8","ph":"X","ts":10,"dur":1}]}""")
        deliverEvent("Tracing.dataCollected", """{"value":[{"name":"b","cat":"v8","ph":"X","ts":20,"dur":1},{"name":"c","cat":"v8","ph":"X","ts":30,"dur":1}]}""")
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        transport.deliverMessage(
            """{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[{"name":"d","cat":"v8","ph":"X","ts":40,"dur":1}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val timeline = (results[0] as TracingActionResult.Success).value
        assertEquals(4, timeline.eventCount)
        assertEquals(4, timeline.scriptExecutions.size)
    }

    // ============ metric classification ============

    @Test
    fun metricClassification_slicesLongTasksLayoutPaintScriptAndMarks() {
        bind()
        startSuccessfully()
        deliverEvent("Tracing.dataCollected", """{"value":[
            {"name":"RunTask","cat":"toplevel","ph":"X","ts":100,"dur":60000,"pid":1,"tid":1},
            {"name":"FunctionCall","cat":"v8","ph":"X","ts":200,"dur":9000,"pid":1,"tid":1}
        ]}""")
        deliverEvent("Tracing.dataCollected", """{"value":[
            {"name":"Layout","cat":"blink_rendering","ph":"X","ts":300,"dur":7000,"pid":1,"tid":1},
            {"name":"Paint","cat":"blink_rendering","ph":"X","ts":400,"dur":5000,"pid":1,"tid":1}
        ]}""")
        deliverEvent("Tracing.dataCollected", """{"value":[
            {"name":"mark_checkout","cat":"user_timing","ph":"N","ts":500,"dur":0,"pid":1,"tid":1}
        ]}""")

        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        transport.deliverMessage("""{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[]}}""")
        assertTrue(waitFor { results.size == 1 })

        val timeline = (results[0] as TracingActionResult.Success).value
        assertEquals(1, timeline.longTasks.size)
        assertEquals("RunTask", timeline.longTasks[0].name)
        assertTrue(timeline.longTasks[0].durationMicros > TraceAnalyzer.LONG_TASK_THRESHOLD_MICROS)

        assertEquals(2, timeline.renderingBlocks.size)
        assertEquals(1, timeline.layoutBlocks.size)
        assertEquals("Layout", timeline.layoutBlocks[0].name)
        assertEquals(1, timeline.paintBlocks.size)
        assertEquals("Paint", timeline.paintBlocks[0].name)

        assertEquals(1, timeline.scriptExecutions.size)
        assertEquals("FunctionCall", timeline.scriptExecutions[0].name)
        assertEquals(TracingCategory.V8, timeline.scriptExecutions[0].category)

        assertEquals(1, timeline.userTimingMarks.size)
        assertEquals("mark_checkout", timeline.userTimingMarks[0].name)
        assertEquals(500L, timeline.userTimingMarks[0].tsMicros)
    }

    @Test
    fun longTaskThreshold_marksOnlyEventsOver50ms() {
        bind()
        startSuccessfully()
        deliverEvent("Tracing.dataCollected", """{"value":[
            {"name":"Short","cat":"toplevel","ph":"X","ts":100,"dur":40000},
            {"name":"Boundary","cat":"toplevel","ph":"X","ts":200,"dur":50000},
            {"name":"Over","cat":"toplevel","ph":"X","ts":300,"dur":50001}
        ]}""")
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        transport.deliverMessage("""{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[]}}""")
        assertTrue(waitFor { results.size == 1 })
        val timeline = (results[0] as TracingActionResult.Success).value
        assertEquals(1, timeline.longTasks.size)
        assertEquals("Over", timeline.longTasks[0].name)
    }

    @Test
    fun dataCollected_fromOtherSession_isIgnored() {
        bind()
        startSuccessfully()
        transport.deliverMessage(
            """{"method":"Tracing.dataCollected","sessionId":"session-OTHER","params":{"value":[{"name":"x","cat":"v8","ph":"X","ts":1,"dur":1}]}}"""
        )
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        transport.deliverMessage("""{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[]}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(0, (results[0] as TracingActionResult.Success).value.eventCount)
    }

    // ============ error mapping ============

    @Test
    fun startTracing_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        engine.startTracing { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as TracingActionResult.Failure
        assertTrue(failure.error is TracingError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun startTracing_protocolError_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        engine.startTracing { results.add(it) }
        val id = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"start broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as TracingActionResult.Failure
        assertTrue(failure.error is TracingError.ExecutionFailed)
        assertTrue((failure.error as TracingError.ExecutionFailed).cause.message.orEmpty().contains("start broke"))
        assertEquals(TracingStatus.IDLE, engine.status)
    }

    @Test
    fun startTracing_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        engine.startTracing { results.add(it) }
        val id = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as TracingActionResult.Failure
        assertTrue(failure.error is TracingError.Unsupported)
        assertEquals(TracingStatus.IDLE, engine.status)
    }

    @Test
    fun startTracing_whileRecording_mapsToRecordingInProgress() {
        bind()
        startSuccessfully()
        assertEquals(TracingStatus.RECORDING, engine.status)
        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<TracingActionResult<Unit>>()
        engine.startTracing { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as TracingActionResult.Failure
        assertTrue(failure.error is TracingError.RecordingInProgress)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun endTracing_withoutRecording_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as TracingActionResult.Failure
        assertTrue(failure.error is TracingError.ExecutionFailed)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun endTracing_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as TracingActionResult.Failure
        assertTrue(failure.error is TracingError.Unavailable)
    }

    @Test
    fun endTracing_protocolError_mapsToExecutionFailed() {
        bind()
        startSuccessfully()
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val id = parseIdFor("Tracing.end")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"end broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as TracingActionResult.Failure
        assertTrue(failure.error is TracingError.ExecutionFailed)
        assertEquals(TracingStatus.IDLE, engine.status)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsBufferSubscriptionsAndState() {
        bind()
        startSuccessfully()
        deliverEvent("Tracing.dataCollected", """{"value":[{"name":"a","cat":"v8","ph":"X","ts":1,"dur":1}]}""")
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        transport.deliverMessage("""{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[]}}""")
        assertTrue(waitFor { engine.timeline.eventCount == 1 })
        assertEquals(1, router.subscriptionCount("Tracing.dataCollected"))

        engine.detach()
        assertNull(engine.session)
        assertEquals(TracingStatus.IDLE, engine.status)
        assertEquals(0, engine.timeline.eventCount)
        assertEquals("no event-listener leak", 0, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals("no event-listener leak", 0, router.subscriptionCount("Tracing.tracingComplete"))
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        startSuccessfully()
        deliverEvent("Tracing.dataCollected", """{"value":[{"name":"a","cat":"v8","ph":"X","ts":1,"dur":1}]}""")
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")
        transport.deliverMessage("""{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[]}}""")
        assertTrue(waitFor { engine.timeline.eventCount == 1 })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertEquals(TracingStatus.IDLE, engine.status)
        assertEquals(0, engine.timeline.eventCount)
        assertEquals(0, router.subscriptionCount("Tracing.dataCollected"))
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        startSuccessfully()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertEquals(TracingStatus.IDLE, engine.status)
        assertEquals(0, router.subscriptionCount("Tracing.dataCollected"))
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        startSuccessfully()
        val results = CopyOnWriteArrayList<TracingActionResult<TraceTimeline>>()
        engine.endTracing { results.add(it) }
        val endId = parseIdFor("Tracing.end")

        engine.detach()
        transport.deliverMessage(
            """{"id":$endId,"sessionId":"session-1","result":{"dataCollected":[{"name":"x","cat":"v8","ph":"X","ts":1,"dur":1}]}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertEquals(0, engine.timeline.eventCount)
        assertNull(engine.session)
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        bind()
        startSuccessfully()
        assertEquals(1, router.subscriptionCount("Tracing.dataCollected"))

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        engine.startTracing {}
        val id = parseIdFor("Tracing.start")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-3","result":{}}""")
        assertTrue(waitFor { engine.status == TracingStatus.RECORDING })

        assertEquals("rebinding must not leak listeners", 1, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals("rebinding must not leak listeners", 1, router.subscriptionCount("Tracing.tracingComplete"))
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        bind()
        startSuccessfully()
        assertEquals(1, router.subscriptionCount("Tracing.dataCollected"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("Tracing.dataCollected"))
        assertEquals(0, router.subscriptionCount("Tracing.tracingComplete"))
    }

    // ============ model-level mapping ============

    @Test
    fun tracingCategory_fromWire_mapsKnownValuesAndFallsBackToOther() {
        assertEquals(TracingCategory.TIMELINE, TracingCategory.fromWire("toplevel"))
        assertEquals(TracingCategory.V8, TracingCategory.fromWire("v8"))
        assertEquals(TracingCategory.BLINK_RENDERING, TracingCategory.fromWire("blink_rendering"))
        assertEquals(TracingCategory.USER_TIMING, TracingCategory.fromWire("user_timing"))
        assertEquals(TracingCategory.OTHER, TracingCategory.fromWire(null))
        assertEquals(TracingCategory.OTHER, TracingCategory.fromWire("no_such_category"))
    }

    @Test
    fun tracingActionResult_successAndFailureCarryExpectedValues() {
        val success = TracingActionResult.Success("data")
        assertEquals("data", success.value)

        val unsupported = TracingActionResult.Failure(TracingError.Unsupported("nope"))
        assertTrue((unsupported.error as TracingError.Unsupported).reason == "nope")

        val unavailable = TracingActionResult.Failure(TracingError.Unavailable("no session"))
        assertTrue((unavailable.error as TracingError.Unavailable).detail == "no session")

        val failed = TracingActionResult.Failure(TracingError.ExecutionFailed(RuntimeException("boom")))
        assertTrue((failed.error as TracingError.ExecutionFailed).cause.message == "boom")

        val inProgress = TracingActionResult.Failure(TracingError.RecordingInProgress())
        assertTrue(inProgress.error is TracingError.RecordingInProgress)
    }

    @Test
    fun traceAnalyzer_parse_handlesMissingFieldsSafely() {
        val parsed = TraceAnalyzer.parse(mapOf("name" to "x"))
        assertTrue(parsed != null)
        assertEquals("x", parsed!!.name)
        assertNull(parsed.ts)
        assertNull(parsed.dur)
        assertNull(TraceAnalyzer.parse(null))
        assertNull(TraceAnalyzer.parse(listOf("not a map")))
    }
}