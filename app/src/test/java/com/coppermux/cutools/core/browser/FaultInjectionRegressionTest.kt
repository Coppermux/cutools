package com.coppermux.cutools.core.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkRequestStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Phase 19 Fault Injection Regression Suite.
 *
 * Exercises:
 *  1. HTTP 404 override decision and summary.
 *  2. Connection-drop construction without an invalid status code (no statusCode=0).
 *  3. Safe interception failure recovery (no process crashes).
 *  4. Historical HTTP 200 events remaining unchanged when armed rules exist.
 *  5. Mutually exclusive fault actions (dropConnection vs statusCode/mockBody).
 *  6. Exact URL matching and query parameter boundaries.
 *  7. Rule activation, removal, and restoration of unintercepted pass-through.
 */
class FaultInjectionRegressionTest {

    private fun createMockRequest(url: String, method: String = "GET"): WebResourceRequest {
        val req = mock<WebResourceRequest>()
        val uri = mock<Uri>()
        whenever(uri.toString()).thenReturn(url)
        whenever(req.url).thenReturn(uri)
        whenever(req.method).thenReturn(method)
        return req
    }

    // ================================================================
    // 1. HTTP 404 Override
    // ================================================================

    @Test
    fun http404Override_decisionAndSummary() {
        val engine = FaultInterceptorEngine()
        val rule = FaultRule(
            id = "fault-404",
            urlPattern = "https://example.com/api/missing",
            statusCode = 404,
        )
        engine.upsert(rule)

        assertEquals("HTTP 404", rule.summary)
        assertTrue(rule.isActive)

        val decision = engine.decisionFor("https://example.com/api/missing", "GET")
        assertEquals(FaultAction.OVERRIDE, decision.action)
        assertEquals(404, decision.statusCode)
        assertNull(decision.body)
    }

    // ================================================================
    // 2. Connection Drop without Invalid Status Code
    // ================================================================

    @Test
    fun connectionDrop_decisionAndSummary() {
        val engine = FaultInterceptorEngine()
        val rule = FaultRule(
            id = "fault-drop",
            urlPattern = "https://example.com/api/drop",
            dropConnection = true,
        )
        engine.upsert(rule)

        assertEquals("drop", rule.summary)
        assertTrue(rule.isActive)

        val decision = engine.decisionFor("https://example.com/api/drop", "POST")
        assertEquals(FaultAction.DROP, decision.action)
        assertNull(decision.statusCode)
        assertFalse(decision.shouldDelay)
    }

    @Test
    fun connectionDrop_delegatesToCdpFetch_andReturnsNullInWebView() {
        val eventStream = DashboardEventStream()
        val faultEngine = FaultInterceptorEngine()
        faultEngine.upsert(
            FaultRule(
                id = "drop-rule",
                urlPattern = "https://example.com/api/fail",
                dropConnection = true,
            )
        )
        val webViewEngine = WebViewEngine(eventStream, faultEngine)
        val request = createMockRequest("https://example.com/api/fail", "POST")

        val mockView = mock<android.webkit.WebView>()
        val response = webViewEngine.interceptorClient.shouldInterceptRequest(mockView, request)
        // In the CDP Fetch architecture, DROP is handled natively by CDP Fetch.failRequest(ConnectionReset),
        // so WebViewEngine.shouldInterceptRequest returns null without fabricating a stream or 502/504.
        assertNull("Drop rule must return null in WebView so CDP Fetch handles native reset", response)
    }

    // ================================================================
    // 3. Safe Interception Failures (No Process Crashes)
    // ================================================================

    @Test
    fun interceptionFailure_doesNotThrowOrCrash() {
        val eventStream = DashboardEventStream()
        val mockFaultEngine = mock<FaultInterceptorEngine>()
        whenever(mockFaultEngine.decisionFor(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenThrow(IllegalStateException("Simulated crash in fault resolver"))

        val webViewEngine = WebViewEngine(eventStream, mockFaultEngine)
        val request = createMockRequest("https://example.com/api/crash", "GET")

        // Must catch the exception and fall back to null safely
        val mockView = mock<android.webkit.WebView>()
        val response = webViewEngine.interceptorClient.shouldInterceptRequest(mockView, request)
        assertNull("Recoverable fault failure must return null fallback", response)
    }

    // ================================================================
    // 4. Historical HTTP 200 Events Remain Unchanged
    // ================================================================

    @Test
    fun historicalHttp200Events_remainUnchangedWhenArmedRulesExist() {
        val historicalUrl = "https://c2.piano.io/xbuilder/experience/execute?client=123"
        val historicalEvent = DashboardEvent(
            id = 1L,
            requestId = "req-1",
            category = DashboardEventCategory.NETWORK,
            severity = DashboardSeverity.SUCCESS,
            kind = "XHR/FETCH",
            url = historicalUrl,
            method = "POST",
            message = historicalUrl,
            statusCode = 200,
            statusText = "OK",
            timestamp = 1000L,
            resourceType = DashboardNetworkType.XHR_FETCH,
        )

        // Historical 200 event is NOT an applied fault
        assertFalse("Historical 200 event must not be flagged as fault-applied", historicalEvent.isFaultApplied)
        assertEquals(200, historicalEvent.statusCode)
        assertEquals("XHR/FETCH", historicalEvent.kind)

        // Arming a rule matching this URL
        val faultEngine = FaultInterceptorEngine()
        faultEngine.upsert(
            FaultRule(
                id = "rule-piano",
                urlPattern = historicalUrl,
                statusCode = 404,
            )
        )

        // Historical event itself remains strictly unchanged
        assertFalse("Arming a rule must not change historical event fault-applied state", historicalEvent.isFaultApplied)
        assertEquals(200, historicalEvent.statusCode)

        // In contrast, an actually injected event has isFaultApplied == true
        val injectedEvent = historicalEvent.copy(
            id = 2L,
            statusCode = 404,
            kind = "HTTP 404",
            detail = "fault injected",
            responseHeaders = mapOf("X-CuTools-Fault" to "injected"),
        )
        assertTrue("Actually injected event must be flagged as fault-applied", injectedEvent.isFaultApplied)
    }

    // ================================================================
    // 5. Mutually Exclusive Fault Actions
    // ================================================================

    @Test(expected = IllegalArgumentException::class)
    fun mutuallyExclusive_dropAndStatusCode_throwsIllegalArgument() {
        FaultRule(
            id = "invalid-1",
            urlPattern = "https://example.com/*",
            statusCode = 404,
            dropConnection = true,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun mutuallyExclusive_dropAndMockBody_throwsIllegalArgument() {
        FaultRule(
            id = "invalid-2",
            urlPattern = "https://example.com/*",
            dropConnection = true,
            mockBody = """{"error": "drop"}""",
        )
    }

    // ================================================================
    // 6. Exact URL Matching and Query Parameters
    // ================================================================

    @Test
    fun exactUrlMatching_differentiatesDynamicQueryParams() {
        val engine = FaultInterceptorEngine()
        val targetUrl = "https://c2.piano.io/xbuilder/experience/execute?client=abc&t=100"
        engine.upsert(
            FaultRule(
                id = "rule-exact",
                urlPattern = targetUrl,
                statusCode = 404,
            )
        )

        // Matches identical URL
        assertTrue(engine.hasActiveRuleFor(targetUrl, "POST"))
        assertEquals(404, engine.decisionFor(targetUrl, "POST").statusCode)

        // Does not match when timestamp differs (exact match, not silent wildcarding)
        val nextUrl = "https://c2.piano.io/xbuilder/experience/execute?client=abc&t=101"
        assertFalse(engine.hasActiveRuleFor(nextUrl, "POST"))
        assertEquals(FaultAction.CONTINUE, engine.decisionFor(nextUrl, "POST").action)

        // Explicit glob pattern matches across changing query params
        engine.upsert(
            FaultRule(
                id = "rule-glob",
                urlPattern = "https://c2.piano.io/xbuilder/experience/execute?*",
                statusCode = 404,
            )
        )
        assertTrue(engine.hasActiveRuleFor(nextUrl, "POST"))
    }

    // ================================================================
    // 7. Enabling, Removing, and Restoring Normal Traffic
    // ================================================================

    @Test
    fun enablingAndRemovingFaultRules_restoresNormalFlow() {
        val engine = FaultInterceptorEngine()
        val url = "https://example.com/api/users"

        // Initially normal
        assertEquals(FaultAction.CONTINUE, engine.decisionFor(url, "GET").action)

        // Arm rule
        engine.upsert(FaultRule(id = "user-rule", urlPattern = url, statusCode = 503))
        assertEquals(FaultAction.OVERRIDE, engine.decisionFor(url, "GET").action)
        assertEquals(503, engine.decisionFor(url, "GET").statusCode)

        // Remove rule
        assertTrue(engine.remove("user-rule"))
        val afterRemoval = engine.decisionFor(url, "GET")
        assertEquals(FaultAction.CONTINUE, afterRemoval.action)
        assertNull(afterRemoval.statusCode)
        assertEquals(0, afterRemoval.delayMs)
    }

    // ================================================================
    // 8. Trailing Slash Tolerance
    // ================================================================

    @Test
    fun trailingSlashTolerance_matchesCleanly() {
        val engine = FaultInterceptorEngine()
        engine.upsert(FaultRule(id = "root-rule", urlPattern = "https://example.com", statusCode = 404))

        assertTrue("Root rule should match URL with trailing slash", engine.hasActiveRuleFor("https://example.com/", "GET"))
        assertTrue("Root rule should match URL without trailing slash", engine.hasActiveRuleFor("https://example.com", "GET"))

        val engine2 = FaultInterceptorEngine()
        engine2.upsert(FaultRule(id = "slash-rule", urlPattern = "https://example.com/", statusCode = 404))
        assertTrue("Slash rule should match URL without trailing slash", engine2.hasActiveRuleFor("https://example.com", "GET"))
        assertTrue("Slash rule should match URL with trailing slash", engine2.hasActiveRuleFor("https://example.com/", "GET"))
    }

    // ================================================================
    // 9. CDP Lowercase Header and Drop Fault Feed Classification
    // ================================================================

    @Test
    fun cdpNetworkRequest_identifiesFaultWithCaseInsensitiveHeader() {
        val model = NetworkRequestModel(
            requestId = "req-cdp-404",
            sessionId = "session-1",
            url = "https://example.com/api/test",
            method = "GET",
            stage = NetworkRequestStage.RESPONSE_RECEIVED,
            responseStatus = 404,
            responseStatusText = "Not Found",
            responseHeaders = mapOf("x-cutools-fault" to "injected", "content-type" to "text/plain"),
        )
        val event = DashboardEventClassifier.buildFromNetworkRequest(model)
        assertTrue("Event must be flagged as fault applied with lowercase header", event.isFaultApplied)
        assertEquals("HTTP 404", event.kind)
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("fault injected", event.detail)
    }

    @Test
    fun cdpNetworkRequest_identifiesConnectionDropWithConnectionReset() {
        val model = NetworkRequestModel(
            requestId = "req-cdp-drop",
            sessionId = "session-1",
            url = "https://example.com/api/test",
            method = "GET",
            stage = NetworkRequestStage.FAILED,
            loadingError = "net::ERR_CONNECTION_RESET",
        )
        val event = DashboardEventClassifier.buildFromNetworkRequest(model)
        assertTrue("Event must be flagged as fault applied for connection drop", event.isFaultApplied)
        assertEquals("CONNECTION DROP", event.kind)
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("net::ERR_CONNECTION_RESET", event.detail)
    }

    @Test
    fun cdpNetworkRequest_doesNotReportConnectionDropForErrFailedWithoutConnectionReset() {
        val model = NetworkRequestModel(
            requestId = "req-cdp-failed",
            sessionId = "session-1",
            url = "https://example.com/api/test",
            method = "GET",
            stage = NetworkRequestStage.FAILED,
            loadingError = "net::ERR_FAILED",
            responseHeaders = mapOf("x-cutools-fault" to "drop"),
        )
        val event = DashboardEventClassifier.buildFromNetworkRequest(model)
        // Requirement 9: Never report a successful connection drop unless CDP interception is armed
        // and the request is actually failed with ERR_CONNECTION_RESET.
        org.junit.Assert.assertNotEquals("CONNECTION DROP", event.kind)
        assertEquals("FAILED", event.kind)
    }
}
