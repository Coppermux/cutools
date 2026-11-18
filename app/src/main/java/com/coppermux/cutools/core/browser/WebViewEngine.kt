package com.coppermux.cutools.core.browser

import android.graphics.Bitmap
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer
import com.coppermux.cutools.core.kernel.DiagnosticKernel
import com.coppermux.cutools.core.kernel.KernelState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

private const val TAG = "CuTools/WebViewEngine"

private fun logTrace(tag: String, message: String) {
    try {
        android.util.Log.d(tag, message)
    } catch (_: Throwable) {
    }
    println("[$tag] $message")
}

/**
 * Phase 18/CDP-first — embedded WebView interceptor engine.
 *
 * This is the Android framework bridge for the browser viewport. It configures
 * a WebView for standalone browsing + DevTools debugging and handles platform
 * responsibilities:
 *  - [WebViewClient.shouldInterceptRequest]: Phase 19 live fault interception
 *    (CONTINUE / OVERRIDE / DROP). Standard DevTools network events are produced
 *    canonically by the CDP Network pipeline.
 *  - [WebViewClient.onPageFinished]: installs the unhandled JavaScript exception
 *    bridge ([installJavascriptErrorBridge]).
 *  - [WebChromeClient.onConsoleMessage]: routes page console log / info /
 *    warning / error events and JS exceptions into [DashboardEventStream].
 */
class WebViewEngine(
    private val eventStream: DashboardEventStream,
    private val faultInterceptorEngine: FaultInterceptorEngine = FaultInterceptorEngine(),
    @Volatile
    var kernel: DiagnosticKernel? = null,
) {

    /**
     * Listener for page load lifecycle completion events (finished, failed, or reset).
     */
    fun interface PageLifecycleListener {
        fun onPageLoadCompleted()
        fun onPageLoadStarted(url: String?) {}
    }

    private val pageLifecycleListeners = java.util.concurrent.CopyOnWriteArrayList<PageLifecycleListener>()

    fun addPageLifecycleListener(listener: PageLifecycleListener) {
        if (!pageLifecycleListeners.contains(listener)) {
            pageLifecycleListeners.add(listener)
        }
    }

    fun removePageLifecycleListener(listener: PageLifecycleListener) {
        pageLifecycleListeners.remove(listener)
    }

    internal fun notifyPageLoadCompleted() {
        for (listener in pageLifecycleListeners) {
            listener.onPageLoadCompleted()
        }
    }

    internal fun notifyPageLoadStarted(url: String?) {
        for (listener in pageLifecycleListeners) {
            listener.onPageLoadStarted(url)
        }
    }

    /**
     * Listener for committed document URL changes (after navigation or redirects).
     */
    fun interface DocumentUrlListener {
        fun onDocumentUrlCommitted(url: String)
    }

    private val documentUrlListeners = java.util.concurrent.CopyOnWriteArrayList<DocumentUrlListener>()

    fun addDocumentUrlListener(listener: DocumentUrlListener) {
        if (!documentUrlListeners.contains(listener)) {
            documentUrlListeners.add(listener)
        }
    }

    fun removeDocumentUrlListener(listener: DocumentUrlListener) {
        documentUrlListeners.remove(listener)
    }

    internal fun notifyDocumentUrlCommitted(url: String) {
        for (listener in documentUrlListeners) {
            listener.onDocumentUrlCommitted(url)
        }
    }

    /**
     * Listener for WebView backward / forward navigation history changes.
     */
    fun interface NavigationHistoryListener {
        fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean)
    }

    private val navigationHistoryListeners = java.util.concurrent.CopyOnWriteArrayList<NavigationHistoryListener>()

    fun addNavigationHistoryListener(listener: NavigationHistoryListener) {
        if (!navigationHistoryListeners.contains(listener)) {
            navigationHistoryListeners.add(listener)
        }
    }

    fun removeNavigationHistoryListener(listener: NavigationHistoryListener) {
        navigationHistoryListeners.remove(listener)
    }

    internal fun notifyHistoryChanged(view: WebView?) {
        val canGoBack = view?.canGoBack() == true
        val canGoForward = view?.canGoForward() == true
        for (listener in navigationHistoryListeners) {
            listener.onHistoryChanged(canGoBack, canGoForward)
        }
    }

    @Volatile
    internal var attachedWebView: java.lang.ref.WeakReference<WebView>? = null

    /**
     * Attaches or tracks [webView] as the actively configured WebView instance.
     */
    fun attachWebView(webView: WebView) {
        attachedWebView = java.lang.ref.WeakReference(webView)
        notifyHistoryChanged(webView)
    }

    /**
     * Returns whether the actively attached WebView can navigate back in its history.
     */
    fun canGoBack(): Boolean = attachedWebView?.get()?.canGoBack() == true

    /**
     * Navigates the actively attached WebView backward in history if possible.
     * Returns true if back navigation was dispatched, false otherwise.
     */
    fun goBack(): Boolean {
        val wv = attachedWebView?.get() ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            notifyHistoryChanged(wv)
            true
        } else {
            false
        }
    }

    /**
     * Returns whether the actively attached WebView can navigate forward in its history.
     */
    fun canGoForward(): Boolean = attachedWebView?.get()?.canGoForward() == true

    /**
     * Navigates the actively attached WebView forward in history if possible.
     * Returns true if forward navigation was dispatched, false otherwise.
     */
    fun goForward(): Boolean {
        val wv = attachedWebView?.get() ?: return false
        return if (wv.canGoForward()) {
            wv.goForward()
            notifyHistoryChanged(wv)
            true
        } else {
            false
        }
    }

    private val resetGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var pageGeneration = 0
    @Volatile
    private var activeDocumentUrl: String? = null
    @Volatile
    var lastCommittedUrl: String? = null
        internal set
    val currentDocumentUrl: String? get() = lastCommittedUrl ?: activeDocumentUrl
    @Volatile
    private var preResetHost: String? = null
    @Volatile
    private var activeDocumentHost: String? = null

    @Volatile
    var isNetworkDropped: Boolean = false
        private set

    /**
     * Authoritative control over WebView network drop state.
     * In CDP-only network drop, native WebSettings.blockNetworkLoads is NEVER enabled,
     * ensuring Chromium's network service and CDP Network.emulateNetworkConditions
     * manage genuine network disconnection semantics (net::ERR_INTERNET_DISCONNECTED).
     *
     * When dropped is false:
     * - Recovers the preserved committed URL or reloads.
     */
    fun setNetworkDrop(dropped: Boolean) {
        isNetworkDropped = dropped
        val wv = attachedWebView?.get()
        val wvId = wv?.let { System.identityHashCode(it) }
        DiagnosticTracer.trace(
            category = "CDP_NETWORK",
            event = "WEBVIEW_ENGINE_SET_NETWORK_DROP_INVOKED",
            "dropped" to dropped,
            "webViewAttached" to (wv != null),
            "webViewIdentity" to wvId,
            "thread" to Thread.currentThread().name,
        )
        if (wv == null) {
            DiagnosticTracer.trace(
                category = "CDP_NETWORK",
                event = "WEBVIEW_ENGINE_SET_NETWORK_DROP_NO_ATTACHED_WEBVIEW",
                "dropped" to dropped,
            )
            return
        }
        val runnable = Runnable {
            try {
                // Ensure native blockNetworkLoads is never enabled in CDP-only drop
                wv.settings.blockNetworkLoads = false
                DiagnosticTracer.trace(
                    category = "CDP_NETWORK",
                    event = "WEBVIEW_SETTINGS_APPLIED",
                    "webViewIdentity" to System.identityHashCode(wv),
                    "dropped" to dropped,
                    "blockNetworkLoads" to false,
                    "cacheMode" to wv.settings.cacheMode,
                    "currentUrl" to wv.url,
                    "lastCommittedUrl" to lastCommittedUrl,
                )
                if (!dropped) {
                    restorePreservedUrlOrReload(wv)
                }
            } catch (t: Throwable) {
                DiagnosticTracer.trace(
                    category = "CDP_NETWORK",
                    event = "WEBVIEW_SET_NETWORK_DROP_ERROR",
                    "webViewIdentity" to System.identityHashCode(wv),
                    "error" to (t.message ?: t.toString()),
                )
                logTrace(TAG, "setNetworkDrop error: ${t.message}")
            }
        }
        runOnMainThreadOrImmediate(wv, runnable)
    }

    /**
     * Restores the last successfully committed document URL upon reconnecting,
     * or reloads the active URL, preserving navigation history without WebView recreation.
     */
    fun restorePreservedUrlOrReload(wv: WebView? = attachedWebView?.get()) {
        val targetWv = wv ?: attachedWebView?.get() ?: return
        val runnable = Runnable {
            try {
                val current = targetWv.url
                val preserved = lastCommittedUrl
                if (preserved != null && (current == null || current.startsWith("chrome-error://") || current != preserved)) {
                    logTrace(TAG, "Restoring preserved committed URL: $preserved (current was: $current)")
                    targetWv.loadUrl(preserved)
                } else {
                    logTrace(TAG, "Reloading current URL: $current")
                    targetWv.reload()
                }
            } catch (t: Throwable) {
                logTrace(TAG, "restorePreservedUrlOrReload error: ${t.message}")
            }
        }
        runOnMainThreadOrImmediate(targetWv, runnable)
    }

    private fun runOnMainThreadOrImmediate(wv: WebView, runnable: Runnable) {
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runnable.run()
            } else {
                wv.post(runnable)
            }
        } catch (_: Throwable) {
            // JVM unit test fallback where android.os.Looper is not mocked
            runnable.run()
        }
    }

    /**
     * Resets the interceptor engine generation, recording pre-reset document host
     * to invalidate late console messages from the pre-reset page lifecycle.
     */
    fun reset() {
        val preUrl = activeDocumentUrl
        preResetHost = extractHost(preUrl)
        activeDocumentUrl = null
        lastCommittedUrl = null
        activeDocumentHost = null
        resetGeneration.incrementAndGet()
        notifyPageLoadCompleted()
        attachedWebView?.get()?.let { wv ->
            runCatching { wv.stopLoading() }
            runCatching { wv.clearHistory() }
        }
        notifyHistoryChanged(null)
    }

    /**
     * Configures [webView] with the interceptor clients and the settings the
     * standalone browser needs: WebContents debugging, JavaScript, DOM
     * storage, multi-window support, and an optional custom User-Agent
     * ([userAgentOverride]).
     */
    fun configure(webView: WebView, userAgentOverride: String? = null) {
        val prevWv = attachedWebView?.get()
        val prevId = prevWv?.let { System.identityHashCode(it) }
        val newId = System.identityHashCode(webView)
        DiagnosticTracer.trace(
            category = "WEBVIEW_LIFECYCLE",
            event = "WEBVIEW_ENGINE_CONFIGURE",
            "newWebViewIdentity" to newId,
            "previousWebViewIdentity" to prevId,
            "isReplacement" to (prevWv != null && prevWv !== webView),
            "isNetworkDropped" to isNetworkDropped,
        )
        attachedWebView = java.lang.ref.WeakReference(webView)
        WebView.setWebContentsDebuggingEnabled(true)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.blockNetworkLoads = false
        if (!userAgentOverride.isNullOrBlank()) {
            settings.userAgentString = userAgentOverride
        }
        webView.webViewClient = interceptorClient
        webView.webChromeClient = interceptorChromeClient

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return interceptorClient.shouldInterceptRequest(null, request)
                    }
                })
            } catch (t: Throwable) {
                logTrace(TAG, "ServiceWorkerController setup: ${t.message}")
            }
        }
        notifyHistoryChanged(webView)
    }

    /**
     * Injects the unhandled-exception bridge into the current page. Defines a
     * `window.onerror` wrapper that forwards uncaught exceptions to
     * `console.error`, so [WebChromeClient.onConsoleMessage] reports them as
     * red "JS exception" events. Safe to call after every page load; the
     * bridge self-guards against double installation.
     */
    fun installJavascriptErrorBridge(webView: WebView) {
        webView.evaluateJavascript(JAVASCRIPT_ERROR_BRIDGE, null)
    }

    // ================================================================
    // Network interceptor
    // ================================================================

    internal val interceptorClient: WebViewClient = object : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            if (view == null || request == null) return null
            val url = request.url?.toString() ?: return null
            val method = request.method ?: "GET"

            return try {
                // Phase 19 fault interception: evaluate active rules before the
                // request is let through to the network.
                val decision = faultInterceptorEngine.decisionFor(url, method)
                if (decision.shouldDelay) {
                    try {
                        Thread.sleep(decision.delayMs.toLong())
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }

                // In the CDP-first architecture, standard network events are produced
                // solely by CDP Network domain events. WebView remains responsible for
                // live fault injection (CONTINUE / OVERRIDE) while DROP is handled natively
                // by CDP Fetch.failRequest(ConnectionReset).
                when (decision.action) {
                    FaultAction.CONTINUE -> null
                    FaultAction.OVERRIDE -> {
                        DiagnosticTracer.trace(
                            category = "FAULT_INJECTION",
                            event = "SERVE_OVERRIDE",
                            "url" to url,
                            "method" to method,
                            "statusCode" to decision.statusCode,
                        )
                        serveOverride(request, decision)
                    }
                    FaultAction.DROP -> {
                        DiagnosticTracer.trace(
                            category = "FAULT_INJECTION",
                            event = "FAULT_ACTION_DROP_PASS_THROUGH",
                            "url" to url,
                            "method" to method,
                        )
                        null
                    }
                }
            } catch (e: Exception) {
                logTrace(TAG, "Fault interception error for $method $url: ${e.message}")
                null
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val wvId = view?.let { System.identityHashCode(it) }
            val blockLoads = view?.settings?.blockNetworkLoads
            DiagnosticTracer.trace(
                category = "WEBVIEW_CLIENT",
                event = "ON_PAGE_STARTED",
                "webViewIdentity" to wvId,
                "url" to url,
                "blockNetworkLoads" to blockLoads,
                "isNetworkDropped" to isNetworkDropped,
            )
            if (view != null) {
                attachedWebView = java.lang.ref.WeakReference(view)
            }
            // Document lifecycle is captured canonically by CDP Network domain.
            if (!url.isNullOrBlank() && url != "about:blank" && !url.startsWith("chrome-error://")) {
                pageGeneration = resetGeneration.get()
                activeDocumentUrl = url
                activeDocumentHost = extractHost(url)
                if (!isNetworkDropped && lastCommittedUrl == null) {
                    lastCommittedUrl = url
                }
                notifyDocumentUrlCommitted(url)
            } else if (url == "about:blank") {
                activeDocumentUrl = null
                activeDocumentHost = null
            }
            notifyPageLoadStarted(url)
            notifyHistoryChanged(view)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val wvId = view?.let { System.identityHashCode(it) }
            val blockLoads = view?.settings?.blockNetworkLoads
            DiagnosticTracer.trace(
                category = "WEBVIEW_CLIENT",
                event = "ON_PAGE_FINISHED",
                "webViewIdentity" to wvId,
                "url" to url,
                "blockNetworkLoads" to blockLoads,
                "isNetworkDropped" to isNetworkDropped,
            )
            if (view != null) {
                attachedWebView = java.lang.ref.WeakReference(view)
            }
            val committed = url ?: view?.url
            if (!committed.isNullOrBlank() && committed != "about:blank" && !committed.startsWith("chrome-error://")) {
                preResetHost = null
                activeDocumentUrl = committed
                activeDocumentHost = extractHost(committed)
                if (!isNetworkDropped) {
                    lastCommittedUrl = committed
                }
                notifyDocumentUrlCommitted(committed)
            }
            view?.let { installJavascriptErrorBridge(it) }
            notifyPageLoadCompleted()
            notifyHistoryChanged(view)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (view != null) {
                attachedWebView = java.lang.ref.WeakReference(view)
            }
            val committed = url ?: view?.url
            if (!committed.isNullOrBlank() && committed != "about:blank" && !committed.startsWith("chrome-error://")) {
                activeDocumentUrl = committed
                activeDocumentHost = extractHost(committed)
                if (!isNetworkDropped) {
                    lastCommittedUrl = committed
                }
                notifyDocumentUrlCommitted(committed)
            }
            notifyHistoryChanged(view)
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            val wvId = view?.let { System.identityHashCode(it) }
            DiagnosticTracer.trace(
                category = "WEBVIEW_CLIENT",
                event = "ON_RECEIVED_HTTP_ERROR",
                "webViewIdentity" to wvId,
                "url" to request?.url?.toString(),
                "isForMainFrame" to request?.isForMainFrame,
                "statusCode" to errorResponse?.statusCode,
                "reasonPhrase" to errorResponse?.reasonPhrase,
            )
            // HTTP error metadata and response headers are captured canonically by CDP.
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            val wvId = view?.let { System.identityHashCode(it) }
            val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode else null
            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() else null
            DiagnosticTracer.trace(
                category = "WEBVIEW_CLIENT",
                event = "ON_RECEIVED_ERROR",
                "webViewIdentity" to wvId,
                "url" to request?.url?.toString(),
                "isForMainFrame" to request?.isForMainFrame,
                "method" to request?.method,
                "errorCode" to errorCode,
                "description" to description,
                "blockNetworkLoads" to view?.settings?.blockNetworkLoads,
            )
            // Network failure errors are captured canonically by CDP Network.loadingFailed.
            notifyPageLoadCompleted()
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
            val wvId = view?.let { System.identityHashCode(it) }
            DiagnosticTracer.trace(
                category = "WEBVIEW_CLIENT",
                event = "ON_RECEIVED_ERROR_LEGACY",
                "webViewIdentity" to wvId,
                "errorCode" to errorCode,
                "description" to description,
                "failingUrl" to failingUrl,
                "blockNetworkLoads" to view?.settings?.blockNetworkLoads,
            )
            notifyPageLoadCompleted()
        }
    }

    // ================================================================
    // Phase 19 — fault response fabrication
    // ================================================================

    /**
     * Serves a fabricated `WebResourceResponse` for an OVERRIDE decision: the
     * forced HTTP status code, optional mock (JSON) body, and a marker header.
     * CDP Network domain captures the resulting response headers and status,
     * routing the fault-injected event canonically into DevTools Dashboard.
     */
    private fun serveOverride(
        request: WebResourceRequest,
        decision: FaultDecision,
    ): WebResourceResponse {
        val mime = mimeTypeFor(decision.body, request.url.toString())
        val statusCode = decision.statusCode ?: 200
        val statusText = statusTextFor(statusCode)
        val headers = mapOf(
            "Content-Type" to "$mime; charset=UTF-8",
            "X-CuTools-Fault" to "injected",
        )
        val body = decision.body ?: if (mime == "text/html") {
            "<!DOCTYPE html><html><head><title>$statusCode $statusText</title></head><body><h1>$statusCode $statusText</h1><p>CuTools Fault Injected</p></body></html>"
        } else {
            ""
        }
        return WebResourceResponse(
            mime,
            "UTF-8",
            statusCode,
            statusText,
            headers,
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )
    }


    /** Content-type for a mock body, inferred from content shape then URL. */
    private fun mimeTypeFor(body: String?, url: String): String {
        val trimmed = body?.trim()
        if (trimmed != null) {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "application/json"
            if (trimmed.startsWith("<")) return "text/html"
        }
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".json") -> "application/json"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            else -> "text/plain"
        }
    }

    /** Standard reason phrase for a forced status, honest for the supported set. */
    private fun statusTextFor(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Injected"
    }

    // ================================================================
    // Console interceptor
    // ================================================================

    internal val interceptorChromeClient: WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress >= 100) {
                notifyPageLoadCompleted()
                notifyHistoryChanged(view)
            }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // Ingress barrier (Phase 2): drop all console messages while kernel is in RESETTING state
            val k = kernel
            if (k != null && k.snapshot.state == KernelState.RESETTING) {
                return false
            }

            // 1. Generation check: drop late console messages originating from pre-reset page lifecycle
            if (pageGeneration < resetGeneration.get()) {
                return false
            }

            val sourceId = consoleMessage.sourceId()
            val preHost = preResetHost

            // 2. Pre-reset document boundary check:
            // Prevent late messages from the pre-reset document from leaking into the post-reset feed,
            // even after the next page's onPageStarted has advanced pageGeneration.
            if (!preHost.isNullOrBlank() && matchesHost(sourceId, preHost)) {
                return false
            }

            // 3. Conservative active document attribution:
            // If preResetHost is active during navigation transit, suppress ambiguous cross-document logs
            val activeHost = activeDocumentHost
            if (!activeHost.isNullOrBlank() && !preHost.isNullOrBlank()) {
                val sourceHost = extractHost(sourceId)
                if (sourceHost != null && !matchesHost(sourceId, activeHost)) {
                    return false
                }
            }

            val level = levelName(consoleMessage.messageLevel())
            val line = consoleMessage.lineNumber()
            val event = DashboardEventClassifier.buildConsoleEvent(
                level = level,
                message = consoleMessage.message(),
                timestamp = System.currentTimeMillis(),
                sourceUrl = consoleMessage.sourceId(),
                lineNumber = if (line > 0) line else null,
            )
            val enriched = if (k != null && k.hasActiveSession) {
                event.copy(
                    kernelEpoch = k.currentEpoch,
                    sessionKey = k.currentSession?.sessionKey,
                )
            } else {
                event
            }
            eventStream.append(enriched)
            return true
        }
    }

    private fun levelName(level: ConsoleMessage.MessageLevel): String = when (level) {
        ConsoleMessage.MessageLevel.LOG -> "log"
        ConsoleMessage.MessageLevel.WARNING -> "warning"
        ConsoleMessage.MessageLevel.ERROR -> "error"
        ConsoleMessage.MessageLevel.DEBUG -> "debug"
        ConsoleMessage.MessageLevel.TIP -> "info"
        else -> "log"
    }

    companion object {

        fun extractHost(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                val uri = java.net.URI(url)
                val host = uri.host
                if (!host.isNullOrBlank()) {
                    host.lowercase().removePrefix("www.")
                } else null
            } catch (_: Exception) {
                val withoutProto = url.substringAfter("://").substringBefore("/")
                val host = withoutProto.substringBefore(":")
                if (host.isNotBlank()) host.lowercase().removePrefix("www.") else null
            }
        }

        fun matchesHost(sourceUrl: String?, targetHost: String): Boolean {
            if (sourceUrl.isNullOrBlank() || targetHost.isBlank()) return false
            val sourceHost = extractHost(sourceUrl) ?: return sourceUrl.contains(targetHost, ignoreCase = true)
            return sourceHost == targetHost || sourceHost.endsWith(".$targetHost") || targetHost.endsWith(".$sourceHost")
        }

        /**
         * Injected once per page load. Honest bridge: uncaught exceptions are
         * forwarded to the existing onerror (when one exists) and also surfaced
         * to WebView's console channel as errors so the dashboard can render a
         * red "JS exception" card.
         */
        private const val JAVASCRIPT_ERROR_BRIDGE: String = """
            (function () {
                if (window.__cutoolsErrorBridgeInstalled__) { return; }
                window.__cutoolsErrorBridgeInstalled__ = true;
                var prev = window.onerror;
                window.onerror = function (message, source, line, column, errorObject) {
                    try {
                        console.error((errorObject && errorObject.stack) ?
                            'Uncaught ' + errorObject.stack :
                            'Uncaught ' + message + (source ? ' (at ' + source + ':' + line + ':' + column + ')' : ''));
                    } catch (e) { /* never interrupt the page error flow */ }
                    if (typeof prev === 'function') {
                        return prev(message, source, line, column, errorObject);
                    }
                    return false;
                };
            })();
        """
    }
}