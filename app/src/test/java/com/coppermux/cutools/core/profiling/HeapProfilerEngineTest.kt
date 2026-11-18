package com.coppermux.cutools.core.profiling

import com.coppermux.cutools.core.model.Protocol
import com.coppermux.cutools.core.profiling.models.HeapProfilerActionResult
import com.coppermux.cutools.core.profiling.models.HeapProfilerError
import com.coppermux.cutools.core.profiling.models.HeapProfilerStatus
import com.coppermux.cutools.core.profiling.models.HeapSnapshotResult
import com.coppermux.cutools.core.profiling.models.SamplingHeapProfileModel
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
 * Unit & protocol test suite for Sub-Phase 7C — Heap Snapshot & Memory Engine
 * (HeapProfiler Domain).
 *
 * Raw CDP `HeapProfiler` commands and `HeapProfiler.addHeapSnapshotChunk` /
 * `HeapProfiler.reportHeapSnapshotProgress` events are delivered via the fake
 * transport and flow through the dispatcher → eventRouter → engine, proving
 * serialization, snapshot chunk reassembly & structural integrity, sampling
 * lifecycle, and the full enable → takeSnapshot → complete flow. No-Fake-Capability
 * is enforced: protocol failures map to explicit Unsupported / Unavailable /
 * Failed / SnapshotInProgress / SamplingInProgress errors.
 */
class HeapProfilerEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var router: CdpEventRouter
    private lateinit var engine: HeapProfilerEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = HeapProfilerEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
    }

    private fun enableSuccessfully() {
        engine.enable {}
        val id = parseIdFor("HeapProfiler.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
    }

    private fun startSnapshotSuccessfully() {
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val id = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == HeapProfilerStatus.TAKING_SNAPSHOT })
    }

    private fun startSamplingSuccessfully() {
        engine.startSampling {}
        val id = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == HeapProfilerStatus.SAMPLING })
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

    private fun deliverEvent(method: String, paramsJson: String, sessionId: String = "session-1") {
        transport.deliverMessage("""{"method":"$method","sessionId":"$sessionId","params":$paramsJson}""")
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

    /** A valid heap snapshot split into three ordered raw fragments. */
    private fun snapshotChunks(): List<String> = listOf(
        """{"snapshot":{"meta":{"node_fields":["type","name","id"]}},"nodes":[""",
        """1,2,3],"strings":[""",
        """"a","b"],"edges":[]}""",
    )

    // ============ payload generation & subscription registration ============

    @Test
    fun takeHeapSnapshot_serializesMethodParamsAndRegistersSubscriptions() {
        bind()
        engine.takeHeapSnapshot { }
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.takeHeapSnapshot") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.takeHeapSnapshot\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"reportProgress\":true"))
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals(1, router.subscriptionCount("HeapProfiler.reportHeapSnapshotProgress"))
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
    fun startSampling_serializesMethodParamsAndSession() {
        bind()
        engine.startSampling(samplingIntervalBytes = 512) {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.startSampling") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.startSampling\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"samplingInterval\":512"))
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
    fun stopSampling_serializesMethodAndSession() {
        bind()
        startSamplingSuccessfully()
        engine.stopSampling {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.stopSampling") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.stopSampling\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ full lifecycle: enable -> takeSnapshot -> chunks -> completion ============

    @Test
    fun fullLifecycle_enableTakeSnapshotStreamChunks_completesAndReassembles() {
        bind()
        enableSuccessfully()
        assertEquals(HeapProfilerStatus.IDLE, engine.status)

        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == HeapProfilerStatus.TAKING_SNAPSHOT })

        snapshotChunks().forEach { deliverChunk(it) }
        deliverProgress(finished = false)
        assertEquals(HeapProfilerStatus.TAKING_SNAPSHOT, engine.status)
        deliverProgress(finished = true)

        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is HeapProfilerActionResult.Success)
        val result = (results[0] as HeapProfilerActionResult.Success).value
        assertEquals(HeapProfilerStatus.STOPPED, engine.status)
        assertEquals(HeapProfilerStatus.STOPPED, result.status)
        assertEquals("session-1", result.sessionId)
        assertEquals(3, result.chunkCount)
        assertEquals(snapshotChunks().joinToString("").length, result.byteSize)
        assertEquals(result, engine.snapshot)
    }

    @Test
    fun chunkReassembly_reconstructsValidSnapshotJson() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == HeapProfilerStatus.TAKING_SNAPSHOT })

        snapshotChunks().forEach { deliverChunk(it) }
        deliverProgress(finished = true)
        assertTrue(waitFor { results.size == 1 })

        val json = (results[0] as HeapProfilerActionResult.Success).value.snapshotJson
        // Structural integrity: the reassembled text must parse as valid JSON.
        val parsed = Protocol.fromJsonValue<Any>(json)
        assertTrue(parsed is Map<*, *>)
        val snapshot = (parsed as Map<*, *>)["snapshot"] as Map<*, *>
        assertEquals(listOf("type", "name", "id"), snapshot["meta"]!!.let { (it as Map<*, *>)["node_fields"] as List<*> })
    }

    @Test
    fun multiChunkReassembly_joinsInOrder() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == HeapProfilerStatus.TAKING_SNAPSHOT })

        deliverChunk("""{"a":""")
        deliverChunk("1,")
        deliverChunk(""""b":2}""")
        deliverProgress(finished = true)
        assertTrue(waitFor { results.size == 1 })

        val result = (results[0] as HeapProfilerActionResult.Success).value
        assertEquals("""{"a":1,"b":2}""", result.snapshotJson)
        assertEquals(3, result.chunkCount)
        val parsed = Protocol.fromJsonValue<Any>(result.snapshotJson) as Map<*, *>
        assertEquals(1, (parsed["a"] as Number).toInt())
        assertEquals(2, (parsed["b"] as Number).toInt())
    }

    @Test
    fun snapshot_chunksFromOtherSession_areIgnored() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == HeapProfilerStatus.TAKING_SNAPSHOT })

        transport.deliverMessage(
            """{"method":"HeapProfiler.addHeapSnapshotChunk","sessionId":"session-OTHER","params":{"chunk":"junk"}}"""
        )
        transport.deliverMessage(
            """{"method":"HeapProfiler.reportHeapSnapshotProgress","sessionId":"session-OTHER","params":{"finished":true}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertEquals(HeapProfilerStatus.TAKING_SNAPSHOT, engine.status)
        assertNull(engine.snapshot)
    }

    // ============ sampling lifecycle ============

    @Test
    fun sampling_fullLifecycle_startThenStop_returnsProfile() {
        bind()
        val startResults = CopyOnWriteArrayList<HeapProfilerActionResult<Unit>>()
        engine.startSampling { startResults.add(it) }
        val startId = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { startResults.size == 1 })
        assertTrue(startResults[0] is HeapProfilerActionResult.Success)
        assertEquals(HeapProfilerStatus.SAMPLING, engine.status)

        val stopResults = CopyOnWriteArrayList<HeapProfilerActionResult<SamplingHeapProfileModel>>()
        engine.stopSampling { stopResults.add(it) }
        val stopId = parseIdFor("HeapProfiler.stopSampling")
        transport.deliverMessage(
            """{"id":$stopId,"sessionId":"session-1","result":{"profile":${sampleSamplingProfileJson()}}}"""
        )
        assertTrue(waitFor { stopResults.size == 1 })
        assertTrue(stopResults[0] is HeapProfilerActionResult.Success)
        val profile = (stopResults[0] as HeapProfilerActionResult.Success).value
        assertEquals(HeapProfilerStatus.STOPPED, engine.status)
        assertEquals(3, profile.nodes.size)
        assertEquals(profile, engine.samplingProfile)
    }

    @Test
    fun stopSampling_parsesHeadSelfAndTotalSizes() {
        val profile = HeapProfilerAnalyzer.parseSamplingProfile(
            Protocol.fromJsonValue<Any>(sampleSamplingProfileJson())
        )!!
        val head = profile.head!!
        assertEquals(0.0, head.selfSize, 0.001)
        assertEquals(1200.0, head.totalSize, 0.001)
        assertEquals("root", head.functionName)
        assertEquals(2, head.children.size)
        val child = head.children[0]
        assertEquals(400.0, child.selfSize, 0.001)
        assertEquals("foo", child.functionName)
        assertEquals("app.js", child.url)
    }

    private fun sampleSamplingProfileJson(): String = """{
        "head":{
            "selfSize":0,
            "totalSize":1200,
            "callFrame":{"functionName":"root","scriptId":"1","url":"app.js","lineNumber":0,"columnNumber":0},
            "children":[
                {"selfSize":400,"totalSize":400,"callFrame":{"functionName":"foo","scriptId":"1","url":"app.js","lineNumber":1,"columnNumber":2},"children":[]},
                {"selfSize":800,"totalSize":800,"callFrame":{"functionName":"bar","scriptId":"1","url":"app.js","lineNumber":2,"columnNumber":2},"children":[]}
            ]
        }
    }"""

    // ============ error mapping ============

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as HeapProfilerActionResult.Failure).error is HeapProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as HeapProfilerActionResult.Failure).error is HeapProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun takeHeapSnapshot_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as HeapProfilerActionResult.Failure).error is HeapProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun startSampling_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<Unit>>()
        engine.startSampling { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as HeapProfilerActionResult.Failure).error is HeapProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopSampling_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<SamplingHeapProfileModel>>()
        engine.stopSampling { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as HeapProfilerActionResult.Failure).error is HeapProfilerError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun takeHeapSnapshot_protocolError_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val id = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"snapshot broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.Failed)
        assertTrue((failure.error as HeapProfilerError.Failed).cause.message.orEmpty().contains("snapshot broke"))
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun takeHeapSnapshot_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val id = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.Unsupported)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun takeHeapSnapshot_whileSnapshotInProgress_mapsToSnapshotInProgress() {
        bind()
        startSnapshotSuccessfully()
        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.SnapshotInProgress)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun startSampling_protocolError_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<Unit>>()
        engine.startSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"start broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.Failed)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun startSampling_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<Unit>>()
        engine.startSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.Unsupported)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun startSampling_whileSampling_mapsToSamplingInProgress() {
        bind()
        startSamplingSuccessfully()
        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<Unit>>()
        engine.startSampling { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.SamplingInProgress)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopSampling_withoutActiveSampling_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<SamplingHeapProfileModel>>()
        engine.stopSampling { results.add(it) }
        assertEquals(1, results.size)
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.Failed)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun stopSampling_protocolError_mapsToFailed() {
        bind()
        startSamplingSuccessfully()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<SamplingHeapProfileModel>>()
        engine.stopSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.stopSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"stop broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.Failed)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
    }

    @Test
    fun stopSampling_malformedProfile_mapsToFailed() {
        bind()
        startSamplingSuccessfully()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<SamplingHeapProfileModel>>()
        engine.stopSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.stopSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"profile":{"no":"head"}}}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as HeapProfilerActionResult.Failure
        assertTrue(failure.error is HeapProfilerError.Failed)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndSubscriptions() {
        bind()
        startSnapshotSuccessfully()
        deliverChunk("""{"x":1}""")
        assertEquals(1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))

        engine.detach()
        assertNull(engine.session)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
        assertNull(engine.snapshot)
        assertNull(engine.samplingProfile)
        assertEquals("no event-listener leak", 0, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
        assertEquals("no event-listener leak", 0, router.subscriptionCount("HeapProfiler.reportHeapSnapshotProgress"))
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        startSnapshotSuccessfully()
        deliverChunk("""{"x":1}""")
        deliverProgress(finished = true)
        assertTrue(waitFor { engine.snapshot != null })
        assertEquals(1, engine.snapshot!!.chunkCount)

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
        assertNull(engine.snapshot)
        assertNull(engine.samplingProfile)
        assertEquals("rebind re-subscribes once for the new session", 1, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        startSnapshotSuccessfully()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertEquals(HeapProfilerStatus.IDLE, engine.status)
        assertEquals(0, router.subscriptionCount("HeapProfiler.addHeapSnapshotChunk"))
    }

    @Test
    fun staleCompletionAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<HeapProfilerActionResult<HeapSnapshotResult>>()
        engine.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.status == HeapProfilerStatus.TAKING_SNAPSHOT })
        deliverChunk("""{"x":1}""")

        engine.detach()
        deliverProgress(finished = true)
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.snapshot)
        assertNull(engine.session)
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

    // ============ model-level mapping ============

    @Test
    fun heapProfilerActionResult_successAndFailureCarryExpectedValues() {
        val success = HeapProfilerActionResult.Success("data")
        assertEquals("data", success.value)

        val unsupported = HeapProfilerActionResult.Failure(HeapProfilerError.Unsupported("nope"))
        assertTrue((unsupported.error as HeapProfilerError.Unsupported).reason == "nope")

        val unavailable = HeapProfilerActionResult.Failure(HeapProfilerError.Unavailable("no session"))
        assertTrue((unavailable.error as HeapProfilerError.Unavailable).detail == "no session")

        val failed = HeapProfilerActionResult.Failure(HeapProfilerError.Failed(RuntimeException("boom")))
        assertTrue((failed.error as HeapProfilerError.Failed).cause.message == "boom")

        val snapInProgress = HeapProfilerActionResult.Failure(HeapProfilerError.SnapshotInProgress("busy"))
        assertTrue((snapInProgress.error as HeapProfilerError.SnapshotInProgress).detail == "busy")

        val sampInProgress = HeapProfilerActionResult.Failure(HeapProfilerError.SamplingInProgress("busy"))
        assertTrue((sampInProgress.error as HeapProfilerError.SamplingInProgress).detail == "busy")
    }

    @Test
    fun heapProfilerAnalyzer_assembleAndParse_handleMissingFieldsSafely() {
        val assembled = HeapProfilerAnalyzer.assemble(listOf("a", "b"), "session-1")
        assertEquals("ab", assembled.snapshotJson)
        assertEquals(2, assembled.chunkCount)
        assertEquals(2, assembled.byteSize)
        assertEquals("session-1", assembled.sessionId)

        assertNull(HeapProfilerAnalyzer.parseSamplingProfile(null))
        assertNull(HeapProfilerAnalyzer.parseSamplingProfile(mapOf("x" to 1)))

        val node = HeapProfilerAnalyzer.parseNode(null)
        assertNull(node)
        val emptyNode = HeapProfilerAnalyzer.parseNode(mapOf("selfSize" to 5))
        assertTrue(emptyNode != null)
        assertEquals(5.0, emptyNode!!.selfSize, 0.001)
        assertEquals(0.0, emptyNode.totalSize, 0.001)
        assertNull(emptyNode.functionName)
        assertTrue(emptyNode.children.isEmpty())
    }
}
