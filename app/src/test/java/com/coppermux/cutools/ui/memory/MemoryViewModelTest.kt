package com.coppermux.cutools.ui.memory

import com.coppermux.cutools.core.memory.MemoryEngine
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for the Phase 11B Memory ViewModel.
 *
 * The real [MemoryEngine] (Phase 11A) is constructed against the fake transport
 * and driven through raw CDP JSON, then the ViewModel's state transitions,
 * command dispatching, heap snapshot chunk streaming, sampling lifecycle, DOM
 * counter propagation, and error handling into explicit banners are verified in
 * the same pipeline the app uses against Chrome. No-Fake-Capability is enforced:
 * engine failures surface as explicit `errorMessage` banners with matching
 * status, never synthetic memory state fallbacks.
 */
class MemoryViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: MemoryEngine
    private lateinit var viewModel: MemoryViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = MemoryEngine(dispatcher, router)
        viewModel = MemoryViewModel(engine)
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

    // ============ target attach / detach coordination ============

    @Test
    fun attachTarget_bindsEngineAndPublishesState() {
        viewModel.attachTarget(session)
        assertEquals(session, engine.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
    }

    @Test
    fun detachTarget_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.detachTarget()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        val state = viewModel.state.value
        assertEquals(MemoryCapabilityStatus.UNAVAILABLE, state.status)
        assertNull(state.domCounters)
        assertFalse(state.samplingActive)
        assertNull(state.errorMessage)
    }

    @Test
    fun onConnectionLost_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.onConnectionLost()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertEquals(MemoryCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
    }

    // ============ command dispatching & state transitions ============

    @Test
    fun triggerGarbageCollection_success_marksSupported() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        viewModel.triggerGarbageCollection { results.add(it) }
        val id = parseIdFor("HeapProfiler.collectGarbage")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)
        assertEquals(MemoryCapabilityStatus.SUPPORTED, viewModel.state.value.status)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun triggerGarbageCollection_dispatchesCollectGarbageWithSession() {
        viewModel.attachTarget(session)
        transport.resetSentMessages()
        viewModel.triggerGarbageCollection {}
        val raw = transport.sentMessages.last { it.contains("HeapProfiler.collectGarbage") }
        assertTrue(raw.contains("\"method\":\"HeapProfiler.collectGarbage\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun fetchDomCounters_success_recordsCounters() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        viewModel.fetchDomCounters { results.add(it) }
        val id = parseIdFor("Memory.getDOMCounters")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"documents":4,"nodes":100,"jsEventListeners":25}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)
        val counters = viewModel.state.value.domCounters!!
        assertEquals(4, counters.documents)
        assertEquals(100, counters.nodes)
        assertEquals(25, counters.jsEventListeners)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun startSampling_success_setsSamplingActive() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        viewModel.startSampling { results.add(it) }
        val id = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)
        assertTrue(viewModel.state.value.samplingActive)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun stopSampling_success_recordsProfile() {
        viewModel.attachTarget(session)
        val startResults = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        viewModel.startSampling { startResults.add(it) }
        val startId = parseIdFor("HeapProfiler.startSampling")
        transport.deliverMessage("""{"id":$startId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { startResults.size == 1 })

        val stopResults = CopyOnWriteArrayList<MemoryActionResult<HeapSamplingProfileModel>>()
        viewModel.stopSampling { stopResults.add(it) }
        val stopId = parseIdFor("HeapProfiler.stopSampling")
        transport.deliverMessage(
            """{"id":$stopId,"sessionId":"session-1","result":{"profile":{"head":{"id":1,"selfSize":0,"callFrame":{"functionName":"root"},"children":[{"id":2,"selfSize":400,"callFrame":{"functionName":"foo"},"children":[]}]},"samples":[2,2]}}}"""
        )
        assertTrue(waitFor { stopResults.size == 1 })
        assertTrue(stopResults[0] is MemoryActionResult.Success)
        assertFalse(viewModel.state.value.samplingActive)
        assertEquals(2, viewModel.state.value.samplingProfile!!.samplesCount)
        assertEquals(2, viewModel.flattenProfile(viewModel.state.value.samplingProfile).size)
        assertEquals("root", viewModel.state.value.samplingProfile!!.head!!.callFrameFunction)
    }

    // ============ real-time heap snapshot chunk streaming ============

    @Test
    fun takeHeapSnapshot_streamsChunksThroughViewModel() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        viewModel.takeHeapSnapshot { results.add(it) }
        val takeId = parseIdFor("HeapProfiler.takeHeapSnapshot")
        transport.deliverMessage("""{"id":$takeId,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is MemoryActionResult.Success)

        assertEquals("", viewModel.heapSnapshotStream.value)
        deliverChunk("""{"a":""")
        assertEquals("""{"a":""", viewModel.heapSnapshotStream.value)
        deliverChunk("1}")
        assertEquals("""{"a":1}""", viewModel.heapSnapshotStream.value)

        viewModel.refreshHeapSnapshot()
        assertEquals("""{"a":1}""", viewModel.state.value.heapSnapshot)
    }

    private fun deliverChunk(chunk: String) {
        val escaped = "\"" + chunk.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        transport.deliverMessage(
            """{"method":"HeapProfiler.addHeapSnapshotChunk","sessionId":"session-1","params":{"chunk":$escaped}}"""
        )
    }

    // ============ error handling (No-Fake-Capability) ============

    @Test
    fun triggerGarbageCollection_withoutSession_resolvesUnavailableBanner() {
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        viewModel.triggerGarbageCollection { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is MemoryActionResult.Failure)
        val state = viewModel.state.value
        assertEquals(MemoryCapabilityStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unavailable"))
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun fetchDomCounters_methodUnavailable_resolvesUnsupportedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<MemoryActionResult<DomCountersModel>>()
        viewModel.fetchDomCounters { results.add(it) }
        val id = parseIdFor("Memory.getDOMCounters")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertEquals(MemoryCapabilityStatus.UNSUPPORTED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unsupported"))
        assertNull(state.domCounters)
    }

    @Test
    fun collectGarbage_protocolFailure_resolvesFailedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<MemoryActionResult<Unit>>()
        viewModel.triggerGarbageCollection { results.add(it) }
        val id = parseIdFor("HeapProfiler.collectGarbage")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"gc broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage.orEmpty().contains("gc broke"))
        assertEquals(MemoryCapabilityStatus.FAILED, state.status)
    }

    @Test
    fun describeError_mapsEveryErrorVariantToRenderableBanner() {
        assertTrue(viewModel.describeError(MemoryError.Unsupported).contains("Unsupported"))
        assertTrue(viewModel.describeError(MemoryError.Unavailable).contains("Unavailable"))
        assertTrue(viewModel.describeError(MemoryError.TargetDisconnected).contains("disconnected"))
        assertTrue(viewModel.describeError(MemoryError.ExecutionFailed("boom")).contains("boom"))
        assertTrue(viewModel.describeError(MemoryError.ExecutionFailed("")).contains("execution failed"))
    }

    // ============ flatten profile ============

    @Test
    fun flattenProfile_visitsTreeInDepthFirstOrder() {
        val profile = HeapSamplingProfileModel(
            head = HeapSamplingNodeModel(
                id = 1,
                callFrameFunction = "root",
                selfSize = 0,
                children = listOf(
                    HeapSamplingNodeModel(id = 2, callFrameFunction = "foo", selfSize = 400),
                    HeapSamplingNodeModel(id = 3, callFrameFunction = "bar", selfSize = 800),
                ),
            ),
            samplesCount = 2,
        )
        val rows = viewModel.flattenProfile(profile)
        assertEquals(3, rows.size)
        assertEquals(0, rows[0].depth)
        assertEquals("root", rows[0].function)
        assertEquals(1, rows[1].depth)
        assertEquals("foo", rows[1].function)
        assertEquals(1, rows[2].depth)
        assertEquals(400L, rows[1].selfSize)
        assertTrue(viewModel.flattenProfile(null).isEmpty())
    }
}