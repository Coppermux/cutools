package com.coppermux.cutools.ui.browser

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer

/**
 * Encapsulates navigation request state for [BrowserView], preventing duplicate
 * [WebView.loadUrl] invocations across recompositions when requested URLs remain identical.
 */
class BrowserNavigationState(
    var lastRequestedUrl: String? = null,
    var lastNavigationId: Long? = null,
) {
    /**
     * Determines whether [targetUrl] should be dispatched to [WebView.loadUrl].
     * Returns true if a genuine navigation transition is needed or if [navigationId]
     * signals an explicit retry/reset; false if the URL has already been dispatched
     * during the current navigation cycle.
     */
    fun shouldLoad(
        targetUrl: String,
        currentWebViewUrl: String?,
        navigationId: Long? = null,
    ): Boolean {
        if (targetUrl.isNotBlank()) {
            val isExplicitRetry = navigationId != null && navigationId != lastNavigationId
            val isAlreadyLoaded = currentWebViewUrl != null && (currentWebViewUrl == targetUrl || currentWebViewUrl.trimEnd('/') == targetUrl.trimEnd('/'))
            val isUrlChanged = lastRequestedUrl != targetUrl
            if (isExplicitRetry || (isUrlChanged && !isAlreadyLoaded)) {
                lastRequestedUrl = targetUrl
                lastNavigationId = navigationId
                return true
            }
            lastRequestedUrl = targetUrl
            if (navigationId != null) {
                lastNavigationId = navigationId
            }
            return false
        } else {
            // Target URL is blank (e.g. Reset App or backToWorkspace)
            val isExplicitReset = navigationId != null && navigationId != lastNavigationId
            lastNavigationId = navigationId
            val wasOnNonBlank = currentWebViewUrl != null && currentWebViewUrl != "about:blank"
            val needsLoadBlank = (lastRequestedUrl != "about:blank" && wasOnNonBlank) || (isExplicitReset && wasOnNonBlank)
            lastRequestedUrl = "about:blank"
            return needsLoadBlank
        }
    }

    /**
     * Explicitly invalidates navigation state, allowing retrying the same URL
     * even if [navigationId] has not changed.
     */
    fun invalidate() {
        lastRequestedUrl = null
        lastNavigationId = null
    }

    /**
     * Explicitly resets navigation state to about:blank.
     */
    fun reset() {
        lastRequestedUrl = "about:blank"
        lastNavigationId = null
    }
}

/**
 * A [SwipeRefreshLayout] subclass that refuses touch interception whenever multiple
 * pointers are active on screen (e.g. pinch-to-zoom gestures), ensuring the child
 * [WebView] receives multi-touch gestures unimpeded. Single-pointer pull-to-refresh
 * behavior remains completely intact.
 */
open class ZoomableSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) {
            return false
        }
        return super.onInterceptTouchEvent(ev)
    }
}

/**
 * Diagnostic decision and operation metadata for pull-to-refresh execution.
 */
data class RefreshDecision(
    val canReload: Boolean,
    val targetUrl: String?,
    val operation: String,
    val status: String,
)

/**
 * Validates whether [url] represents a valid HTTP or HTTPS navigation target.
 */
fun isValidNavigationTarget(url: String?): Boolean {
    if (url.isNullOrBlank() || url == "about:blank") return false
    val trimmed = url.trim()
    val isHttp = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    if (!isHttp) return false
    return try {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        val afterScheme = trimmed.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        host.isNotBlank()
    }
}

/**
 * Resolves the authoritative navigation target for a pull-to-refresh request.
 * Prioritizes active committed WebView document URLs over application navigation state,
 * ensuring pull-to-refresh is never suppressed by empty or stale application state.
 */
fun resolveRefreshTarget(
    currentWebViewUrl: String?,
    committedUrl: String?,
    stateUrl: String?,
): String? {
    return when {
        isValidNavigationTarget(currentWebViewUrl) -> currentWebViewUrl
        isValidNavigationTarget(committedUrl) -> committedUrl
        isValidNavigationTarget(stateUrl) -> stateUrl
        else -> null
    }
}

/**
 * Computes pull-to-refresh eligibility, target URL, diagnostic status, and execution operation.
 */
fun computeRefreshDecision(
    isVisible: Boolean,
    currentWebViewUrl: String?,
    committedUrl: String?,
    stateUrl: String?,
    isNetworkDropped: Boolean,
): RefreshDecision {
    val targetUrl = resolveRefreshTarget(currentWebViewUrl, committedUrl, stateUrl)
    val canReload = isVisible && targetUrl != null
    val isCurrentUrlUsable = isValidNavigationTarget(currentWebViewUrl)

    val op = when {
        !canReload -> "none (cancel refreshing: no valid navigation target)"
        isNetworkDropped -> if (isCurrentUrlUsable) "webView.reload() (offline handled)" else "webView.loadUrl($targetUrl) (offline handled)"
        isCurrentUrlUsable -> "webView.reload()"
        else -> "webView.loadUrl($targetUrl)"
    }

    val status = when {
        !canReload -> "REJECTED_NO_TARGET"
        isNetworkDropped -> "HANDLED_OFFLINE"
        else -> "DISPATCHED"
    }

    return RefreshDecision(
        canReload = canReload,
        targetUrl = targetUrl,
        operation = op,
        status = status,
    )
}

/**
 * Coordinates pull-to-refresh lifecycle, ensuring the refresh indicator tracks
 * the active page-loading lifecycle and does not dismiss prematurely on stale completions.
 */
class PullToRefreshCoordinator(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onDismissIndicator: (reason: String, durationMs: Long) -> Unit,
    private val onScheduleDismiss: (delayMs: Long, runnable: Runnable) -> Unit = { _, _ -> },
    private val onCancelScheduledDismiss: () -> Unit = {},
) : WebViewEngine.PageLifecycleListener {

    var isRefreshInProgress: Boolean = false
        private set

    var reloadPageStarted: Boolean = false
        private set

    var refreshStartTime: Long = 0L
        private set

    fun onRefreshGestureTriggered(
        decision: RefreshDecision,
        onDispatchReload: () -> Unit,
        onReject: () -> Unit,
    ) {
        refreshStartTime = clock()
        isRefreshInProgress = true
        reloadPageStarted = false
        onCancelScheduledDismiss()

        DiagnosticTracer.trace(
            category = "PULL_TO_REFRESH",
            event = "REFRESH_STARTED",
            "operation" to decision.operation,
            "refreshStatus" to decision.status,
            "targetUrl" to decision.targetUrl,
            "canReload" to decision.canReload,
        )

        if (decision.canReload && decision.targetUrl != null) {
            onDispatchReload()
        } else {
            DiagnosticTracer.trace(
                category = "PULL_TO_REFRESH",
                event = "REFRESH_CANCELLED",
                "reason" to decision.status,
            )
            isRefreshInProgress = false
            onReject()
            onDismissIndicator("CANCELLED", 0L)
            DiagnosticTracer.trace(
                category = "PULL_TO_REFRESH",
                event = "REFRESH_INDICATOR_DISMISSED",
                "reason" to "CANCELLED",
                "durationMs" to 0L,
            )
        }
    }

    override fun onPageLoadStarted(url: String?) {
        if (isRefreshInProgress) {
            reloadPageStarted = true
            DiagnosticTracer.trace(
                category = "PULL_TO_REFRESH",
                event = "REFRESH_PAGE_STARTED",
                "url" to url,
                "elapsedMs" to (clock() - refreshStartTime),
            )
        }
    }

    override fun onPageLoadCompleted() {
        if (!isRefreshInProgress) return

        val elapsed = clock() - refreshStartTime
        // Stale completion filter: ignore completions arriving < 250ms if navigation has not yet started
        if (!reloadPageStarted && elapsed < 250L) {
            DiagnosticTracer.trace(
                category = "PULL_TO_REFRESH",
                event = "REFRESH_STALE_COMPLETION_IGNORED",
                "elapsedMs" to elapsed,
            )
            return
        }

        DiagnosticTracer.trace(
            category = "PULL_TO_REFRESH",
            event = "REFRESH_LOADING_COMPLETED",
            "elapsedMs" to elapsed,
            "reloadPageStarted" to reloadPageStarted,
        )

        // Ensure minimum perceptible duration for visual confirmation of refresh
        val minDisplayDuration = 500L
        val delayMs = if (elapsed < minDisplayDuration) minDisplayDuration - elapsed else 0L

        if (delayMs > 0L) {
            onScheduleDismiss(delayMs, Runnable {
                finishRefresh("COMPLETED")
            })
        } else {
            finishRefresh("COMPLETED")
        }
    }

    fun onTimeout() {
        if (isRefreshInProgress) {
            DiagnosticTracer.trace(
                category = "PULL_TO_REFRESH",
                event = "REFRESH_TIMEOUT",
                "durationMs" to (clock() - refreshStartTime),
            )
            finishRefresh("TIMEOUT")
        }
    }

    fun onDetachedOrHidden() {
        if (isRefreshInProgress) {
            DiagnosticTracer.trace(
                category = "PULL_TO_REFRESH",
                event = "REFRESH_CANCELLED",
                "reason" to "VIEWPORT_HIDDEN_OR_DETACHED",
            )
            finishRefresh("VIEWPORT_HIDDEN_OR_DETACHED")
        }
    }

    private fun finishRefresh(reason: String) {
        val duration = clock() - refreshStartTime
        isRefreshInProgress = false
        reloadPageStarted = false
        onCancelScheduledDismiss()
        onDismissIndicator(reason, duration)
        DiagnosticTracer.trace(
            category = "PULL_TO_REFRESH",
            event = "REFRESH_INDICATOR_DISMISSED",
            "reason" to reason,
            "totalDurationMs" to duration,
        )
    }
}

/**
 * Backward-compatible helper to test reload eligibility across visible viewport and candidate URLs.
 */
fun canReloadWebpage(
    isVisible: Boolean,
    currentWebViewUrl: String?,
    committedUrl: String? = null,
    stateUrl: String? = null,
): Boolean {
    return isVisible && resolveRefreshTarget(currentWebViewUrl, committedUrl, stateUrl) != null
}

/**
 * Phase 17/18 — active web viewport.
 *
 * The dedicated container for the active browser view. It appears when a
 * genuine address-bar navigation replaces the Home Workspace grid. Since
 * Phase 18 the viewport hosts a real embedded [WebView] configured by the
 * [WebViewEngine] (WebContents debugging, JavaScript, and DOM storage); every
 * network and console event the page produces is intercepted and routed into
 * the DevTools Dashboard feed.
 */
@Composable
fun BrowserView(
    url: String,
    connectionActive: Boolean,
    webViewEngine: WebViewEngine,
    isVisible: Boolean = true,
    navigationId: Long = 0L,
    isBackEnabled: Boolean = isVisible,
    onBackToWorkspace: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val navState = remember { BrowserNavigationState() }
    val indicatorBgColor = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    val indicatorColor = MaterialTheme.colorScheme.primary.toArgb()

    val currentUrlState by rememberUpdatedState(url)
    val currentIsVisible by rememberUpdatedState(isVisible)

    var canGoBack by remember { mutableStateOf(webViewEngine.canGoBack()) }
    var swipeRefreshLayoutRef by remember { mutableStateOf<ZoomableSwipeRefreshLayout?>(null) }
    var pendingDismissRunnable by remember { mutableStateOf<Runnable?>(null) }
    var pendingTimeoutRunnable by remember { mutableStateOf<Runnable?>(null) }

    val coordinator = remember {
        PullToRefreshCoordinator(
            onDismissIndicator = { _, _ ->
                swipeRefreshLayoutRef?.let { srl ->
                    srl.post {
                        if (srl.isRefreshing) {
                            srl.isRefreshing = false
                        }
                    }
                }
            },
            onScheduleDismiss = { delayMs, runnable ->
                swipeRefreshLayoutRef?.let { srl ->
                    pendingDismissRunnable = runnable
                    srl.postDelayed(runnable, delayMs)
                }
            },
            onCancelScheduledDismiss = {
                swipeRefreshLayoutRef?.let { srl ->
                    pendingDismissRunnable?.let { srl.removeCallbacks(it) }
                    pendingDismissRunnable = null
                    pendingTimeoutRunnable?.let { srl.removeCallbacks(it) }
                    pendingTimeoutRunnable = null
                }
            },
        )
    }

    DisposableEffect(webViewEngine, coordinator) {
        webViewEngine.addPageLifecycleListener(coordinator)
        onDispose {
            webViewEngine.removePageLifecycleListener(coordinator)
        }
    }

    DisposableEffect(webViewEngine) {
        val historyListener = WebViewEngine.NavigationHistoryListener { backPossible, _ ->
            canGoBack = backPossible
        }
        webViewEngine.addNavigationHistoryListener(historyListener)
        canGoBack = webViewEngine.canGoBack()
        onDispose {
            webViewEngine.removeNavigationHistoryListener(historyListener)
        }
    }

    // Android Back handler: if WebView has backward navigation history, navigate WebView history;
    // otherwise if at root of browsing session, navigate back to workspace.
    val backHandlingActive = isVisible && isBackEnabled && (canGoBack || onBackToWorkspace != null)
    BackHandler(enabled = backHandlingActive) {
        if (webViewEngine.canGoBack()) {
            webViewEngine.goBack()
            canGoBack = webViewEngine.canGoBack()
        } else {
            onBackToWorkspace?.invoke()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AndroidView(
            factory = { context ->
                ZoomableSwipeRefreshLayout(context).apply {
                    swipeRefreshLayoutRef = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setProgressBackgroundColorSchemeColor(indicatorBgColor)
                    setColorSchemeColors(indicatorColor)

                    val webView = WebView(context).apply {
                        val wvId = System.identityHashCode(this)
                        DiagnosticTracer.trace(
                            category = "WEBVIEW_LIFECYCLE",
                            event = "WEBVIEW_CREATED",
                            "webViewIdentity" to wvId,
                            "initialUrl" to url,
                            "isVisible" to isVisible,
                        )
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                        webViewEngine.configure(webView = this)
                        if (navState.shouldLoad(url, this.url, navigationId)) {
                            DiagnosticTracer.trace(
                                category = "NAVIGATION",
                                event = "INITIAL_LOAD_URL",
                                "webViewIdentity" to wvId,
                                "url" to url,
                            )
                            loadUrl(if (url.isNotBlank()) url else "about:blank")
                        } else if (url.isBlank()) {
                            loadUrl("about:blank")
                        }
                    }
                    addView(webView)

                    // Pull-to-refresh is only allowed when webpage is scrolled to the very top (scroll position zero).
                    setOnChildScrollUpCallback { _, child ->
                        val target = child ?: webView
                        target.canScrollVertically(-1) || target.scrollY > 0
                    }

                    // Reload the current webpage when released after crossing the threshold
                    setOnRefreshListener {
                        val currentWebViewUrl = webView.url
                        val committedUrl = webViewEngine.lastCommittedUrl ?: webViewEngine.currentDocumentUrl
                        val stateUrl = currentUrlState
                        val kernelDropped = webViewEngine.kernel?.isNetworkDropped
                        val engineDropped = webViewEngine.isNetworkDropped
                        val isNetworkDropped = (kernelDropped == true) || engineDropped
                        val blockLoads = webView.settings.blockNetworkLoads
                        val cdpSession = webViewEngine.kernel?.currentSession

                        val decision = computeRefreshDecision(
                            isVisible = currentIsVisible,
                            currentWebViewUrl = currentWebViewUrl,
                            committedUrl = committedUrl,
                            stateUrl = stateUrl,
                            isNetworkDropped = isNetworkDropped,
                        )

                        DiagnosticTracer.trace(
                            category = "PULL_TO_REFRESH",
                            event = "REFRESH_HANDLER_INVOKED",
                            "operation" to decision.operation,
                            "refreshStatus" to decision.status,
                            "webViewIdentity" to System.identityHashCode(webView),
                            "kernelIsNetworkDropped" to kernelDropped,
                            "engineIsNetworkDropped" to engineDropped,
                            "actualBlockNetworkLoads" to blockLoads,
                            "actualCacheMode" to webView.settings.cacheMode,
                            "currentWebViewUrl" to currentWebViewUrl,
                            "stateUrl" to stateUrl,
                            "targetUrl" to decision.targetUrl,
                            "lastCommittedUrl" to webViewEngine.lastCommittedUrl,
                            "canReload" to decision.canReload,
                            "cdpSessionId" to cdpSession?.cdpSessionId,
                            "cdpTargetId" to cdpSession?.targetId,
                        )

                        webView.evaluateJavascript("window.navigator.onLine") { result ->
                            DiagnosticTracer.trace(
                                category = "PULL_TO_REFRESH",
                                event = "REFRESH_NAVIGATOR_ONLINE",
                                "navigatorOnLine" to result,
                            )
                        }

                        // Schedule 10s safety timeout to guarantee UI is never left stuck if reload hangs
                        val timeoutRunnable = Runnable { coordinator.onTimeout() }
                        pendingTimeoutRunnable = timeoutRunnable
                        postDelayed(timeoutRunnable, 10_000L)

                        coordinator.onRefreshGestureTriggered(
                            decision = decision,
                            onDispatchReload = {
                                if (isValidNavigationTarget(currentWebViewUrl)) {
                                    webView.reload()
                                } else {
                                    decision.targetUrl?.let { webView.loadUrl(it) }
                                }
                            },
                            onReject = {
                                isRefreshing = false
                            },
                        )
                    }

                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            val wv = (v as? ViewGroup)?.let { vg ->
                                (0 until vg.childCount).mapNotNull { vg.getChildAt(it) as? WebView }.firstOrNull()
                            }
                            DiagnosticTracer.trace(
                                category = "WEBVIEW_LIFECYCLE",
                                event = "VIEWPORT_ATTACHED_TO_WINDOW",
                                "webViewIdentity" to wv?.let { System.identityHashCode(it) },
                                "blockNetworkLoads" to wv?.settings?.blockNetworkLoads,
                            )
                            // Recovery check: if returning while no refresh is in progress, ensure indicator is off
                            if (!coordinator.isRefreshInProgress && isRefreshing) {
                                isRefreshing = false
                            }
                        }

                        override fun onViewDetachedFromWindow(v: View) {
                            val wv = (v as? ViewGroup)?.let { vg ->
                                (0 until vg.childCount).mapNotNull { vg.getChildAt(it) as? WebView }.firstOrNull()
                            }
                            DiagnosticTracer.trace(
                                category = "WEBVIEW_LIFECYCLE",
                                event = "VIEWPORT_DETACHED_FROM_WINDOW",
                                "webViewIdentity" to wv?.let { System.identityHashCode(it) },
                                "blockNetworkLoads" to wv?.settings?.blockNetworkLoads,
                            )
                        }
                    })
                }
            },
            update = { swipeRefreshLayout ->
                swipeRefreshLayoutRef = swipeRefreshLayout
                swipeRefreshLayout.setProgressBackgroundColorSchemeColor(indicatorBgColor)
                swipeRefreshLayout.setColorSchemeColors(indicatorColor)

                val webView = (0 until swipeRefreshLayout.childCount)
                    .mapNotNull { swipeRefreshLayout.getChildAt(it) as? WebView }
                    .firstOrNull()

                val wvUrl = webView?.url
                val committedUrl = webViewEngine.lastCommittedUrl ?: webViewEngine.currentDocumentUrl
                val canRefresh = isVisible && resolveRefreshTarget(wvUrl, committedUrl, url) != null
                swipeRefreshLayout.isEnabled = canRefresh
                if (!canRefresh && swipeRefreshLayout.isRefreshing) {
                    coordinator.onDetachedOrHidden()
                }

                webView?.let { wv ->
                    DiagnosticTracer.trace(
                        category = "WEBVIEW_LIFECYCLE",
                        event = "BROWSER_VIEW_UPDATE",
                        "webViewIdentity" to System.identityHashCode(wv),
                        "isVisible" to isVisible,
                        "url" to url,
                        "wvUrl" to wv.url,
                        "blockNetworkLoads" to wv.settings.blockNetworkLoads,
                        "cacheMode" to wv.settings.cacheMode,
                    )
                    wv.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                    canGoBack = wv.canGoBack()
                    if (navState.shouldLoad(url, wv.url, navigationId)) {
                        DiagnosticTracer.trace(
                            category = "NAVIGATION",
                            event = "UPDATE_LOAD_URL_DISPATCHED",
                            "webViewIdentity" to System.identityHashCode(wv),
                            "url" to url,
                            "navigationId" to navigationId,
                        )
                        wv.loadUrl(if (url.isNotBlank()) url else "about:blank")
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}