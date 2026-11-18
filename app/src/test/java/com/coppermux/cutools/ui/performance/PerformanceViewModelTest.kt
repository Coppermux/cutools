package com.coppermux.cutools.ui.performance

import com.coppermux.cutools.core.performance.PerformanceEngine
import com.coppermux.cutools.core.performance.models.PerformanceActionResult
import com.coppermux.cutools.core.performance.models.PerformanceCapabilityStatus
import com.coppermux.cutools.core.performance.models.PerformanceError
import com.coppermux.cutools.core.performance.models.PerformanceMetricsSnapshotModel
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
 * Unit & protocol test suite for the Phase 11B Performance ViewModel.
 *
 * The real [PerformanceEngine] (Phase 10) is constructed against the fake
 * transport and driven through raw CDP JSON, then the ViewModel's state
 * transitions, command dispatching, real-time metric streaming, timeline
 * accumulation, metric filtering, and error propagation into explicit banners
 * are verified in the same pipeline the app uses against Chrome.
 * No-Fake-Capability is enforced: engine failures surface as explicit
 * `errorMessage` banners with matching status, never synthetic metric fallbacks.
 */
class PerformanceViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: PerformanceEngine
    private lateinit var viewModel: PerformanceViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = PerformanceEngine(dispatcher, router)
        viewModel = PerformanceViewModel(engine)
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

    private fun deliverMetricsSnapshot() {
        transport.deliverMessage(
            """{"method":"Performance.metrics","sessionId":"session-1","params":{"metrics":[{"name":"JSHeapUsedSize","value":1048576},{"name":"LayoutCount","value":3}]}}"""
        )
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
        assertEquals(PerformanceCapabilityStatus.UNAVAILABLE, state.status)
        assertNull(state.currentMetrics)
        assertTrue(state.timeline.isEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun onConnectionLost_clearsEngineAndResetsState() {
        viewModel.attachTarget(session)
        viewModel.onConnectionLost()
        assertNull(engine.session)
        assertNull(viewModel.attachedSession)
        assertEquals(PerformanceCapabilityStatus.UNAVAILABLE, viewModel.state.value.status)
    }

    // ============ command dispatching & state transitions ============

    @Test
    fun enablePerformance_success_marksSupportedAndEnabled() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        viewModel.enablePerformance { results.add(it) }
        val id = parseIdFor("Performance.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is PerformanceActionResult.Success)
        val state = viewModel.state.value
        assertEquals(PerformanceCapabilityStatus.SUPPORTED, state.status)
        assertTrue(state.enabled)
        assertTrue(viewModel.isEnabled)
        assertNull(state.errorMessage)
    }

    @Test
    fun enablePerformance_serializesTimeDomainWhenSet() {
        viewModel.attachTarget(session)
        viewModel.setTimeDomain("threadTicks")
        transport.resetSentMessages()
        viewModel.enablePerformance {}
        val raw = transport.sentMessages.last { it.contains("Performance.enable") }
        assertTrue(raw.contains("\"method\":\"Performance.enable\""))
        assertTrue(raw.contains("\"timeDomain\":\"threadTicks\""))
    }

    @Test
    fun refreshMetrics_success_recordsSnapshotAndTimeline() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<PerformanceActionResult<PerformanceMetricsSnapshotModel>>()
        viewModel.refreshMetrics { results.add(it) }
        assertTrue(viewModel.state.value.isLoading)
        val id = parseIdFor("Performance.getMetrics")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"metrics":[{"name":"LayoutCount","value":5}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { !viewModel.state.value.isLoading })
        val snapshot = viewModel.state.value.currentMetrics!!
        assertEquals(5.0, snapshot.metrics.first().value, 0.001)
        assertEquals(1, viewModel.state.value.timeline.size)
        assertEquals(PerformanceCapabilityStatus.SUPPORTED, viewModel.state.value.status)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun refreshMetrics_accumulatesBoundedTimeline() {
        viewModel.attachTarget(session)
        val max = PerformanceViewModel.MAX_TIMELINE
        for (i in 0 until (max + 10)) {
            viewModel.refreshMetrics {}
            val id = parseIdFor("Performance.getMetrics")
            transport.deliverMessage(
                """{"id":$id,"sessionId":"session-1","result":{"metrics":[{"name":"LayoutCount","value":$i}]}}"""
            )
            val expected = minOf(i + 1, max)
            assertTrue(waitFor { viewModel.state.value.timeline.size == expected })
        }
        assertEquals("timeline is bounded", max, viewModel.state.value.timeline.size)
    }

    // ============ real-time metric streaming propagation ============

    @Test
    fun metricStream_propagatesThroughEngineToViewModel() {
        viewModel.attachTarget(session)
        deliverMetricsSnapshot()
        assertTrue(waitFor { viewModel.metrics.value != null })
        val snapshot = viewModel.metrics.value!!
        assertTrue(snapshot.metrics.any { it.name == "JSHeapUsedSize" && it.value == 1048576.0 })
        assertTrue(snapshot.metrics.any { it.name == "LayoutCount" && it.value == 3.0 })
    }

    // ============ metric filtering ============

    @Test
    fun filteredMetricNames_emptyFilter_returnsAll() {
        viewModel.attachTarget(session)
        val snapshot = PerformanceMetricsSnapshotModel(metrics = listOf(
            com.coppermux.cutools.core.performance.models.MetricValueModel("JSHeapUsedSize", 1.0),
            com.coppermux.cutools.core.performance.models.MetricValueModel("LayoutCount", 2.0),
        ))
        assertEquals(2, viewModel.filteredMetricNames(snapshot).size)
    }

    @Test
    fun filteredMetricNames_filtersCaseInsensitive() {
        viewModel.attachTarget(session)
        val snapshot = PerformanceMetricsSnapshotModel(metrics = listOf(
            com.coppermux.cutools.core.performance.models.MetricValueModel("JSHeapUsedSize", 1.0),
            com.coppermux.cutools.core.performance.models.MetricValueModel("LayoutCount", 2.0),
            com.coppermux.cutools.core.performance.models.MetricValueModel("RecalcStyleCount", 3.0),
        ))
        viewModel.updateMetricFilter("layout")
        assertEquals(listOf("LayoutCount"), viewModel.filteredMetricNames(snapshot))
        viewModel.updateMetricFilter("recalc")
        assertEquals(listOf("RecalcStyleCount"), viewModel.filteredMetricNames(snapshot))
    }

    // ============ error handling (No-Fake-Capability) ============

    @Test
    fun refreshMetrics_withoutSession_resolvesUnavailableBanner() {
        val results = CopyOnWriteArrayList<PerformanceActionResult<PerformanceMetricsSnapshotModel>>()
        viewModel.refreshMetrics { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is PerformanceActionResult.Failure)
        val state = viewModel.state.value
        assertEquals(PerformanceCapabilityStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unavailable"))
        assertNull(state.currentMetrics)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enablePerformance_methodUnavailable_resolvesUnsupportedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        viewModel.enablePerformance { results.add(it) }
        val id = parseIdFor("Performance.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertEquals(PerformanceCapabilityStatus.UNSUPPORTED, state.status)
        assertTrue(state.errorMessage.orEmpty().contains("Unsupported"))
        assertFalse(state.enabled)
    }

    @Test
    fun refreshMetrics_protocolFailure_resolvesFailedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<PerformanceActionResult<PerformanceMetricsSnapshotModel>>()
        viewModel.refreshMetrics { results.add(it) }
        val id = parseIdFor("Performance.getMetrics")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"metrics broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(waitFor { viewModel.state.value.errorMessage != null })
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.currentMetrics)
        assertTrue(state.errorMessage.orEmpty().contains("metrics broke"))
    }

    // ============ error description helpers ============

    @Test
    fun describeError_mapsEveryErrorVariantToRenderableBanner() {
        assertTrue(viewModel.describeError(PerformanceError.Unsupported).contains("Unsupported"))
        assertTrue(viewModel.describeError(PerformanceError.Unavailable).contains("Unavailable"))
        assertTrue(viewModel.describeError(PerformanceError.TargetDisconnected).contains("disconnected"))
        assertTrue(viewModel.describeError(PerformanceError.ExecutionFailed("boom")).contains("boom"))
        assertTrue(viewModel.describeError(PerformanceError.ExecutionFailed("")).contains("execution failed"))
    }
}