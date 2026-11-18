package com.coppermux.cutools.core.browser

import com.coppermux.cutools.core.network.NetworkRequestModel

/**
 * Phase 18 — pure classification of intercepted WebView signals into
 * [DashboardEvent] models.
 *
 * This object carries no Android framework dependency: it maps primitives
 * (URLs, HTTP methods/status, console level text, timestamps) to dashboard
 * events so the interceptor stream parsing is fully unit-testable on the JVM
 * while [WebViewEngine] stays a thin framework bridge. The classifier never
 * fabricates data — it only derives severity and display badges from the
 * signals it is given.
 */
object DashboardEventClassifier {

    /** HTTP methods WebView reports that conventionally denote async API calls. */
    private val ASYNC_API_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

    // ================================================================
    // Severity derivation
    // ================================================================

    /**
     * Severity for an HTTP response status:
     * Green for 2xx (and informational 1xx), yellow for 3xx redirects, red for
     * 4xx/5xx. Absent status is treated as neutral success (request sent).
     */
    fun severityForHttpStatus(status: Int?): DashboardSeverity = when (status) {
        null -> DashboardSeverity.SUCCESS
        in 200..299, in 100..199 -> DashboardSeverity.SUCCESS
        in 300..399 -> DashboardSeverity.WARNING
        else -> DashboardSeverity.ERROR
    }

    /**
     * Severity for a console level string ("log", "info", "debug", "warning",
     * "error", "tip"). Standard console.log output is neutral; warnings are
     * yellow; errors are red.
     */
    fun severityForConsoleLevel(level: String?): DashboardSeverity = when (level) {
        "error" -> DashboardSeverity.ERROR
        "warning" -> DashboardSeverity.WARNING
        else -> DashboardSeverity.SUCCESS
    }

    // ================================================================
    // Network type inference
    // ================================================================

    /**
     * Infers the [DashboardNetworkType] of a WebView resource interception.
     * A main-frame request is a document; async-api methods (POST/PUT/PATCH/
     * DELETE) classify as XHR/Fetch; otherwise the URL path tail infers
     * stylesheet / script / image / font and everything else is a plain
     * request. WebView does not expose a CDP-style resource type, so this is
     * an honest best-effort projection, never a fabrication.
     */
    fun networkType(url: String?, method: String?, isForMainFrame: Boolean): DashboardNetworkType = when {
        isForMainFrame -> DashboardNetworkType.DOCUMENT
        !method.isNullOrBlank() && method.trim().uppercase() in ASYNC_API_METHODS -> DashboardNetworkType.XHR_FETCH
        else -> kindForUrl(url)
    }

    private fun kindForUrl(url: String?): DashboardNetworkType {
        val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return DashboardNetworkType.REQUEST
        return when {
            path.endsWith(".css") -> DashboardNetworkType.STYLESHEET
            path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs") -> DashboardNetworkType.SCRIPT
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".bmp") || path.endsWith(".avif") -> DashboardNetworkType.IMAGE
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") || path.endsWith(".eot") -> DashboardNetworkType.FONT
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".ogg") ||
                path.endsWith(".mp3") || path.endsWith(".wav") -> DashboardNetworkType.MEDIA
            else -> DashboardNetworkType.REQUEST
        }
    }

    private fun kindBadge(type: DashboardNetworkType): String = type.displayLabel.uppercase()

    // ================================================================
    // Event builders
    // ================================================================

    /**
     * Builds a network "request sent" event for a WebView interception.
     * The request has not received a response yet, so severity is neutral.
     * [requestHeaders] are the genuine `WebResourceRequest` headers captured
     * at interception time (used by the Phase 19 expanded card + cURL export).
     */
    fun buildNetworkRequestEvent(
        url: String,
        method: String,
        isForMainFrame: Boolean,
        timestamp: Long,
        isRedirect: Boolean = false,
        detail: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
    ): DashboardEvent {
        val resourceType = networkType(url, method, isForMainFrame)
        return DashboardEvent(
            category = DashboardEventCategory.NETWORK,
            severity = if (isRedirect) DashboardSeverity.WARNING else DashboardSeverity.SUCCESS,
            kind = kindBadge(resourceType),
            url = url,
            method = method,
            message = url,
            statusCode = if (isRedirect) 301 else null,
            timestamp = timestamp,
            detail = detail,
            resourceType = resourceType,
            requestHeaders = requestHeaders,
        )
    }

    /**
     * Builds a terminal network event for a document load that finished
     * [durationMs] after [WebViewEngine.onPageStarted]. A completed page load
     * is always neutral/success tint.
     */
    fun buildDocumentFinishedEvent(
        url: String?,
        timestamp: Long,
        durationMs: Long? = null,
    ): DashboardEvent = DashboardEvent(
        category = DashboardEventCategory.NETWORK,
        severity = DashboardSeverity.SUCCESS,
        kind = DashboardNetworkType.DOCUMENT.displayLabel.uppercase(),
        url = url,
        method = "GET",
        message = url,
        statusCode = 200,
        timestamp = timestamp,
        durationMs = durationMs,
        resourceType = DashboardNetworkType.DOCUMENT,
    )

    /**
     * Builds an HTTP error event (4xx/5xx) from `WebViewClient.onReceivedHttpError`.
     * Severity is red regardless of the request type. [responseHeaders] are the
     * genuine `WebResourceResponse` headers of the error response.
     */
    fun buildHttpErrorEvent(
        url: String?,
        method: String?,
        statusCode: Int,
        statusText: String?,
        timestamp: Long,
        isForMainFrame: Boolean = false,
        responseHeaders: Map<String, String> = emptyMap(),
    ): DashboardEvent {
        val resourceType = if (isForMainFrame) DashboardNetworkType.DOCUMENT else networkType(url, method, false)
        return DashboardEvent(
            category = DashboardEventCategory.NETWORK,
            severity = DashboardSeverity.ERROR,
            kind = "HTTP $statusCode",
            url = url,
            method = method,
            message = url,
            statusCode = statusCode,
            statusText = statusText,
            timestamp = timestamp,
            detail = statusText,
            resourceType = resourceType,
            responseHeaders = responseHeaders,
        )
    }

    /**
     * Builds a network failure event (e.g. `net::ERR_NAME_NOT_RESOLVED` from
     * `WebViewClient.onReceivedError`). Severity is always red.
     */
    fun buildNetworkFailureEvent(
        url: String?,
        timestamp: Long,
        detail: String? = null,
    ): DashboardEvent = DashboardEvent(
        category = DashboardEventCategory.NETWORK,
        severity = DashboardSeverity.ERROR,
        kind = "NETWORK ERROR",
        url = url,
        method = null,
        message = url,
        timestamp = timestamp,
        detail = detail,
        resourceType = DashboardNetworkType.REQUEST,
    )

    /**
     * Builds a terminal event for a fault-injected response served by the
     * Phase 19 [FaultInterceptorEngine] ([WebViewEngine.shouldInterceptRequest]
     * OVERRIDE branch). Forced 4xx/5xx codes badge as the HTTP error with red
     * severity; mock-body responses (200) badge as "FAULT MOCK" with success
     * severity. The genuine served headers + body are carried for the expanded
     * card payload inspection.
     */
    fun buildFaultResponseEvent(
        url: String?,
        method: String?,
        statusCode: Int,
        statusText: String?,
        timestamp: Long,
        isForMainFrame: Boolean = false,
        responseHeaders: Map<String, String> = emptyMap(),
        responseBody: String? = null,
    ): DashboardEvent {
        val isError = statusCode >= 400
        val resourceType = if (isForMainFrame) DashboardNetworkType.DOCUMENT else networkType(url, method, false)
        return DashboardEvent(
            category = DashboardEventCategory.NETWORK,
            severity = if (isError) DashboardSeverity.ERROR else DashboardSeverity.SUCCESS,
            kind = if (isError) "HTTP $statusCode" else "FAULT MOCK",
            url = url,
            method = method,
            message = url,
            statusCode = statusCode,
            statusText = statusText,
            timestamp = timestamp,
            detail = if (isError) "fault injected" else "mock body injected",
            resourceType = resourceType,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
        )
    }

    /**
     * Builds a connection-drop failure event for the Phase 19 [FaultInterceptorEngine]
     * DROP branch — the WebView stream throws while the page reads it, so the
     * simulated socket failure surfaces as a red network error event.
     */
    fun buildFaultDropEvent(
        url: String?,
        method: String?,
        timestamp: Long,
    ): DashboardEvent = DashboardEvent(
        category = DashboardEventCategory.NETWORK,
        severity = DashboardSeverity.ERROR,
        kind = "CONNECTION DROP",
        url = url,
        method = method,
        message = url,
        timestamp = timestamp,
        detail = "net::ERR_CONNECTION_RESET (simulated drop)",
        resourceType = DashboardNetworkType.REQUEST,
    )

    /**
     * Builds a console event from an in-page JavaScript console message.
     * [level] is the WebChromeClient message level name ("log", "info",
     * "debug", "warning", "error", "tip"). [isRuntimeException] flags events
     * raised through the injected JS unhandled-exception bridge, which are
     * surfaced as console.error and badge as "JS exception".
     */
    fun buildConsoleEvent(
        level: String?,
        message: String?,
        timestamp: Long,
        sourceUrl: String? = null,
        lineNumber: Int? = null,
        isRuntimeException: Boolean = false,
    ): DashboardEvent = DashboardEvent(
        category = DashboardEventCategory.CONSOLE,
        severity = severityForConsoleLevel(level),
        kind = if (isRuntimeException) "JS exception" else "console.$level",
        url = sourceUrl,
        method = null,
        message = message,
        timestamp = timestamp,
        detail = if (lineNumber != null) "line $lineNumber" else null,
        resourceType = null,
    )

    /**
     * Builds a console event for an unhandled JS exception captured by the
     * injected `window.onerror` bridge. Always red.
     */
    fun buildRuntimeExceptionEvent(
        message: String?,
        timestamp: Long,
        sourceUrl: String? = null,
        lineNumber: Int? = null,
    ): DashboardEvent = buildConsoleEvent(
        level = "error",
        message = message,
        timestamp = timestamp,
        sourceUrl = sourceUrl,
        lineNumber = lineNumber,
        isRuntimeException = true,
    )

    /**
     * Builds a canonical [DashboardEvent] from genuine Chromium CDP [NetworkRequestModel] data.
     * Preserves native [NetworkRequestModel.requestId], genuine headers, status code,
     * timing duration, and Blink-reported resource types.
     */
    fun buildFromNetworkRequest(model: NetworkRequestModel): DashboardEvent {
        val resourceType = networkTypeFromCdp(model.resourceType, model.url, model.method)
        val isError = model.isFailed || (model.responseStatus != null && model.responseStatus >= 400)
        val isWarning = (model.redirectStatus != null && model.responseStatus == null) ||
            (model.responseStatus != null && model.responseStatus in 300..399)
        val severity = when {
            isError -> DashboardSeverity.ERROR
            isWarning -> DashboardSeverity.WARNING
            else -> DashboardSeverity.SUCCESS
        }

        val isFaultInjected = model.responseHeaders.any { (k, _) -> k.equals("X-CuTools-Fault", ignoreCase = true) }
        val isConnectionDrop = model.isFailed && model.loadingError?.contains("ERR_CONNECTION_RESET") == true

        val kind = when {
            isConnectionDrop -> "CONNECTION DROP"
            isFaultInjected && model.responseStatus != null && model.responseStatus >= 400 -> "HTTP ${model.responseStatus}"
            isFaultInjected && !model.isFailed -> "FAULT MOCK"
            model.isFailed -> when {
                model.canceled -> "CANCELED"
                model.loadingError?.contains("ERR_CONNECTION_RESET") == true -> "CONNECTION DROP"
                else -> "FAILED"
            }
            isFaultInjected -> "FAULT MOCK"
            model.responseStatus != null && model.responseStatus >= 400 -> "HTTP ${model.responseStatus}"
            model.redirectStatus != null && model.responseStatus == null -> "HTTP ${model.redirectStatus}"
            else -> kindBadge(resourceType)
        }

        val detail = when {
            isConnectionDrop -> model.loadingError ?: "simulated connection drop"
            isFaultInjected && model.responseStatus != null && model.responseStatus >= 400 -> "fault injected"
            isFaultInjected -> "mock body injected"
            model.loadingError != null -> model.loadingError
            model.redirectUrl != null -> "Redirected from ${model.redirectUrl}" + (model.redirectStatus?.let { " ($it)" } ?: "")
            model.responseStatusText != null -> model.responseStatusText
            else -> null
        }

        val durationMs = model.timing?.responseEnd?.takeIf { it > 0 }?.toLong()
            ?: model.timing?.receiveHeadersEnd?.takeIf { it > 0 }?.toLong()
        val timestamp = if (model.timestamp != null && model.timestamp > 0) {
            (model.timestamp * 1000).toLong()
        } else {
            System.currentTimeMillis()
        }

        return DashboardEvent(
            requestId = model.requestId,
            category = DashboardEventCategory.NETWORK,
            severity = severity,
            kind = kind,
            url = model.url,
            method = model.method,
            message = model.url,
            statusCode = model.responseStatus ?: model.redirectStatus,
            statusText = model.responseStatusText,
            timestamp = timestamp,
            durationMs = durationMs,
            detail = detail,
            resourceType = resourceType,
            requestHeaders = model.requestHeaders,
            responseHeaders = model.responseHeaders,
            requestBody = model.postData,
            responseBody = null,
        )
    }

    /**
     * Maps Chromium CDP's `type` parameter (e.g. "Document", "Stylesheet", "XHR", "Fetch")
     * to the internal [DashboardNetworkType]. Falls back to URL extension inference when unspecified.
     */
    fun networkTypeFromCdp(cdpType: String?, url: String?, method: String?): DashboardNetworkType {
        if (!cdpType.isNullOrBlank()) {
            when (cdpType.trim().lowercase()) {
                "document" -> return DashboardNetworkType.DOCUMENT
                "stylesheet" -> return DashboardNetworkType.STYLESHEET
                "script" -> return DashboardNetworkType.SCRIPT
                "image" -> return DashboardNetworkType.IMAGE
                "font" -> return DashboardNetworkType.FONT
                "media" -> return DashboardNetworkType.MEDIA
                "xhr", "fetch" -> return DashboardNetworkType.XHR_FETCH
            }
        }
        return networkType(url, method, false)
    }
}