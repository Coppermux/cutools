package com.coppermux.cutools.core.browser

import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkRequestStage
import com.coppermux.cutools.core.network.ResourceTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 18 — pure unit tests for the dashboard interceptor stream parser:
 * severity derivation, network kind inference, and event construction. The
 * classifier carries no Android dependency, so every classification rule is
 * verified deterministically on the JVM.
 */
class DashboardEventClassifierTest {

    // ================================================================
    // HTTP severity derivation
    // ================================================================

    @Test
    fun severityForHttpStatus_isGreenFor2xx() {
        listOf(200, 201, 204, 299).forEach { status ->
            assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForHttpStatus(status))
        }
    }

    @Test
    fun severityForHttpStatus_isNeutralForInformationalAndMissing() {
        assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForHttpStatus(101))
        assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForHttpStatus(null))
    }

    @Test
    fun severityForHttpStatus_isWarningFor3xx() {
        listOf(301, 302, 304, 399).forEach { status ->
            assertEquals(DashboardSeverity.WARNING, DashboardEventClassifier.severityForHttpStatus(status))
        }
    }

    @Test
    fun severityForHttpStatus_isErrorFor4xxAnd5xx() {
        listOf(400, 401, 404, 500, 503, 599).forEach { status ->
            assertEquals(DashboardSeverity.ERROR, DashboardEventClassifier.severityForHttpStatus(status))
        }
    }

    // ================================================================
    // Console severity derivation
    // ================================================================

    @Test
    fun severityForConsoleLevel_mapsLevelsToSeverity() {
        assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForConsoleLevel("log"))
        assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForConsoleLevel("info"))
        assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForConsoleLevel("debug"))
        assertEquals(DashboardSeverity.WARNING, DashboardEventClassifier.severityForConsoleLevel("warning"))
        assertEquals(DashboardSeverity.ERROR, DashboardEventClassifier.severityForConsoleLevel("error"))
        assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForConsoleLevel("tip"))
        assertEquals(DashboardSeverity.SUCCESS, DashboardEventClassifier.severityForConsoleLevel(null))
    }

    // ================================================================
    // Network kind inference
    // ================================================================

    @Test
    fun networkType_mainFrameRequest_isDocument() {
        assertEquals(
            DashboardNetworkType.DOCUMENT,
            DashboardEventClassifier.networkType("https://example.com/", "GET", isForMainFrame = true),
        )
    }

    @Test
    fun networkType_asyncMethods_areXhrFetch() {
        listOf("POST", "PUT", "PATCH", "DELETE", "post", "Put").forEach { method ->
            assertEquals(
                DashboardNetworkType.XHR_FETCH,
                DashboardEventClassifier.networkType("https://api.example.com/items", method, isForMainFrame = false),
            )
        }
    }

    @Test
    fun networkType_urlTail_infersStylesheetScriptImageFont() {
        assertEquals(
            DashboardNetworkType.STYLESHEET,
            DashboardEventClassifier.networkType("https://site.com/app.css", "GET", isForMainFrame = false),
        )
        assertEquals(
            DashboardNetworkType.SCRIPT,
            DashboardEventClassifier.networkType("https://site.com/bundle.js?ver=1", "GET", isForMainFrame = false),
        )
        assertEquals(
            DashboardNetworkType.IMAGE,
            DashboardEventClassifier.networkType("https://site.com/logo.png", "GET", isForMainFrame = false),
        )
        assertEquals(
            DashboardNetworkType.FONT,
            DashboardEventClassifier.networkType("https://site.com/fonts/inter.woff2", "GET", isForMainFrame = false),
        )
    }

    @Test
    fun networkType_unknownUrl_isPlainRequest() {
        val type = DashboardEventClassifier.networkType("https://api.example.com/unknown", "GET", isForMainFrame = false)
        assertEquals(DashboardNetworkType.REQUEST, type)
        assertEquals(DashboardNetworkType.REQUEST, DashboardEventClassifier.networkType(null, null, false))
    }

    // ================================================================
    // Event builders
    // ================================================================

    @Test
    fun buildNetworkRequestEvent_stampsNetworkMetadata() {
        val event = DashboardEventClassifier.buildNetworkRequestEvent(
            url = "https://example.com/app.js",
            method = "GET",
            isForMainFrame = false,
            timestamp = 1_000,
        )
        assertEquals(DashboardEventCategory.NETWORK, event.category)
        assertEquals(DashboardSeverity.SUCCESS, event.severity)
        assertEquals("SCRIPT", event.kind)
        assertEquals("https://example.com/app.js", event.url)
        assertEquals("GET", event.method)
        assertNull(event.statusCode)
        assertFalse(event.isXhrFetch)
        assertFalse(event.isError)
    }

    @Test
    fun buildNetworkRequestEvent_redirect_isWarningWith3xxStatus() {
        val event = DashboardEventClassifier.buildNetworkRequestEvent(
            url = "https://example.com/old",
            method = "GET",
            isForMainFrame = true,
            timestamp = 1_000,
            isRedirect = true,
        )
        assertEquals(DashboardSeverity.WARNING, event.severity)
        assertEquals(301, event.statusCode)
    }

    @Test
    fun buildHttpErrorEvent_isRedWithStatus() {
        val event = DashboardEventClassifier.buildHttpErrorEvent(
            url = "https://example.com/missing",
            method = "GET",
            statusCode = 404,
            statusText = "Not Found",
            timestamp = 2_000,
        )
        assertEquals(DashboardEventCategory.NETWORK, event.category)
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("HTTP 404", event.kind)
        assertEquals(404, event.statusCode)
        assertEquals("Not Found", event.statusText)
        assertTrue(event.isHttpError)
        assertTrue(event.isError)
    }

    @Test
    fun buildNetworkFailureEvent_isRed() {
        val event = DashboardEventClassifier.buildNetworkFailureEvent(
            url = "https://example.com/",
            detail = "net::ERR_NAME_NOT_RESOLVED",
            timestamp = 3_000,
        )
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("NETWORK ERROR", event.kind)
        assertEquals("net::ERR_NAME_NOT_RESOLVED", event.detail)
        assertNull(event.statusCode)
    }

    @Test
    fun buildConsoleEvent_levelsClassifyKindAndSeverity() {
        val log = DashboardEventClassifier.buildConsoleEvent("log", "hello", 4_000, sourceUrl = "https://example.com/app.js")
        assertEquals(DashboardEventCategory.CONSOLE, log.category)
        assertEquals(DashboardSeverity.SUCCESS, log.severity)
        assertEquals("console.log", log.kind)

        val warn = DashboardEventClassifier.buildConsoleEvent("warning", "deprecated API", 5_000)
        assertEquals(DashboardSeverity.WARNING, warn.severity)
        assertEquals("console.warning", warn.kind)
        assertTrue(warn.isWarning)

        val error = DashboardEventClassifier.buildConsoleEvent("error", "boom", 6_000)
        assertEquals(DashboardSeverity.ERROR, error.severity)
        assertEquals("console.error", error.kind)
        assertTrue(error.isError)
    }

    @Test
    fun buildRuntimeExceptionEvent_isRedJsException() {
        val event = DashboardEventClassifier.buildRuntimeExceptionEvent(
            message = "ReferenceError: x is not defined",
            timestamp = 7_000,
            sourceUrl = "https://example.com/app.js",
            lineNumber = 42,
        )
        assertEquals(DashboardEventCategory.CONSOLE, event.category)
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("JS exception", event.kind)
        assertEquals("ReferenceError: x is not defined", event.message)
        assertEquals(42, event.detail?.substringAfter("line ")?.toInt())
    }

    @Test
    fun buildDocumentFinishedEvent_carriesDuration() {
        val event = DashboardEventClassifier.buildDocumentFinishedEvent(
            url = "https://example.com/",
            timestamp = 8_000,
            durationMs = 124,
        )
        assertEquals(DashboardSeverity.SUCCESS, event.severity)
        assertEquals(DashboardNetworkType.DOCUMENT, event.resourceType)
        assertEquals("DOCUMENT", event.kind)
        assertEquals(200, event.statusCode)
        assertEquals(124L, event.durationMs)
    }

    // ================================================================
    // Phase 19 — payload inspection + fault event builders
    // ================================================================

    @Test
    fun buildNetworkRequestEvent_carriesRequestHeaders() {
        val event = DashboardEventClassifier.buildNetworkRequestEvent(
            url = "https://api.example.com/items",
            method = "POST",
            isForMainFrame = false,
            timestamp = 9_000,
            requestHeaders = mapOf("Authorization" to "Bearer tok", "X-Trace" to "abc"),
        )
        assertEquals(mapOf("Authorization" to "Bearer tok", "X-Trace" to "abc"), event.requestHeaders)
        assertFalse(event.requestHeaders.isEmpty())
    }

    @Test
    fun buildHttpErrorEvent_carriesResponseHeaders() {
        val event = DashboardEventClassifier.buildHttpErrorEvent(
            url = "https://example.com/missing",
            method = "GET",
            statusCode = 404,
            statusText = "Not Found",
            timestamp = 10_000,
            responseHeaders = mapOf("Server" to "nginx"),
        )
        assertEquals(mapOf("Server" to "nginx"), event.responseHeaders)
    }

    @Test
    fun buildFaultResponseEvent_4xxIsRedHttpError() {
        val event = DashboardEventClassifier.buildFaultResponseEvent(
            url = "https://api.example.com/items",
            method = "GET",
            statusCode = 503,
            statusText = "Service Unavailable",
            timestamp = 11_000,
            responseHeaders = mapOf("X-CuTools-Fault" to "injected"),
            responseBody = """{"error":"down"}""",
        )
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("HTTP 503", event.kind)
        assertEquals(503, event.statusCode)
        assertEquals("fault injected", event.detail)
        assertEquals(mapOf("X-CuTools-Fault" to "injected"), event.responseHeaders)
        assertEquals("""{"error":"down"}""", event.responseBody)
        assertTrue(event.isHttpError)
        assertTrue(event.isError)
    }

    @Test
    fun buildFaultResponseEvent_200MockBodyBadgesFaultMock() {
        val event = DashboardEventClassifier.buildFaultResponseEvent(
            url = "https://api.example.com/health",
            method = "GET",
            statusCode = 200,
            statusText = "OK",
            timestamp = 12_000,
            responseBody = """{"ok":false}""",
        )
        assertEquals(DashboardSeverity.SUCCESS, event.severity)
        assertEquals("FAULT MOCK", event.kind)
        assertEquals(200, event.statusCode)
        assertEquals("mock body injected", event.detail)
        assertFalse(event.isError)
        assertEquals("""{"ok":false}""", event.responseBody)
    }

    @Test
    fun buildFaultDropEvent_isRedConnectionFailure() {
        val event = DashboardEventClassifier.buildFaultDropEvent(
            url = "https://api.example.com/items",
            method = "GET",
            timestamp = 13_000,
        )
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("CONNECTION DROP", event.kind)
        assertNull(event.statusCode)
        assertTrue(event.detail.orEmpty().contains("ERR_CONNECTION_RESET"))
    }

    // ================================================================
    // CDP NetworkRequestModel translation
    // ================================================================

    @Test
    fun buildFromNetworkRequest_preservesCdpRequestIdAndGenuineHeaders() {
        val model = NetworkRequestModel(
            requestId = "cdp-req-1234.56",
            stage = NetworkRequestStage.RESPONSE_RECEIVED,
            sessionId = "session-1",
            url = "https://api.example.com/data.json",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer secret", "Accept" to "application/json"),
            responseHeaders = mapOf("Content-Type" to "application/json; charset=utf-8", "Server" to "Kestrel"),
            responseStatus = 200,
            responseStatusText = "OK",
            resourceType = "XHR",
            postData = """{"query":"test"}""",
            timestamp = 1700000.123,
            timing = ResourceTiming(
                receiveHeadersEnd = 85.0,
            ),
        )

        val event = DashboardEventClassifier.buildFromNetworkRequest(model)

        assertEquals("cdp-req-1234.56", event.requestId)
        assertEquals(DashboardSeverity.SUCCESS, event.severity)
        assertEquals("XHR/FETCH", event.kind)
        assertEquals("https://api.example.com/data.json", event.url)
        assertEquals("POST", event.method)
        assertEquals(200, event.statusCode)
        assertEquals("OK", event.statusText)
        assertEquals(mapOf("Authorization" to "Bearer secret", "Accept" to "application/json"), event.requestHeaders)
        assertEquals(mapOf("Content-Type" to "application/json; charset=utf-8", "Server" to "Kestrel"), event.responseHeaders)
        assertEquals("""{"query":"test"}""", event.requestBody)
        assertEquals(85L, event.durationMs)
        assertEquals(1700000123L, event.timestamp)
    }

    @Test
    fun buildFromNetworkRequest_4xxAnd5xxAreErrors() {
        val model = NetworkRequestModel(
            requestId = "cdp-err-1",
            stage = NetworkRequestStage.RESPONSE_RECEIVED,
            sessionId = "session-1",
            url = "https://api.example.com/missing",
            method = "GET",
            responseStatus = 404,
            responseStatusText = "Not Found",
            responseHeaders = mapOf("Content-Type" to "text/plain"),
        )

        val event = DashboardEventClassifier.buildFromNetworkRequest(model)

        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("HTTP 404", event.kind)
        assertEquals(404, event.statusCode)
        assertEquals("Not Found", event.detail)
        assertTrue(event.isError)
    }

    @Test
    fun buildFromNetworkRequest_redirectMetadata() {
        val model = NetworkRequestModel(
            requestId = "cdp-redir-1",
            stage = NetworkRequestStage.PENDING,
            sessionId = "session-1",
            url = "https://example.com/dest",
            method = "GET",
            redirectStatus = 302,
            redirectUrl = "https://example.com/src",
        )

        val event = DashboardEventClassifier.buildFromNetworkRequest(model)

        assertEquals(DashboardSeverity.WARNING, event.severity)
        assertEquals("HTTP 302", event.kind)
        assertEquals(302, event.statusCode)
        assertTrue(event.detail.orEmpty().contains("Redirected from https://example.com/src (302)"))
    }

    @Test
    fun buildFromNetworkRequest_failedAndCanceledRequests() {
        val failedModel = NetworkRequestModel(
            requestId = "cdp-fail-1",
            stage = NetworkRequestStage.FAILED,
            sessionId = "session-1",
            url = "https://example.com/broken",
            method = "GET",
            loadingError = "net::ERR_NAME_NOT_RESOLVED",
        )
        val failedEvent = DashboardEventClassifier.buildFromNetworkRequest(failedModel)
        assertEquals(DashboardSeverity.ERROR, failedEvent.severity)
        assertEquals("FAILED", failedEvent.kind)
        assertEquals("net::ERR_NAME_NOT_RESOLVED", failedEvent.detail)

        val canceledModel = NetworkRequestModel(
            requestId = "cdp-cancel-1",
            stage = NetworkRequestStage.FAILED,
            sessionId = "session-1",
            url = "https://example.com/aborted",
            method = "GET",
            canceled = true,
        )
        val canceledEvent = DashboardEventClassifier.buildFromNetworkRequest(canceledModel)
        assertEquals(DashboardSeverity.ERROR, canceledEvent.severity)
        assertEquals("CANCELED", canceledEvent.kind)
    }

    @Test
    fun buildFromNetworkRequest_phase19FaultInjectionBadges() {
        // Fault override with mock body
        val mockModel = NetworkRequestModel(
            requestId = "cdp-fault-1",
            stage = NetworkRequestStage.RESPONSE_RECEIVED,
            sessionId = "session-1",
            url = "https://example.com/api/test",
            method = "GET",
            responseStatus = 200,
            responseHeaders = mapOf("X-CuTools-Fault" to "injected", "Content-Type" to "application/json"),
        )
        val mockEvent = DashboardEventClassifier.buildFromNetworkRequest(mockModel)
        assertEquals(DashboardSeverity.SUCCESS, mockEvent.severity)
        assertEquals("FAULT MOCK", mockEvent.kind)
        assertEquals("mock body injected", mockEvent.detail)

        // Fault override with 500 error
        val errModel = NetworkRequestModel(
            requestId = "cdp-fault-2",
            stage = NetworkRequestStage.RESPONSE_RECEIVED,
            sessionId = "session-1",
            url = "https://example.com/api/fail",
            method = "GET",
            responseStatus = 500,
            responseHeaders = mapOf("X-CuTools-Fault" to "injected"),
        )
        val errEvent = DashboardEventClassifier.buildFromNetworkRequest(errModel)
        assertEquals(DashboardSeverity.ERROR, errEvent.severity)
        assertEquals("HTTP 500", errEvent.kind)
        assertEquals("fault injected", errEvent.detail)

        // Fault drop
        val dropModel = NetworkRequestModel(
            requestId = "cdp-fault-3",
            stage = NetworkRequestStage.FAILED,
            sessionId = "session-1",
            url = "https://example.com/api/drop",
            method = "GET",
            loadingError = "net::ERR_CONNECTION_RESET (CuTools simulated connection drop)",
        )
        val dropEvent = DashboardEventClassifier.buildFromNetworkRequest(dropModel)
        assertEquals(DashboardSeverity.ERROR, dropEvent.severity)
        assertEquals("CONNECTION DROP", dropEvent.kind)
    }

    @Test
    fun networkTypeFromCdp_mapsBlinkResourceTypes() {
        assertEquals(DashboardNetworkType.DOCUMENT, DashboardEventClassifier.networkTypeFromCdp("Document", null, null))
        assertEquals(DashboardNetworkType.STYLESHEET, DashboardEventClassifier.networkTypeFromCdp("Stylesheet", null, null))
        assertEquals(DashboardNetworkType.SCRIPT, DashboardEventClassifier.networkTypeFromCdp("Script", null, null))
        assertEquals(DashboardNetworkType.IMAGE, DashboardEventClassifier.networkTypeFromCdp("Image", null, null))
        assertEquals(DashboardNetworkType.FONT, DashboardEventClassifier.networkTypeFromCdp("Font", null, null))
        assertEquals(DashboardNetworkType.MEDIA, DashboardEventClassifier.networkTypeFromCdp("Media", null, null))
        assertEquals(DashboardNetworkType.XHR_FETCH, DashboardEventClassifier.networkTypeFromCdp("XHR", null, null))
        assertEquals(DashboardNetworkType.XHR_FETCH, DashboardEventClassifier.networkTypeFromCdp("Fetch", null, null))
        // Fallback to URL extension
        assertEquals(DashboardNetworkType.STYLESHEET, DashboardEventClassifier.networkTypeFromCdp(null, "https://example.com/style.css", "GET"))
    }
}