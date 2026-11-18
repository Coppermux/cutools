package com.coppermux.cutools.core.performance

import com.coppermux.cutools.core.performance.models.MetricValueModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Phase 10 Performance Engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `Performance.enable/disable/getMetrics` serialization, genuine
 * `Performance.metrics` event routing into the real-time `metrics` StateFlow,
 * and strict metric-snapshot parsing in the same pipeline the app uses against
 * Chrome. No-Fake-Capability is enforced: protocol failures, missing sessions,
 * unavailable methods, and connection loss map to explicit UNSUPPORTED /
 * UNAVAILABLE / FAILED / TARGET_DISCONNECTED outcomes, never fabricated metric
 * state.
 */
class PerformanceEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: PerformanceEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = PerformanceEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
        val id = parseId("Performance.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })
    }

    private fun parseId(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun deliverMetricsEvent(metricsJson: String) {
        transport.deliverMessage(
            """{"method":"Performance.metrics","sessionId":"session-1","params":{"metrics":$metricsJson}}"""
        )
    }

    // ============ payload generation ============

    @Test
    fun bind_sendsPerformanceEnableOnSession() {
        engine.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("Performance.enable") }
        assertTrue(raw.contains("\"method\":\"Performance.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun enable_withTimeDomain_serializesTimeDomain() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.enable(timeDomain = "cpu") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Performance.enable") }
        assertTrue(raw.contains("\"timeDomain\":\"cpu\""))
    }

    @Test
    fun enable_withoutTimeDomain_omitsKey() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.enable() { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Performance.enable") }
        assertTrue(!raw.contains("\"timeDomain\""))
    }

    @Test
    fun disable_serializesDisableAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.disable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Performance.disable") }
        assertTrue(raw.contains("\"method\":\"Performance.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getMetrics_serializesMethodAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Performance.getMetrics") }
        assertTrue(raw.contains("\"method\":\"Performance.getMetrics\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ getMetrics parsing & StateFlow ============

    @Test
    fun getMetrics_success_parsesAndPublishesSnapshot() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        val id = parseId("Performance.getMetrics")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"metrics":[{"name":"memory","value":64.5},{"name":"cpu","value":12.0}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is PerformanceActionResult.Success<*>)
        val snapshot = (results[0] as PerformanceActionResult.Success<*>).data as PerformanceMetricsSnapshotModel
        assertEquals(2, snapshot.metrics.size)
        assertEquals("memory", snapshot.metrics[0].name)
        assertEquals(64.5, snapshot.metrics[0].value, 0.0)
        assertEquals("cpu", snapshot.metrics[1].name)
        assertEquals(12.0, snapshot.metrics[1].value, 0.0)
        assertEquals(PerformanceCapabilityStatus.SUPPORTED, engine.capability)
        assertEquals(snapshot, engine.metrics.value)
    }

    @Test
    fun getMetrics_emptyMetrics_returnsEmptySnapshot() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        val id = parseId("Performance.getMetrics")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"metrics":[]}}""")
        assertTrue(waitFor { results.size == 1 })
        val snapshot = (results[0] as PerformanceActionResult.Success<*>).data as PerformanceMetricsSnapshotModel
        assertTrue(snapshot.metrics.isEmpty())
        assertNotNull(engine.metrics.value)
        assertTrue(engine.metrics.value!!.metrics.isEmpty())
    }

    @Test
    fun getMetrics_missingMetricsList_returnsEmptySnapshot() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        val id = parseId("Performance.getMetrics")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        val snapshot = (results[0] as PerformanceActionResult.Success<*>).data as PerformanceMetricsSnapshotModel
        assertTrue(snapshot.metrics.isEmpty())
    }

    @Test
    fun getMetrics_nonObjectResult_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        val id = parseId("Performance.getMetrics")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":42}""")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as PerformanceActionResult.Failure
        assertTrue(failure.error is PerformanceError.ExecutionFailed)
        assertEquals(PerformanceCapabilityStatus.FAILED, engine.capability)
    }

    // ============ event parsing & StateFlow ============

    @Test
    fun metricsEvent_parsesAndUpdatesStateFlow() {
        bind()
        deliverMetricsEvent("""[{"name":"memory","value":50.0},{"name":"cpu","value":8.0}]""")
        assertTrue(waitFor { engine.metrics.value != null && engine.metrics.value!!.metrics.size == 2 })
        val snapshot = engine.metrics.value!!
        assertEquals("memory", snapshot.metrics[0].name)
        assertEquals(50.0, snapshot.metrics[0].value, 0.0)
        assertEquals("cpu", snapshot.metrics[1].name)
    }

    @Test
    fun metricsEvent_forOtherSession_isIgnored() {
        bind()
        transport.deliverMessage(
            """{"method":"Performance.metrics","sessionId":"other-session","params":{"metrics":[{"name":"memory","value":1.0}]}}"""
        )
        Thread.sleep(200)
        assertNull(engine.metrics.value)
    }

    @Test
    fun metricsEvent_malformedParams_isTolerated() {
        bind()
        transport.deliverMessage("""{"method":"Performance.metrics","sessionId":"session-1","params":"not-a-map"}""")
        Thread.sleep(150)
        assertNull(engine.metrics.value)
    }

    @Test
    fun metricsEvent_skipsMalformedEntries() {
        bind()
        deliverMetricsEvent("""[{"name":"memory","value":5.0},{"value":9.0},"not-a-map"]""")
        assertTrue(waitFor { engine.metrics.value != null && engine.metrics.value!!.metrics.size == 1 })
        assertEquals("memory", engine.metrics.value!!.metrics[0].name)
    }

    // ============ error mapping ============

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.enable() { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is PerformanceActionResult.Failure)
        assertTrue((results[0] as PerformanceActionResult.Failure).error === PerformanceError.Unavailable)
        assertEquals(PerformanceCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as PerformanceActionResult.Failure).error === PerformanceError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getMetrics_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as PerformanceActionResult.Failure).error === PerformanceError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enable_methodUnavailable_mapsToUnsupported() {
        engine.bindSession(session)
        val id = parseId("Performance.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.capability == PerformanceCapabilityStatus.UNSUPPORTED })
        assertEquals(PerformanceCapabilityStatus.UNSUPPORTED, engine.capability)
        assertTrue(!engine.enabled)
    }

    @Test
    fun disable_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseId("Performance.disable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"disable broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as PerformanceActionResult.Failure
        assertTrue(failure.error is PerformanceError.ExecutionFailed)
        assertTrue((failure.error as PerformanceError.ExecutionFailed).message.contains("disable broke"))
        assertEquals(PerformanceCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getMetrics_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as PerformanceActionResult.Failure).error === PerformanceError.TargetDisconnected)
    }

    // ============ domain success ============

    @Test
    fun enable_success_setsEnabledAndCapability() {
        engine.bindSession(session)
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.enable() { results.add(it) }
        val id = parseId("Performance.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is PerformanceActionResult.Success)
        assertEquals(PerformanceCapabilityStatus.SUPPORTED, engine.capability)
        assertTrue(engine.enabled)
    }

    @Test
    fun disable_success_disablesDomain() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseId("Performance.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is PerformanceActionResult.Success)
        assertTrue(waitFor { !engine.enabled })
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndUnsubscribes() {
        bind()
        deliverMetricsEvent("""[{"name":"memory","value":1.0}]""")
        assertTrue(waitFor { engine.metrics.value != null })
        assertEquals("one listener", 1, router.subscriptionCount("Performance.metrics"))

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertNull(engine.metrics.value)
        assertEquals("no event-listener leak", 0, router.subscriptionCount("Performance.metrics"))
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        deliverMetricsEvent("""[{"name":"memory","value":2.0}]""")
        assertTrue(waitFor { engine.metrics.value != null })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertNull(engine.metrics.value)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        deliverMetricsEvent("""[{"name":"memory","value":3.0}]""")
        assertTrue(waitFor { engine.metrics.value != null })
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.metrics.value)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<PerformanceActionResult<*>>()
        engine.getMetrics { results.add(it) }
        val id = parseId("Performance.getMetrics")

        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"metrics":[{"name":"m","value":1.0}]}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
    }

    @Test
    fun lateEventAfterDetach_isIgnored() {
        bind()
        engine.detach()
        transport.deliverMessage(
            """{"method":"Performance.metrics","sessionId":"session-1","params":{"metrics":[{"name":"memory","value":4.0}]}}"""
        )
        Thread.sleep(200)
        assertNull(engine.metrics.value)
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        engine.bindSession(session)
        val id1 = parseId("Performance.enable")
        transport.deliverMessage("""{"id":$id1,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        val id2 = parseId("Performance.enable")
        transport.deliverMessage("""{"id":$id2,"sessionId":"session-3","result":{}}""")
        assertTrue(waitFor { engine.enabled && engine.session == reconnected })

        assertEquals("rebinding must not leak extra listeners", 1, router.subscriptionCount("Performance.metrics"))
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        engine.bindSession(session)
        assertEquals(1, router.subscriptionCount("Performance.metrics"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("Performance.metrics"))
    }

    // ============ model-level mapping ============

    @Test
    fun performanceActionResult_and_error_factories_produceExpectedValues() {
        val success = PerformanceActionResult.Success(Unit)
        assertTrue((success as PerformanceActionResult.Success<Unit>).data === Unit)

        val failure = PerformanceActionResult.Failure(PerformanceError.Unsupported)
        assertTrue(failure.error === PerformanceError.Unsupported)

        val exec = PerformanceError.ExecutionFailed("boom")
        assertEquals("boom", exec.message)
        assertTrue(PerformanceError.Unavailable === PerformanceError.Unavailable)
        assertTrue(PerformanceError.TargetDisconnected === PerformanceError.TargetDisconnected)
    }

    @Test
    fun parser_parseMetrics_safe() {
        val metrics = PerformanceParser.parseMetrics(
            listOf<Any?>(
                mapOf("name" to "memory", "value" to 1.5),
                mapOf("name" to "cpu"),
                "not-a-map",
            )
        )
        assertEquals(2, metrics.size)
        assertEquals("memory", metrics[0].name)
        assertEquals(1.5, metrics[0].value, 0.0)
        assertEquals("cpu", metrics[1].name)
        assertEquals(0.0, metrics[1].value, 0.0)

        assertTrue(PerformanceParser.parseMetrics("not-a-map").isEmpty())
        assertTrue(PerformanceParser.parseMetrics(null).isEmpty())
    }

    @Test
    fun parser_parseSnapshot_safe() {
        val snapshot = PerformanceParser.parseSnapshot(
            mapOf<String, Any?>("metrics" to listOf<Any?>(mapOf("name" to "memory", "value" to 2.0)))
        )
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.metrics.size)
        assertEquals("memory", snapshot!!.metrics[0].name)
        assertTrue(snapshot!!.timestamp > 0)

        assertNull(PerformanceParser.parseSnapshot("not-a-map"))
        assertNull(PerformanceParser.parseSnapshot(null))
    }
}