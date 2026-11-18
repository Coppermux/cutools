package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic unit/protocol tests for the Phase 5A Network domain enablement
 * and event reception plus Phase 5B response body retrieval and resource
 * timing. Uses the real [CommandDispatcher] + [CdpEventRouter] on an in-memory
 * fake transport, so command serialization, classification, and event routing
 * are exercised end-to-end.
 */
class NetworkEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: NetworkEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = NetworkEngine(dispatcher, router)
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

    private fun bind() {
        engine.bindSession(session)
        val net = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$net,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })
    }

    private fun awaitRequestCount(count: Int) {
        assertTrue("requests >= $count", waitFor { engine.state.requests.size >= count })
    }

    // ============ domain enablement ============

    @Test
    fun bind_enablesNetworkDomainOnTheSession() {
        engine.bindSession(session)
        assertTrue(transport.sentMessages.any { it.contains("Network.enable") })
        val net = parseIdFor("Network.enable")
        assertTrue(transport.sentMessages.last { it.contains("Network.enable") }.contains("\"sessionId\":\"session-1\""))
        transport.deliverMessage("""{"id":$net,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })
        assertEquals(NetworkCapabilityStatus.SUPPORTED, engine.state.capability)
    }

    @Test
    fun bind_unsupportedMethod_movesToUnsupported() {
        engine.bindSession(session)
        val net = parseIdFor("Network.enable")
        transport.deliverMessage(
            """{"id":$net,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.UNSUPPORTED })
        assertEquals(NetworkCapabilityStatus.UNSUPPORTED, engine.state.capability)
        assertNotNull(engine.state.capabilityError)
    }

    @Test
    fun bind_genericEnableError_movesToFailed() {
        engine.bindSession(session)
        val net = parseIdFor("Network.enable")
        transport.deliverMessage(
            """{"id":$net,"sessionId":"session-1","error":{"code":-32000,"message":"something broke"}}"""
        )
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.FAILED })
        assertEquals(NetworkCapabilityStatus.FAILED, engine.state.capability)
    }

    // ============ event reception & lifecycle ============

    @Test
    fun requestWillBeSent_eventCreatesPendingRequest() {
        bind()
        deliverEvent("Network.requestWillBeSent", requestParams(url = "https://example.com/"))
        awaitRequestCount(1)
        val model = engine.state.requests[0]
        assertEquals(NetworkRequestStage.PENDING, model.stage)
        assertEquals("https://example.com/", model.url)
        assertEquals("GET", model.method)
        assertEquals("session-1", model.sessionId)
        assertEquals("target-1", model.targetId)
    }

    @Test
    fun fullLifecycle_pendingToResponseToFinished() {
        bind()
        val requestId = "req-1"
        deliverEvent("Network.requestWillBeSent", requestParams(requestId = requestId))
        awaitRequestCount(1)
        assertEquals(NetworkRequestStage.PENDING, engine.state.requests.first { it.requestId == requestId }.stage)

        deliverEvent("Network.responseReceived", responseParams(requestId = requestId))
        assertTrue(waitFor {
            engine.state.requests.any { it.requestId == requestId && it.stage == NetworkRequestStage.RESPONSE_RECEIVED }
        })
        assertEquals(200, engine.state.requests.first { it.requestId == requestId }.responseStatus)

        deliverEvent("Network.loadingFinished", mapOf("requestId" to requestId))
        assertTrue(waitFor { engine.state.requests.any { it.requestId == requestId && it.isFinished } })
        val model = engine.state.requests.first { it.requestId == requestId }
        assertEquals(NetworkRequestStage.FINISHED, model.stage)
        assertTrue(model.hasResponse)
        assertEquals(200, model.responseStatus)
    }

    @Test
    fun loadingFailed_createsFailedRequestWithError() {
        bind()
        val requestId = "req-fail"
        deliverEvent("Network.requestWillBeSent", requestParams(requestId = requestId))
        awaitRequestCount(1)
        deliverEvent("Network.loadingFailed", mapOf(
            "requestId" to requestId,
            "error" to "net::ERR_NAME_NOT_RESOLVED",
            "canceled" to false,
        ))
        assertTrue(waitFor { engine.state.requests.any { it.requestId == requestId && it.isFailed } })
        val model = engine.state.requests.first { it.requestId == requestId }
        assertEquals(NetworkRequestStage.FAILED, model.stage)
        assertEquals("net::ERR_NAME_NOT_RESOLVED", model.loadingError)
        assertEquals(1, engine.state.failedCount)
    }

    @Test
    fun requestWillBeSentAndLoadingFinished_fullLifecycle() {
        bind()
        val requestId = "req-2"
        deliverEvent("Network.requestWillBeSent", requestParams(requestId = requestId))
        awaitRequestCount(1)
        deliverEvent("Network.loadingFinished", finishedParams(requestId = requestId))
        assertTrue(waitFor { engine.state.requests.any { it.requestId == requestId && it.isFinished } })
        assertEquals(1, engine.state.finishedCount)
        assertEquals(0, engine.state.pendingCount)
    }

    // ============ event routing / session association ============

    @Test
    fun eventForOtherSession_isFilteredOut() {
        bind()
        val other = SessionInfo("other-session", "target-9", "ws://test")
        deliverEvent("Network.requestWillBeSent", requestParams(), sess = other)
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
    }

    @Test
    fun malformedEventPayload_isToleratedWithoutCrashing() {
        bind()
        deliverEvent("Network.requestWillBeSent", "not-a-map")
        deliverEvent("Network.loadingFinished", mapOf("no-id" to 1))
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
        assertEquals(NetworkLifecyclePhase.READY, engine.state.phase)
    }

    @Test
    fun unsupportedNetworkEvent_isIgnored() {
        bind()
        deliverEvent("Network.someOther", mapOf("requestId" to "r1"))
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
    }

    // ============ response body retrieval (Phase 5B) ============

    @Test
    fun getResponseBody_decodesPlainText() {
        bind()
        val results = java.util.concurrent.CopyOnWriteArrayList<ResponseBodyResult>()
        engine.getResponseBody("req-1") { results.add(it) }
        val id = parseIdFor("Network.getResponseBody")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"body":"plain text body","base64Encoded":false}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ResponseBodyStatus.SUCCESS, results[0].status)
        assertEquals("plain text body", results[0].text)
    }

    @Test
    fun getResponseBody_decodesBase64Text() {
        bind()
        val encoded = java.util.Base64.getEncoder().encodeToString("{\"a\":1}".toByteArray())
        val results = java.util.concurrent.CopyOnWriteArrayList<ResponseBodyResult>()
        engine.getResponseBody("req-1") { results.add(it) }
        val id = parseIdFor("Network.getResponseBody")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"body":"$encoded","base64Encoded":true}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ResponseBodyStatus.SUCCESS, results[0].status)
        assertEquals("{\"a\":1}", results[0].text)
    }

    @Test
    fun getResponseBody_missingBody_isUnavailable() {
        bind()
        val results = java.util.concurrent.CopyOnWriteArrayList<ResponseBodyResult>()
        engine.getResponseBody("req-1") { results.add(it) }
        val id = parseIdFor("Network.getResponseBody")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"base64Encoded":false}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ResponseBodyStatus.UNAVAILABLE, results[0].status)
    }

    @Test
    fun getResponseBody_evictedBody_isUnavailable() {
        bind()
        val results = java.util.concurrent.CopyOnWriteArrayList<ResponseBodyResult>()
        engine.getResponseBody("req-1") { results.add(it) }
        val id = parseIdFor("Network.getResponseBody")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Response body not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ResponseBodyStatus.UNAVAILABLE, results[0].status)
    }

    @Test
    fun getResponseBody_unsupportedMethod_isUnsupported() {
        bind()
        val results = java.util.concurrent.CopyOnWriteArrayList<ResponseBodyResult>()
        engine.getResponseBody("req-1") { results.add(it) }
        val id = parseIdFor("Network.getResponseBody")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(ResponseBodyStatus.UNSUPPORTED, results[0].status)
    }

    @Test
    fun getResponseBody_withoutBoundSession_isUnavailable() {
        val results = java.util.concurrent.CopyOnWriteArrayList<ResponseBodyResult>()
        engine.getResponseBody("req-1") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(ResponseBodyStatus.UNAVAILABLE, results[0].status)
    }

    // ============ resource timing parsing (Phase 5B) ============

    @Test
    fun responseReceived_populatesResourceTiming() {
        bind()
        val requestId = "req-timing"
        deliverEvent("Network.requestWillBeSent", requestParams(requestId = requestId))
        awaitRequestCount(1)
        val timingParams = mapOf(
            "requestId" to requestId,
            "type" to "xhr",
            "response" to mapOf(
                "url" to "https://example.com/",
                "status" to 200,
                "mimeType" to "application/json",
                "headers" to mapOf<String, String>("Content-Type" to "application/json"),
            ),
            "timing" to mapOf(
                "dnsStart" to 0.0,
                "dnsEnd" to 40.0,
                "connectStart" to 40.0,
                "connectEnd" to 120.0,
                "sslStart" to 120.0,
                "sslEnd" to 160.0,
                "sendStart" to 160.0,
                "sendEnd" to 220.0,
                "receiveHeadersEnd" to 320.0,
            ),
        )
        deliverEvent("Network.responseReceived", timingParams)
        assertTrue(waitFor {
            engine.state.requests.any {
                it.requestId == requestId && it.timing != null
            }
        })
        val model = engine.state.requests.first { it.requestId == requestId }
        assertNotNull(model.timing)
        assertEquals(40.0, model.timing!!.dnsDuration!!, 0.001)
        assertEquals(40.0, model.timing!!.sslDuration!!, 0.001)
        assertEquals(160.0, model.timing!!.ttfbDuration!!, 0.001)
    }

    // ============ helpers ============

    private fun requestParams(requestId: String = "r1", url: String = "https://example.com/"): Map<String, Any?> = mapOf(
        "requestId" to requestId,
        "documentURL" to url,
        "initiator" to "subresource",
        "request" to mapOf("url" to url, "method" to "GET", "headers" to mapOf("Accept" to "*/*")),
    )

    private fun responseParams(requestId: String): Map<String, Any?> = mapOf(
        "requestId" to requestId,
        "type" to "xhr",
        "response" to mapOf(
            "url" to "https://example.com/",
            "status" to 200,
            "statusText" to "OK",
            "mimeType" to "application/json",
            "headers" to mapOf("Content-Type" to "application/json"),
        ),
    )

    private fun finishedParams(requestId: String): Map<String, Any?> = mapOf("requestId" to requestId)

    private fun deliverEvent(method: String, params: Any?, sess: SessionInfo = session) {
        router.handleEvent(Event(method, params, sessionId = sess.sessionId, targetId = sess.targetId))
    }
}