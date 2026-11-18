package com.coppermux.cutools.core.memory

import com.coppermux.cutools.core.memory.models.DomCountersModel
import com.coppermux.cutools.core.memory.models.HeapSamplingNodeModel
import com.coppermux.cutools.core.memory.models.HeapSamplingProfileModel
import com.coppermux.cutools.core.memory.models.MemoryActionResult
import com.coppermux.cutools.core.memory.models.MemoryCapabilityStatus
import com.coppermux.cutools.core.memory.models.MemoryError
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
 * Unit & protocol test suite for Phase 11A — Memory & Heap Profiler Core Engine
 * (`HeapProfiler` + `Memory` domains).
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving command serialization,
 * genuine `HeapProfiler.addHeapSnapshotChunk` streaming into the
 * [heapSnapshotStream] StateFlow, sampling profile hierarchy parsing, and strict
 * `Unsupported` / `Unavailable` / `TargetDisconnected` / `ExecutionFailed` error
 * mapping in the same pipeline the app uses against Chrome. No-Fake-Capability
 * is enforced: never a fabricated DOM counter or heap profile.
 */
class MemoryEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: MemoryEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = MemoryEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
    }

    private fun startSnapshotSuccessfully() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.takeHeapSnapshot { results.add(it) }
        val id = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == MemoryEngineStatus.TAKING_SNAPSHOT })
    }

    private fun startSamplingSuccessfully() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.startSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == MemoryEngineStatus.SAMPLING })
    }

    private fun deliverChunk(chunk: String) {
        transport.deliverMessage(
            """{"method":"HeapProfiler.addHeapSnapshotChunk","sessionId":"session-1","params":{"chunk":${jsonString(chunk)}}}"""
        )
    }

    private fun deliverProgress(finished: Boolean) {
        transport.deliverMessage(
            """{"method":"HeapProfiler.reportHeapSnapshotProgress","sessionId":"session-1","params":{"done":1,"total":2,"finished":$finished}}"""
        )
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun samplingProfileJson(): String = """{
        "head":{
            "id":1,
            "selfSize":0,
            "callFrame":{"functionName":"root","scriptId":"1","url":"app.js","lineNumber":0,"columnNumber":0},
            "children":[
                {"id":2,"selfSize":400,"callFrame":{"functionName":"foo","scriptId":"1","url":"app.js","lineNumber":1,"columnNumber":2},"children":[]},
                {"id":3,"selfSize":800,"callFrame":{"functionName":"bar","scriptId":"1","url":"app.js","lineNumber":2,"columnNumber":2},"children":[]}
            ]
        },
        "samples":[2,3,2]
    }"""

    // ============ payload generation & subscription registration ============

    @Test
    fun takeHeapSnapshot_serializesMethodParamsAndRegistersSubscriptions() {
        bind()
        engine.takeHeapSnapshot(reportProgress = true) { }
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.takeHeapSnapshot") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.takeHeapSnapshot\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"reportProgress\":true"))
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals(1, router.subscriptionCount("HeapProfiler.reportHeapSnapshotProgress"))
    }

    @Test
    fun takeHeapSnapshot_omitsReportProgressWhenNotSupplied() {
        bind()
        engine.takeHeapSnapshot(reportProgress = null) { }
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.takeHeapSnapshot") }
        assertTrue(!raw.contains("reportProgress"))
    }

    @Test
    fun enable_serializesMethodAndSession() {
        bind()
        engine.enable {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.enable") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesMethodAndSession() {
        bind()
        engine.disable {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.disable") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun collectGarbage_serializesMethodAndSession() {
        bind()
        engine.collectGarbage {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.collectGarbage") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.collectGarbage\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun startSampling_serializesMethodParamsAndSession() {
        bind()
        engine.startSampling(samplingIntervalBytes = 512.0) {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.startSampling") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.startSampling\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"samplingInterval\":512.0"))
    }

    @Test
    fun startSampling_omitsIntervalWhenNotSupplied() {
        bind()
        engine.startSampling {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.startSampling") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.startSampling\""))
        assertTrue(!raw.contains("samplingInterval"))
    }

    @Test
    fun getDOMCounters_serializesMethodAndSession() {
        bind()
        engine.getDOMCounters {}
        val raw = transport.sentMessages.last { it.contains("Memory.getDOMCounters") }
        assertTrue(raw.contains("\"method\":\"Memory.getDOMCounters\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun prepareForLeakDetection_serializesMethodAndSession() {
        bind()
        engine.prepareForLeakDetection {}
        val raw = transport.sentMessages.last { it.contains("Memory.prepareForLeakDetection") }
        assertTrue(raw.contains("\"method\":\"Memory.prepareForLeakDetection\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ chunk streaming & snapshot lifecycle ============

    @Test
    fun takeHeapSnapshot_streamsChunksIntoStateFlow_andCompletesOnProgress() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == MemoryEngineStatus.TAKING_SNAPSHOT })
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)

        assertEquals("", engine.heapSnapshotStream.value)
        deliverChunk("""{"a":""")
        assertEquals("""{"a":""", engine.heapSnapshotStream.value)
        deliverChunk("1,")
        assertEquals("""{"a":1,""" , engine.heapSnapshotStream.value)
        deliverChunk(""""b":2}""")
        assertEquals("""{"a":1,"b":2}""", engine.heapSnapshotStream.value)
        assertEquals(MemoryEngineStatus.TAKING_SNAPSHOT, engine.status)

        deliverProgress(finished = false)
        assertEquals(MemoryEngineStatus.TAKING_SNAPSHOT, engine.status)
        deliverProgress(finished = true)
        assertEquals(MemoryEngineStatus.STOPPED, engine.status)
    }

    @Test
    fun chunkStream_ignoresChunksFromOtherSessions() {
        startSnapshotSuccessfully()
        transport.deliverMessage(
            """{"method":"HeapProfiler.addHeapSnapshotChunk","sessionId":"session-OTHER","params":{"chunk":"junk"}}"""
        )
        Thread.sleep(100)
        assertEquals("", engine.heapSnapshotStream.value)
        assertEquals(MemoryEngineStatus.TAKING_SNAPSHOT, engine.status)
    }

    @Test
    fun chunkEvent_malformedParams_isTolerated() {
        startSnapshotSuccessfully()
        transport.deliverMessage(
            """{"method":"HeapProfiler.addHeapSnapshotChunk","sessionId":"session-1","params":"not-a-map"}"""
        )
        Thread.sleep(100)
        assertEquals("", engine.heapSnapshotStream.value)
        assertEquals(MemoryEngineStatus.TAKING_SNAPSHOT, engine.status)
    }

    // ============ sampling profile parsing ============

    @Test
    fun sampling_fullLifecycle_startThenStop_returnsProfile() {
        bind()
        val startResults = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.startSampling { startResults.add(it) }
        val startId = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { startResults.size == 1 })
        assertTrue(startResults[0] is MemoryActionResult.Success)
        assertEquals(MemoryEngineStatus.SAMPLING, engine.status)

        val stopResults = CopyOnWriteArrayList<MemoryActionResult<HeapSamplingProfileModel>>()
        engine.stopSampling { stopResults.add(it) }
        val stopId = parseIdFor("HeapProfiler.stopSampling")
        transport.deliverMessage(
            """{"id":$stopId,"sessionId":"session-1","result":{"profile":${samplingProfileJson()}}}"""
        )
        assertTrue(waitFor { stopResults.size == 1 })
        assertTrue(stopResults[0] is MemoryActionResult.Success)
        val profile = (stopResults[0] as MemoryActionResult.Success).data
        assertEquals(MemoryEngineStatus.STOPPED, engine.status)
        assertEquals(3, profile.samplesCount)
        assertEquals(2, profile.head!!.children.size)
    }

    @Test
    fun stopSampling_parsesHeadHierarchyAndSelfSizes() {
        val profile = MemoryParser.parseSamplingProfile(
            java.util.HashMap<String, Any?>().apply {
                put("head", java.util.HashMap<String, Any?>().apply {
                    put("id", 1)
                    put("selfSize", 0)
                    put("callFrame", java.util.HashMap<String, Any?>().apply {
                        put("functionName", "root"); put("scriptId", "1"); put("url", "app.js")
                        put("lineNumber", 0); put("columnNumber", 0)
                    })
                    put("children", listOf<Any?>(
                        java.util.HashMap<String, Any?>().apply {
                            put("id", 2); put("selfSize", 400)
                            put("callFrame", java.util.HashMap<String, Any?>().apply {
                                put("functionName", "foo"); put("url", "app.js"); put("lineNumber", 1); put("columnNumber", 2)
                            })
                            put("children", listOf<Any?>())
                        }
                    ))
                })
                put("samples", listOf<Any?>(2, 2))
            }
        )!!
        val head = profile.head!!
        assertEquals(1, head.id)
        assertEquals("root", head.callFrameFunction)
        assertEquals(0L, head.selfSize)
        assertEquals(1, head.children.size)
        val child = head.children[0]
        assertEquals(2, child.id)
        assertEquals("foo", child.callFrameFunction)
        assertEquals("app.js", child.url)
        assertEquals(1, child.lineNumber)
        assertEquals(2, child.columnNumber)
        assertEquals(400L, child.selfSize)
        assertTrue(child.children.isEmpty())
        assertEquals(2, profile.samplesCount)
    }

    // ============ DOM counters parsing ============

    @Test
    fun getDOMCounters_success_parsesCounters() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        engine.getDOMCounters { results.add(it) }
        val id = parseIdFor("Memory.getDOMCounters")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"documents":4,"nodes":100,"jsEventListeners":25}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)
        val counters = (results[0] as MemoryActionResult.Success).data
        assertEquals(4, counters.documents)
        assertEquals(100, counters.nodes)
        assertEquals(25, counters.jsEventListeners)
        assertEquals(MemoryCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getDOMCounters_missingFields_defaultToZero() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        engine.getDOMCounters { results.add(it) }
        val id = parseIdFor("Memory.getDOMCounters")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        val counters = (results[0] as MemoryActionResult.Success).data
        assertEquals(0, counters.documents)
        assertEquals(0, counters.nodes)
        assertEquals(0, counters.jsEventListeners)
    }

    @Test
    fun getDOMCounters_nonObjectResult_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        engine.getDOMCounters { results.add(it) }
        val id = parseIdFor("Memory.getDOMCounters")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":42}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertEquals(MemoryCapabilityStatus.FAILED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(MemoryCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun collectGarbage_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.collectGarbage { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun takeHeapSnapshot_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.takeHeapSnapshot { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun startSampling_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.startSampling { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopSampling_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<HeapSamplingProfileModel>>()
        engine.stopSampling { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getDOMCounters_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        engine.getDOMCounters { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun prepareForLeakDetection_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.prepareForLeakDetection { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun collectGarbage_protocolError_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.collectGarbage { results.add(it) }
        val id = parseIdFor("HeapProfiler.collectGarbage")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"gc broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertTrue((failure.error as MemoryError.ExecutionFailed).message.contains("gc broke"))
        assertEquals(MemoryCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getDOMCounters_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        engine.getDOMCounters { results.add(it) }
        val id = parseIdFor("Memory.getDOMCounters")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error === MemoryError.Unsupported)
        assertEquals(MemoryCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun takeHeapSnapshot_protocolError_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.takeHeapSnapshot { results.add(it) }
        val id = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"snapshot broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertEquals(MemoryEngineStatus.IDLE, engine.status)
        assertEquals("", engine.heapSnapshotStream.value)
    }

    @Test
    fun takeHeapSnapshot_whileSnapshotInProgress_mapsToExecutionFailed() {
        startSnapshotSuccessfully()
        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.takeHeapSnapshot { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun startSampling_whileSampling_mapsToExecutionFailed() {
        startSamplingSuccessfully()
        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.startSampling { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopSampling_withoutActiveSampling_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<HeapSamplingProfileModel>>()
        engine.stopSampling { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopSampling_protocolError_mapsToExecutionFailed() {
        startSamplingSuccessfully()
        val results = CopyOnWriteArrayList<MemoryActionResult<HeapSamplingProfileModel>>()
        engine.stopSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.stopSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"stop broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertEquals(MemoryEngineStatus.IDLE, engine.status)
    }

    @Test
    fun stopSampling_malformedProfile_mapsToExecutionFailed() {
        startSamplingSuccessfully()
        val results = CopyOnWriteArrayList<MemoryActionResult<HeapSamplingProfileModel>>()
        engine.stopSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.stopSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"profile":{"no":"head"}}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as MemoryActionResult.Failure
        assertTrue(failure.error is MemoryError.ExecutionFailed)
        assertEquals(MemoryEngineStatus.IDLE, engine.status)
    }

    @Test
    fun getDOMCounters_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        engine.getDOMCounters { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as MemoryActionResult.Failure).error === MemoryError.TargetDisconnected)
    }

    // ============ domain success ============

    @Test
    fun enable_success_setsCapability() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.enable { results.add(it) }
        val id = parseIdFor("HeapProfiler.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)
        assertEquals(MemoryCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun collectGarbage_success_resolvesSuccess() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.collectGarbage { results.add(it) }
        val id = parseIdFor("HeapProfiler.collectGarbage")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)
    }

    @Test
    fun prepareForLeakDetection_success_resolvesSuccess() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.prepareForLeakDetection { results.add(it) }
        val id = parseIdFor("Memory.prepareForLeakDetection")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndSubscriptions() {
        startSnapshotSuccessfully()
        deliverChunk("""{"x":1}""")
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals("""{"x":1}""", engine.heapSnapshotStream.value)

        engine.detach()
        assertNull(engine.session)
        assertEquals(MemoryEngineStatus.IDLE, engine.status)
        assertNull(engine.capability)
        assertEquals("", engine.heapSnapshotStream.value)
        assertEquals("no event-listener leak", 0, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals("no event-listener leak", 0, router.subscriptionCount("HeapProfiler.reportHeapSnapshotProgress"))
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        startSnapshotSuccessfully()
        deliverChunk("""{"x":1}""")
        deliverProgress(finished = true)
        assertTrue(waitFor { engine.status == MemoryEngineStatus.STOPPED })
        assertEquals("""{"x":1}""", engine.heapSnapshotStream.value)

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertEquals(MemoryEngineStatus.IDLE, engine.status)
        assertNull(engine.capability)
        assertEquals("", engine.heapSnapshotStream.value)
        assertEquals("rebind re-subscribes once for the new session", 1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        startSnapshotSuccessfully()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertEquals(MemoryEngineStatus.IDLE, engine.status)
        assertEquals(0, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
    }

    @Test
    fun staleCompletionAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        engine.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == MemoryEngineStatus.TAKING_SNAPSHOT })
        deliverChunk("""{"x":1}""")

        engine.detach()
        deliverProgress(finished = true)
        Thread.sleep(100)
        assertNull(engine.session)
        assertEquals(MemoryEngineStatus.IDLE, engine.status)
        assertEquals("", engine.heapSnapshotStream.value)
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        bind()
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        assertEquals("rebinding must not leak listeners", 1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals("rebinding must not leak listeners", 1, router.subscriptionCount("HeapProfiler.reportHeapSnapshotProgress"))
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        bind()
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals(0, router.subscriptionCount("HeapProfiler.reportHeapSnapshotProgress"))
    }

    // ============ model-level mapping & parser safety ============

    @Test
    fun memoryActionResult_and_error_factories_produceExpectedValues() {
        val success = MemoryActionResult.Success(Unit)
        assertTrue((success as MemoryActionResult.Success<Unit>).data === Unit)

        val failure = MemoryActionResult.Failure(MemoryError.Unsupported)
        assertTrue(failure.error === MemoryError.Unsupported)
        assertTrue(MemoryError.Unavailable === MemoryError.Unavailable)
        assertTrue(MemoryError.TargetDisconnected === MemoryError.TargetDisconnected)

        val exec = MemoryError.ExecutionFailed("boom")
        assertEquals("boom", exec.message)
    }

    @Test
    fun parser_parseDomCounters_safe() {
        val counters = MemoryParser.parseDomCounters(
            mapOf("documents" to 3, "nodes" to 50, "jsEventListeners" to 7)
        )!!
        assertEquals(3, counters.documents)
        assertEquals(50, counters.nodes)
        assertEquals(7, counters.jsEventListeners)

        assertNull(MemoryParser.parseDomCounters("not-a-map"))
        assertNull(MemoryParser.parseDomCounters(null))
        assertEquals(
            DomCountersModel(0, 0, 0),
            MemoryParser.parseDomCounters(mapOf<String, Any?>("documents" to "x"))
        )
    }

    @Test
    fun parser_parseHeapSnapshotChunk_safe() {
        assertEquals("abc", MemoryParser.parseHeapSnapshotChunk(mapOf("chunk" to "abc")))
        assertNull(MemoryParser.parseHeapSnapshotChunk(mapOf("chunk" to 42)))
        assertNull(MemoryParser.parseHeapSnapshotChunk(mapOf<String, Any?>()))
        assertNull(MemoryParser.parseHeapSnapshotChunk("not-a-map"))
        assertNull(MemoryParser.parseHeapSnapshotChunk(null))
    }

    @Test
    fun parser_parseSamplingProfile_andNode_safe() {
        assertNull(MemoryParser.parseSamplingProfile(null))
        assertNull(MemoryParser.parseSamplingProfile(mapOf("x" to 1)))
        assertNull(MemoryParser.parseSamplingProfile(mapOf<String, Any?>()))

        assertNull(MemoryParser.parseSamplingNode(null))
        assertNull(MemoryParser.parseSamplingNode(mapOf("selfSize" to 5)))

        val node = MemoryParser.parseSamplingNode(
            mapOf("id" to 1, "selfSize" to 5)
        )!!
        assertEquals(1, node.id)
        assertEquals(5L, node.selfSize)
        assertNull(node.callFrameFunction)
        assertTrue(node.children.isEmpty())
    }
}
