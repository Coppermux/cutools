package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.browser.DashboardEventClassifier
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Comprehensive regression suite for CuTools Cut Connection repair via CDP Fetch.failRequest(ConnectionReset).
 *
 * Verifies:
 * 1. Targeted Fetch.enable arming only when active drop rules exist.
 * 2. Fetch.failRequest dispatches errorReason: ConnectionReset for matching requests.
 * 3. HTTP method filtering consistency between FaultInterceptorEngine and FetchInterceptionEngine.
 * 4. URL pattern matching with trailing-slash tolerance.
 * 5. Fetch.disable dispatched when drop rules are removed or cleared, restoring native browsing.
 * 6. CDP session disconnection and target re-attachment lifecycle safety.
 * 7. Preservation of HTTP 404 status overrides in WebViewEngine without CDP Fetch interference.
 * 8. Rejection of fake connection drops when CDP did not fail the request.
 */
class FetchConnectionResetFaultIntegrationTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var faultEngine: FaultInterceptorEngine
    private lateinit var engine: FetchInterceptionEngine

    private val session = SessionInfo("session-test-1", "target-test-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        faultEngine = FaultInterceptorEngine()
        engine = FetchInterceptionEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            faultInterceptorEngine = faultEngine,
        )
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
        throw AssertionError("Timed out waiting for transport message containing '$method'")
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun pausedParams(
        requestId: String,
        url: String,
        method: String = "GET",
    ): Map<String, Any?> = mapOf(
        "requestId" to requestId,
        "documentURL" to url,
        "request" to mapOf("url" to url, "method" to method),
    )

    private fun deliverEvent(method: String, params: Any?, sess: SessionInfo = session) {
        router.handleEvent(Event(method, params, sessionId = sess.sessionId, targetId = sess.targetId))
    }

    // ================================================================
    // 1. Rule Activation & Targeted Interception
    // ================================================================

    @Test
    fun bindSession_withoutDropRules_doesNotEnableFetch() {
        engine.bindSession(session)
        assertFalse(
            "Fetch.enable must not be sent when no drop rules exist",
            transport.sentMessages.any { it.contains("Fetch.enable") }
        )
        assertEquals(FetchLifecyclePhase.INACTIVE, engine.state.phase)
        assertEquals(FetchCapabilityStatus.SUPPORTED, engine.state.capability)
    }

    @Test
    fun applyDropRule_armsTargetedFetchEnable() {
        engine.bindSession(session)

        faultEngine.upsert(
            FaultRule(
                id = "drop-1",
                urlPattern = "https://example.com/api/cut",
                dropConnection = true,
            )
        )

        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.enable") } })
        val enableMsg = transport.sentMessages.last { it.contains("Fetch.enable") }
        assertTrue("Must target the specific URL pattern", enableMsg.contains("https://example.com/api/cut"))

        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })
    }

    // ================================================================
    // 2. Matching & ConnectionReset Execution
    // ================================================================

    @Test
    fun requestPaused_forMatchingDropRule_executesFailRequestWithConnectionReset() {
        engine.bindSession(session)
        faultEngine.upsert(
            FaultRule(
                id = "drop-api",
                urlPattern = "*example.com/api/reset*",
                dropConnection = true,
            )
        )
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        transport.resetSentMessages()
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-drop-101", url = "https://example.com/api/reset/data", method = "GET")
        )

        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })
        val failMsg = transport.sentMessages.last { it.contains("Fetch.failRequest") }
        assertTrue("failRequest must carry requestId", failMsg.contains("\"requestId\":\"req-drop-101\""))
        assertTrue("errorReason must be ConnectionReset", failMsg.contains("\"errorReason\":\"ConnectionReset\""))
    }

    // ================================================================
    // 3. HTTP Method Filtering & Trailing Slash Matching Consistency
    // ================================================================

    @Test
    fun httpMethodFiltering_continuesNonMatchingMethod_failsMatchingMethod() {
        engine.bindSession(session)
        faultEngine.upsert(
            FaultRule(
                id = "drop-post-only",
                urlPattern = "*example.com/api/submit*",
                method = "POST",
                dropConnection = true,
            )
        )
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        // 1. Non-matching method GET should continue untouched
        transport.resetSentMessages()
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-get-1", url = "https://example.com/api/submit", method = "GET")
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.continueRequest") } })
        assertFalse(transport.sentMessages.any { it.contains("Fetch.failRequest") })

        // 2. Matching method POST should fail with ConnectionReset
        transport.resetSentMessages()
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-post-1", url = "https://example.com/api/submit", method = "POST")
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })
        val failMsg = transport.sentMessages.last { it.contains("Fetch.failRequest") }
        assertTrue(failMsg.contains("\"errorReason\":\"ConnectionReset\""))
    }

    @Test
    fun trailingSlashTolerance_matchesConsistentWithFaultInterceptorEngine() {
        engine.bindSession(session)
        faultEngine.upsert(
            FaultRule(
                id = "drop-slash",
                urlPattern = "https://example.com/items",
                dropConnection = true,
            )
        )
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        transport.resetSentMessages()
        // Incoming URL has trailing slash https://example.com/items/
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-slash-1", url = "https://example.com/items/", method = "GET")
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })
        val failMsg = transport.sentMessages.last { it.contains("Fetch.failRequest") }
        assertTrue(failMsg.contains("\"errorReason\":\"ConnectionReset\""))
    }

    // ================================================================
    // 4. Rule Removal & Clearing Disables Interception
    // ================================================================

    @Test
    fun removeDropRule_whenNoRulesRemain_disablesFetchInterception() {
        engine.bindSession(session)
        faultEngine.upsert(
            FaultRule(id = "r1", urlPattern = "*target*", dropConnection = true)
        )
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        transport.resetSentMessages()
        faultEngine.remove("r1")

        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.disable") } })
        val disableId = parseIdFor("Fetch.disable")
        transport.deliverMessage("""{"id":$disableId,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.DISABLED })
    }

    @Test
    fun clearFaultRules_disablesFetchInterceptionAndRestoresNormalBrowsing() {
        engine.bindSession(session)
        faultEngine.upsert(FaultRule(id = "r1", urlPattern = "*drop1*", dropConnection = true))
        faultEngine.upsert(FaultRule(id = "r2", urlPattern = "*drop2*", dropConnection = true))
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        transport.resetSentMessages()
        faultEngine.clear()

        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.disable") } })
        val disableId = parseIdFor("Fetch.disable")
        transport.deliverMessage("""{"id":$disableId,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.DISABLED })
    }

    // ================================================================
    // 5. CDP Attachment, Disconnection and Session Replacement
    // ================================================================

    @Test
    fun cdpDisconnection_clearsSessionSafely() {
        engine.bindSession(session)
        faultEngine.upsert(FaultRule(id = "r1", urlPattern = "*drop*", dropConnection = true))
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        engine.onConnectionLost()
        assertNull(engine.session)
        assertEquals(FetchLifecyclePhase.INACTIVE, engine.state.phase)
    }

    @Test
    fun sessionReplacement_reArmsActiveDropRulesOnNewSession() {
        faultEngine.upsert(FaultRule(id = "r1", urlPattern = "*drop*", dropConnection = true))
        engine.bindSession(session)
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        // Detach old session
        engine.detach()
        assertEquals(FetchLifecyclePhase.INACTIVE, engine.state.phase)

        // Bind to replacement session
        transport.resetSentMessages()
        val newSession = SessionInfo("session-replacement-2", "target-replacement-2", "ws://test")
        engine.bindSession(newSession)

        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.enable") } })
        val enableMsg = transport.sentMessages.last { it.contains("Fetch.enable") }
        assertTrue("Must enable Fetch on the new replacement session", enableMsg.contains("\"sessionId\":\"${newSession.sessionId}\""))
    }

    // ================================================================
    // 6. Preservation of HTTP 404 Status Overrides
    // ================================================================

    @Test
    fun http404Override_doesNotArmFetchInterception_andServesInWebViewEngine() {
        engine.bindSession(session)
        // Upsert non-drop HTTP 404 rule
        faultEngine.upsert(
            FaultRule(
                id = "404-rule",
                urlPattern = "https://example.com/not-found",
                statusCode = 404,
                dropConnection = false,
            )
        )

        // Fetch must NOT be enabled
        assertFalse(transport.sentMessages.any { it.contains("Fetch.enable") })
        assertEquals(FetchLifecyclePhase.INACTIVE, engine.state.phase)

        // WebViewEngine shouldInterceptRequest serves the HTTP 404 response
        val eventStream = DashboardEventStream()
        val webViewEngine = WebViewEngine(eventStream, faultEngine)
        val mockRequest = mock<android.webkit.WebResourceRequest>()
        val mockUri = mock<android.net.Uri>()
        whenever(mockUri.toString()).thenReturn("https://example.com/not-found")
        whenever(mockRequest.url).thenReturn(mockUri)
        whenever(mockRequest.method).thenReturn("GET")

        // Fault decision for 404 is OVERRIDE
        val decision = faultEngine.decisionFor("https://example.com/not-found", "GET")
        assertEquals(com.coppermux.cutools.core.browser.FaultAction.OVERRIDE, decision.action)
        assertEquals(404, decision.statusCode)

        val response = webViewEngine.interceptorClient.shouldInterceptRequest(mock<android.webkit.WebView>(), mockRequest)
        assertNotNull("HTTP 404 rule must be served by WebViewEngine", response)
    }

    // ================================================================
    // 7. Verification that Never Reports Fake Drop
    // ================================================================

    @Test
    fun classifier_neverReportsConnectionDrop_unlessErrConnectionResetIsPresent() {
        // Failed with ERR_FAILED (e.g. from an unhandled socket issue or old stream)
        val failedGeneric = com.coppermux.cutools.core.network.NetworkRequestModel(
            requestId = "req-failed-1",
            sessionId = "session-1",
            url = "https://example.com/test",
            method = "GET",
            stage = com.coppermux.cutools.core.network.NetworkRequestStage.FAILED,
            loadingError = "net::ERR_FAILED",
        )
        val eventGeneric = DashboardEventClassifier.buildFromNetworkRequest(failedGeneric)
        assertNotEquals("CONNECTION DROP", eventGeneric.kind)
        assertEquals("FAILED", eventGeneric.kind)

        // Failed with genuine ERR_CONNECTION_RESET
        val failedReset = com.coppermux.cutools.core.network.NetworkRequestModel(
            requestId = "req-failed-2",
            sessionId = "session-1",
            url = "https://example.com/test",
            method = "GET",
            stage = com.coppermux.cutools.core.network.NetworkRequestStage.FAILED,
            loadingError = "net::ERR_CONNECTION_RESET",
        )
        val eventReset = DashboardEventClassifier.buildFromNetworkRequest(failedReset)
        assertEquals("CONNECTION DROP", eventReset.kind)
        assertTrue(eventReset.isFaultApplied)
    }

    // ================================================================
    // 8. Corrections Verification
    // ================================================================

    @Test
    fun requestPattern_serializesResourceTypeKey() {
        val pattern = RequestPattern(urlPattern = "https://en.wikipedia.org", resourceType = "Document", requestStage = "Request")
        val params = pattern.toParams()
        assertEquals("Document", params["resourceType"])
        assertNull("Old 'type' key must not be present in CDP params", params["type"])
        assertEquals("https://en.wikipedia.org", params["urlPattern"])
        assertEquals("Request", params["requestStage"])
    }

    @Test
    fun bareHostname_normalizesWithoutBroadeningToEntireDomain() {
        val rule = FaultRule(
            id = "wiki-page",
            urlPattern = "en.wikipedia.org/wiki/Main_Page",
            dropConnection = true,
        )

        // Matching: exact page matches under https and http
        assertTrue(FaultInterceptorEngine.matches(rule, "https://en.wikipedia.org/wiki/Main_Page", "GET"))
        assertTrue(FaultInterceptorEngine.matches(rule, "http://en.wikipedia.org/wiki/Main_Page", "GET"))
        assertTrue(FaultInterceptorEngine.matches(rule, "https://en.wikipedia.org/wiki/Main_Page/", "GET"))

        // Does NOT broaden to other pages or subresources
        assertFalse(FaultInterceptorEngine.matches(rule, "https://en.wikipedia.org/wiki/Other_Page", "GET"))
        assertFalse(FaultInterceptorEngine.matches(rule, "https://en.wikipedia.org/static/favicon.ico", "GET"))

        // Patterns generated: only targeted page variants, no domain wildcards
        val patterns = engine.patternsFor(rule)
        assertTrue(patterns.any { it.urlPattern == "en.wikipedia.org/wiki/Main_Page" })
        assertTrue(patterns.any { it.urlPattern == "https://en.wikipedia.org/wiki/Main_Page" })
        assertTrue(patterns.any { it.urlPattern == "https://en.wikipedia.org/wiki/Main_Page/" })
        assertFalse("Must not generate broad domain wildcards", patterns.any { it.urlPattern?.contains("*") == true })
    }

    @Test
    fun nullMethod_matchesAnyHttpMethod() {
        val rule = FaultRule(
            id = "drop-any-method",
            urlPattern = "https://en.wikipedia.org/wiki/Main_Page",
            method = null, // ALL methods
            dropConnection = true,
        )

        assertTrue(FaultInterceptorEngine.matches(rule, "https://en.wikipedia.org/wiki/Main_Page", "GET"))
        assertTrue(FaultInterceptorEngine.matches(rule, "https://en.wikipedia.org/wiki/Main_Page", "POST"))
        assertTrue(FaultInterceptorEngine.matches(rule, "https://en.wikipedia.org/wiki/Main_Page", "HEAD"))
    }

    // ================================================================
    // 9. Wikipedia Address-Bar Navigation and Pull-to-Refresh Verification
    // ================================================================

    @Test
    fun wikipediaNavigationAndPullToRefresh_dropConnectionProducesConnectionReset() {
        engine.bindSession(session)
        // User navigates to Wikipedia and arms committed URL drop rule
        faultEngine.upsert(
            FaultRule(
                id = "wiki-drop",
                urlPattern = "https://www.wikipedia.org/",
                dropConnection = true,
            )
        )
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        // 1. Address-bar navigation to committed Wikipedia page
        transport.resetSentMessages()
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-nav-1", url = "https://www.wikipedia.org/", method = "GET")
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })
        val failNav = transport.sentMessages.last { it.contains("Fetch.failRequest") }
        assertTrue(failNav.contains("\"requestId\":\"req-nav-1\""))
        assertTrue(failNav.contains("\"errorReason\":\"ConnectionReset\""))

        // 2. Pull-to-refresh on committed page / subpath
        transport.resetSentMessages()
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-refresh-1", url = "https://www.wikipedia.org/wiki/Main_Page", method = "GET")
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })
        val failRefresh = transport.sentMessages.last { it.contains("Fetch.failRequest") }
        assertTrue(failRefresh.contains("\"requestId\":\"req-refresh-1\""))
        assertTrue(failRefresh.contains("\"errorReason\":\"ConnectionReset\""))

        // 3. Clear restores normal browsing
        transport.resetSentMessages()
        faultEngine.clear()
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.disable") } })
        val disableId = parseIdFor("Fetch.disable")
        transport.deliverMessage("""{"id":$disableId,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.DISABLED })
    }

    @Test
    fun pageSpecificRule_doesNotAffectUnrelatedPages_andContinuesUntouched() {
        engine.bindSession(session)
        faultEngine.upsert(
            FaultRule(
                id = "page-rule",
                urlPattern = "https://en.wikipedia.org/wiki/Main_Page",
                dropConnection = true,
            )
        )
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${session.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        // Targeted page is dropped
        transport.resetSentMessages()
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-target", url = "https://en.wikipedia.org/wiki/Main_Page", method = "GET")
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.failRequest") } })

        // Unrelated page on same host is continued untouched
        transport.resetSentMessages()
        deliverEvent(
            "Fetch.requestPaused",
            pausedParams(requestId = "req-unrelated", url = "https://en.wikipedia.org/wiki/Other_Article", method = "GET")
        )
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Fetch.continueRequest") } })
        assertFalse(transport.sentMessages.any { it.contains("Fetch.failRequest") })
    }
}
