package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.model.Event
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic unit/protocol tests for Phase 5C Fetch-domain interception:
 * `Fetch.enable`/`Fetch.disable`, `Fetch.requestPaused` routing through the
 * rule manager, `Fetch.fulfillRequest` (mock HTTP response + base64 body),
 * `Fetch.failRequest` error mapping, `Fetch.continueRequest`,
 * `Fetch.continueWithAuth`, and stale-request rejection on session reconnect.
 * Uses the real [CommandDispatcher] + [CdpEventRouter] on an in-memory fake
 * transport, so command serialization, classification, and event routing are
 * exercised end-to-end.
 */
class FetchInterceptionEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: FetchInterceptionEngine
    private lateinit var ruleManager: MockResponseRuleManager

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        ruleManager = MockResponseRuleManager()
        engine = FetchInterceptionEngine(dispatcher, router, ruleManager)
    }

    private fun parseIdFor(method: String): Long {
        val raw = awaitMessageContaining(method)
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun awaitMessageContaining(method: String, timeoutMs: Long = 3000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            transport.sentMessages.lastOrNull { it.contains(method) }?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for a transport message containing '$method'")
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun bind(sess: SessionInfo = session, patterns: List<RequestPattern> = emptyList()) {
        engine.bindSession(sess, patterns = patterns)
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${sess.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })
    }

    private fun pausedParams(
        requestId: String,
        url: String = "https://example.com/",
        method: String = "GET",
    ): Map<String, Any?> = mapOf(
        "requestId" to requestId,
        "documentURL" to url,
        "request" to mapOf("url" to url, "method" to method),
    )

    private fun deliverEvent(method: String, params: Any?, sess: SessionInfo = session) {
        router.handleEvent(Event(method, params, sessionId = sess.sessionId, targetId = sess.targetId))
    }

    // ============ domain enablement ============

    @Test
    fun bind_enablesFetchDomainOnTheSession() {
        engine.bindSession(session)
        assertTrue(transport.sentMessages.any { it.contains("Fetch.enable") })
        val msg = transport.sentMessages.last { it.contains("Fetch.enable") }
        assertTrue(msg.contains("\"sessionId\":\"session-1\""))
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })
        assertEquals(FetchCapabilityStatus.SUPPORTED, engine.state.capability)
    }

    @Test
    fun bind_withPatterns_sendsPatternsInEnableParams() {
        engine.bindSession(session, patterns = listOf(
            RequestPattern(urlPattern = "*api/*", requestStage = "Request"),
            RequestPattern(urlPattern = "*cdn*", resourceType = "Script"),
        ))
        val msg = transport.sentMessages.last { it.contains("Fetch.enable") }
        assertTrue(msg.contains("\"patterns\""))
        assertTrue(msg.contains("\"urlPattern\":\"*api/*\""))
        assertTrue(msg.contains("\"requestStage\":\"Request\""))
        assertTrue(msg.contains("\"urlPattern\":\"*cdn*\""))
        assertTrue(msg.contains("\"resourceType\":\"Script\""))
    }

    @Test
    fun bind_withAuthRequests_sendsHandleAuthRequests() {
        engine.bindSession(session, authRequests = true)
        val msg = transport.sentMessages.last { it.contains("Fetch.enable") }
        assertTrue(msg.contains("\"handleAuthRequests\":true"))
    }

    @Test
    fun bind_unsupportedMethod_movesToUnsupported() {
        engine.bindSession(session)
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.UNSUPPORTED })
        assertEquals(FetchCapabilityStatus.UNSUPPORTED, engine.state.capability)
    }

    @Test
    fun bind_genericEnableError_movesToFailed() {
        engine.bindSession(session)
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"something broke"}}"""
        )
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.FAILED })
        assertEquals(FetchCapabilityStatus.FAILED, engine.state.capability)
    }

    @Test
    fun disable_sendsFetchDisableAndMovesToDisabled() {
        bind()
        engine.disable()
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.disable") } })
        val id = parseIdFor("Fetch.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.DISABLED })
        assertEquals(0, engine.state.pendingCount)
    }

    // ============ requestPaused routing ============

    @Test
    fun requestPaused_withFulfillRule_fulfillsMockResponse() {
        bind()
        ruleManager.upsert(MockResponseRuleSpec(
            id = "mock",
            urlPattern = "*example.com*",
            action = MockAction.FULFILL,
            statusCode = 201,
            statusText = "Created",
            responseHeaders = mapOf("Content-Type" to "application/json"),
            body = "{\"ok\":true}",
        ))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.fulfillRequest") } })
        val msg = transport.sentMessages.last { it.contains("Fetch.fulfillRequest") }
        assertTrue(msg.contains("\"requestId\":\"req-1\""))
        assertTrue(msg.contains("\"code\":201"))
        assertTrue(msg.contains("\"statusText\":\"Created\""))
        assertTrue(msg.contains("\"headers\":[{\"name\":\"Content-Type\",\"value\":\"application/json\"}]"))
        val expected = MockHttpResponse.base64Encode("{\"ok\":true}")
        assertTrue(msg.contains("\"body\":\"$expected\""))
        assertEquals("{\"ok\":true}", String(Base64.getDecoder().decode(expected)))
    }

    @Test
    fun requestPaused_withPreEncodedBody_doesNotDoubleEncode() {
        bind()
        val pre = MockHttpResponse.base64Encode("raw-bytes")
        ruleManager.upsert(MockResponseRuleSpec(
            id = "mock",
            urlPattern = "*example.com*",
            action = MockAction.FULFILL,
            body = pre,
            bodyIsBase64 = true,
        ))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.fulfillRequest") } })
        val msg = transport.sentMessages.last { it.contains("Fetch.fulfillRequest") }
        assertTrue(msg.contains("\"body\":\"$pre\""))
    }

    @Test
    fun requestPaused_withFailRule_failsWithExplicitReason() {
        bind()
        ruleManager.upsert(MockResponseRuleSpec(
            id = "fail",
            urlPattern = "*example.com*",
            action = MockAction.FAIL,
            failureReason = NetworkFailureReason.TimedOut,
        ))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })
        val msg = transport.sentMessages.last { it.contains("Fetch.failRequest") }
        assertTrue(msg.contains("\"requestId\":\"req-1\""))
        assertTrue(msg.contains("\"errorReason\":\"TimedOut\""))
    }

    @Test
    fun failRequest_dispatchesEveryNetworkFailureReason() {
        bind()
        for (reason in NetworkFailureReason.values()) {
            transport.resetSentMessages()
            val requestId = "req-${reason.name}"
            ruleManager.upsert(MockResponseRuleSpec(
                id = "fail-$requestId",
                urlPattern = "*$requestId*",
                action = MockAction.FAIL,
                failureReason = reason,
            ))
            deliverEvent("Fetch.requestPaused", pausedParams(requestId = requestId, url = "https://example.com/$requestId"))
            assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })
            val msg = transport.sentMessages.last { it.contains("Fetch.failRequest") }
            assertTrue("expected ${reason.cdpValue}", msg.contains("\"errorReason\":\"${reason.cdpValue}\""))
            val id = parseIdFor("Fetch.failRequest")
            transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
            ruleManager.remove("fail-$requestId")
        }
    }

    @Test
    fun networkFailureReason_mapsCdpValues() {
        assertEquals("Failed", NetworkFailureReason.Failed.cdpValue)
        assertEquals("Aborted", NetworkFailureReason.Aborted.cdpValue)
        assertEquals("TimedOut", NetworkFailureReason.TimedOut.cdpValue)
        assertEquals("AccessDenied", NetworkFailureReason.AccessDenied.cdpValue)
        assertEquals("ConnectionRefused", NetworkFailureReason.ConnectionRefused.cdpValue)
        assertEquals("ConnectionReset", NetworkFailureReason.ConnectionReset.cdpValue)
        assertEquals(NetworkFailureReason.Aborted, NetworkFailureReason.fromCdp("Aborted"))
        assertEquals(NetworkFailureReason.TimedOut, NetworkFailureReason.fromCdp("TimedOut"))
        assertEquals(NetworkFailureReason.ConnectionReset, NetworkFailureReason.fromCdp("ConnectionReset"))
        assertEquals(NetworkFailureReason.Failed, NetworkFailureReason.fromCdp("UnknownReason"))
    }

    @Test
    fun requestPaused_withoutRule_continuesRequest() {
        bind()
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.continueRequest") } })
        val msg = transport.sentMessages.last { it.contains("Fetch.continueRequest") }
        assertTrue(msg.contains("\"requestId\":\"req-1\""))
    }

    @Test
    fun requestPaused_withContinueRule_continuesRequest() {
        bind()
        ruleManager.upsert(MockResponseRuleSpec(
            id = "pass",
            urlPattern = "*example.com*",
            action = MockAction.CONTINUE,
        ))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.continueRequest") } })
        assertTrue(transport.sentMessages.none { it.contains("Fetch.fulfillRequest") })
        assertTrue(transport.sentMessages.none { it.contains("Fetch.failRequest") })
    }

    @Test
    fun requestPaused_forOtherSession_isIgnored() {
        bind()
        val other = SessionInfo("other-session", "target-9", "ws://test")
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-x"), sess = other)
        Thread.sleep(200)
        assertEquals(0, engine.state.interceptedCount)
        assertTrue(transport.sentMessages.none { it.contains("Fetch.continueRequest") })
    }

    @Test
    fun requestPaused_malformedPayload_isTolerated() {
        bind()
        deliverEvent("Fetch.requestPaused", "not-a-map")
        deliverEvent("Fetch.requestPaused", mapOf("no-id" to 1))
        Thread.sleep(200)
        assertEquals(0, engine.state.interceptedCount)
        assertEquals(FetchLifecyclePhase.READY, engine.state.phase)
    }

    @Test
    fun requestPaused_incrementsInterceptionCounters() {
        bind()
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-2"))
        assertTrue(waitFor { engine.state.interceptedCount == 2 })
        assertTrue(waitFor { engine.state.pendingCount == 2 })
    }

    @Test
    fun pausedRequest_pendingCountClearsAfterDispatch() {
        bind()
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { engine.state.pendingCount == 1 })
        val id = parseIdFor("Fetch.continueRequest")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.pendingCount == 0 })
    }

    @Test
    fun fulfillRequest_chromeError_reportsErrorAndClearsPending() {
        bind()
        ruleManager.upsert(MockResponseRuleSpec(id = "mock", urlPattern = "*example.com*", action = MockAction.FULFILL))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.fulfillRequest") } })
        val id = parseIdFor("Fetch.fulfillRequest")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"No request found"}}"""
        )
        assertTrue(waitFor { engine.state.pendingCount == 0 })
    }

    // ============ manual interception actions ============

    @Test
    fun continueWithAuth_buildsAuthChallengeParams() {
        bind()
        ruleManager.upsert(MockResponseRuleSpec(id = "c1", urlPattern = "*auth*", action = MockAction.CONTINUE))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-auth", url = "https://example.com/auth"))
        assertTrue(waitFor { engine.state.pendingCount == 1 })
        engine.continueWithAuth(
            "req-auth",
            ContinueWithAuth(response = "ProvideCredentials", username = "user", password = "pass"),
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.continueWithAuth") } })
        val msg = transport.sentMessages.last { it.contains("Fetch.continueWithAuth") }
        assertTrue(msg.contains("\"requestId\":\"req-auth\""))
        assertTrue(msg.contains("\"response\":\"ProvideCredentials\""))
        assertTrue(msg.contains("\"username\":\"user\""))
        assertTrue(msg.contains("\"password\":\"pass\""))
    }

    @Test
    fun fulfillRequest_reportsSentOnSuccess() {
        bind()
        val results = CopyOnWriteArrayList<FetchActionResult>()
        ruleManager.upsert(MockResponseRuleSpec(id = "c1", urlPattern = "*hold*", action = MockAction.CONTINUE))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-hold", url = "https://example.com/hold"))
        assertTrue(waitFor { engine.state.pendingCount == 1 })
        engine.fulfillRequest("req-hold", MockHttpResponse(code = 418)) { results.add(it) }
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.fulfillRequest") } })
        val id = parseIdFor("Fetch.fulfillRequest")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(FetchActionResult.SENT, results[0])
        assertTrue(waitFor { engine.state.pendingCount == 0 })
    }

    // ============ stale request / lifecycle handling ============

    @Test
    fun actionWithNoSession_returnsNoSession() {
        val results = CopyOnWriteArrayList<FetchActionResult>()
        engine.fulfillRequest("req-1", MockHttpResponse(code = 200)) { results.add(it) }
        assertEquals(FetchActionResult.NO_SESSION, results[0])
        assertTrue(transport.sentMessages.none { it.contains("Fetch.fulfillRequest") })
    }

    @Test
    fun actionOnUnpausedRequestId_returnsUnknownRequest() {
        bind()
        val results = CopyOnWriteArrayList<FetchActionResult>()
        engine.continueRequest("req-not-paused") { results.add(it) }
        assertEquals(FetchActionResult.UNKNOWN_REQUEST, results[0])
        assertTrue(transport.sentMessages.none { it.contains("Fetch.continueRequest") })
    }

    @Test
    fun staleRequestId_afterRebind_isRejectedWithoutLeaking() {
        bind(session)
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-old"))
        assertTrue(waitFor { engine.state.pendingCount == 1 })

        // Reconnect: re-bind to a fresh session clears all paused state.
        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        bind(newSession)
        assertEquals(0, engine.state.pendingCount)

        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<FetchActionResult>()
        engine.fulfillRequest("req-old", MockHttpResponse(code = 200, body = "stale")) { results.add(it) }
        assertEquals(FetchActionResult.UNKNOWN_REQUEST, results[0])
        assertTrue("stale request must not dispatch a command", transport.sentMessages.none { it.contains("Fetch.fulfillRequest") })
    }

    @Test
    fun staleRequestId_afterDetach_returnsNoSessionAndSendsNothing() {
        bind()
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-1"))
        assertTrue(waitFor { engine.state.pendingCount == 1 })

        engine.detach()
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.INACTIVE })
        assertNull(engine.session)
        assertEquals(0, engine.state.pendingCount)

        transport.resetSentMessages()
        val results = CopyOnWriteArrayList<FetchActionResult>()
        engine.failRequest("req-1", NetworkFailureReason.Aborted) { results.add(it) }
        assertEquals(FetchActionResult.NO_SESSION, results[0])
        assertTrue(transport.sentMessages.none { it.contains("Fetch.failRequest") })
    }

    @Test
    fun reconnect_pausedRequestsFromOldSessionDoNotRoute() {
        bind(session)
        val newSession = SessionInfo("session-3", "target-3", "ws://test")
        bind(newSession)

        // A stale requestPaused event for the OLD session must be ignored.
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-stale"), sess = session)
        Thread.sleep(200)
        assertEquals(0, engine.state.interceptedCount)

        // A fresh event for the new session is accepted and routed.
        ruleManager.upsert(MockResponseRuleSpec(id = "mock", urlPattern = "*example.com*", action = MockAction.FULFILL))
        deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-fresh"), sess = newSession)
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.fulfillRequest") } })
        val msg = transport.sentMessages.last { it.contains("Fetch.fulfillRequest") }
        assertTrue(msg.contains("\"requestId\":\"req-fresh\""))
    }

    @Test
    fun detach_doesNotThrowOnLateEventBurst() {
        bind()
        for (i in 1..5) {
            deliverEvent("Fetch.requestPaused", pausedParams(requestId = "req-$i"))
        }
        assertTrue(waitFor { engine.state.interceptedCount == 5 })
        engine.detach()
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.INACTIVE })
        assertEquals(0, engine.state.pendingCount)
    }
}