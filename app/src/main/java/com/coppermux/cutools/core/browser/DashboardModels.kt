package com.coppermux.cutools.core.browser

/**
 * Phase 18 — unified DevTools Dashboard event domain.
 *
 * These are the structured, UI-independent models produced by the embedded
 * WebView interceptor engine ([WebViewEngine]) and consumed by the
 * chronological dashboard feed. Every event originates from a genuine
 * WebView interception callback — nothing is fabricated.
 */
enum class DashboardEventCategory(val displayLabel: String) {
    /** A WebView network interception (document / asset / script / XHR / fetch). */
    NETWORK("network"),

    /** An in-page JavaScript console message or unhandled exception. */
    CONSOLE("console"),
}

/**
 * Display severity of a dashboard event, derived from the intercepted WebView
 * signal. Drives the Green / Yellow / Red card tinting:
 *  - [SUCCESS]: successful requests (HTTP 2xx) and standard console.log output.
 *  - [WARNING]: HTTP 3xx redirects, warning console levels, deprecations.
 *  - [ERROR]: network failures, HTTP 4xx/5xx, console.error, JS exceptions.
 */
enum class DashboardSeverity(val displayLabel: String) {
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error"),
}

/**
 * Kind of a WebView network interception, used for the type badge and for the
 * XHR/Fetch dashboard filter. [XHR_FETCH] represents asynchronous API calls
 * (XMLHttpRequest / Fetch API) which WebView exposes as a single interception
 * surface — the two are not distinguishable from a WebResourceRequest.
 */
enum class DashboardNetworkType(val displayLabel: String) {
    DOCUMENT("document"),
    STYLESHEET("stylesheet"),
    SCRIPT("script"),
    IMAGE("image"),
    FONT("font"),
    MEDIA("media"),
    XHR_FETCH("xhr/fetch"),
    REQUEST("request"),
}

/**
 * The dashboard filter bar chips. Filters are a pure intersection over the
 * underlying event stream (see [DashboardEventFilter]):
 *  - [ALL]: complete chronological feed.
 *  - [NETWORK]: all document / asset / script requests (excludes XHR/Fetch).
 *  - [XHR_FETCH]: asynchronous API calls only.
 *  - [CONSOLE]: in-page JS console messages.
 *  - [WARNINGS]: console warnings and deprecations (severity == WARNING).
 *  - [ERRORS]: console errors, unhandled exceptions, HTTP 4xx/5xx, failures.
 */
enum class DashboardFilter(val displayLabel: String) {
    ALL("All"),
    NETWORK("Network"),
    XHR_FETCH("XHR/Fetch"),
    CONSOLE("Console"),
    WARNINGS("Warnings"),
    ERRORS("Errors"),
}

/**
 * A single entry in the chronological DevTools feed.
 *
 * [timestamp] is the event reception time (elapsed realtime millis, monotonic)
 * at the interceptor, which also preserves arrival order alongside the
 * monotonically increasing [id]. [durationMs] is the measured response
 * duration when WebView exposes both the start and the completion of a
 * resource load; it is null when WebView does not report a completion.
 *
 * [kind] is the display badge (e.g. "DOCUMENT", "STYLESHEET", "SCRIPT",
 * "XHR/FETCH", "HTTP 404", "console.error", "JS exception"). For HTTP errors
 * [statusCode]/[statusText] carry the genuine error response.
 *
 * Phase 19 additions — expanded payload inspection. [requestHeaders] are the
 * genuine `WebResourceRequest` headers captured at interception time;
 * [responseHeaders]/[responseBody] are captured when the interceptor serves
 * (fault-injected) responses or receives HTTP-error responses. WebView does not
 * expose raw request POST payloads for pass-through loads, so [requestBody] is
 * only populated when a captured body exists; the expanded card renders
 * whatever genuine data is available and never fabricates payload content.
 */
data class DashboardEvent(
    val id: Long = 0L,
    val requestId: String? = null,
    val category: DashboardEventCategory,
    val severity: DashboardSeverity,
    val kind: String,
    val url: String? = null,
    val method: String? = null,
    val message: String? = null,
    val statusCode: Int? = null,
    val statusText: String? = null,
    val timestamp: Long = 0L,
    val durationMs: Long? = null,
    val detail: String? = null,
    val resourceType: DashboardNetworkType? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val responseBody: String? = null,
    val kernelEpoch: Long? = null,
    val sessionKey: String? = null,
) {
    val isNetwork: Boolean get() = category == DashboardEventCategory.NETWORK

    /** True when this event represents a response or drop that was actively fault-injected. */
    val isFaultApplied: Boolean
        get() = responseHeaders.any { (k, _) -> k.equals("X-CuTools-Fault", ignoreCase = true) } ||
            kind == "FAULT MOCK" ||
            kind == "CONNECTION DROP" ||
            detail?.contains("fault injected", ignoreCase = true) == true ||
            detail?.contains("simulated connection drop", ignoreCase = true) == true ||
            detail == "mock body injected"
    val isConsole: Boolean get() = category == DashboardEventCategory.CONSOLE

    /** True for async API calls (XHR / Fetch interceptions). */
    val isXhrFetch: Boolean get() = resourceType == DashboardNetworkType.XHR_FETCH

    val isHttpSuccess: Boolean get() = statusCode != null && statusCode in 200..299
    val isHttpRedirect: Boolean get() = statusCode != null && statusCode in 300..399

    /** True for HTTP 4xx/5xx responses (and failures with no HTTP status). */
    val isHttpError: Boolean get() = statusCode != null && statusCode >= 400

    /**
     * Whether the event is a console warning/deprecation — one half of the
     * [DashboardFilter.WARNINGS] chip (the other half being HTTP 3xx redirects).
     */
    val isWarning: Boolean get() = severity == DashboardSeverity.WARNING

    /** Whether the event is an error (console error / failure / 4xx-5xx). */
    val isError: Boolean get() = severity == DashboardSeverity.ERROR
}