package com.coppermux.cutools.core.browser

import android.webkit.ConsoleMessage
import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Focused unit tests for [WebViewEngine] console lifecycle and reset generation guards.
 */
class WebViewEngineTest {

    private lateinit var eventStream: DashboardEventStream
    private lateinit var webViewEngine: WebViewEngine

    @Before
    fun setUp() {
        eventStream = DashboardEventStream()
        webViewEngine = WebViewEngine(eventStream)
    }

    private fun mockConsoleMessage(
        level: ConsoleMessage.MessageLevel = ConsoleMessage.MessageLevel.LOG,
        text: String = "test-log",
        source: String = "https://example.com/app.js",
        line: Int = 10,
    ): ConsoleMessage {
        val msg = mock<ConsoleMessage>()
        whenever(msg.messageLevel()).thenReturn(level)
        whenever(msg.message()).thenReturn(text)
        whenever(msg.sourceId()).thenReturn(source)
        whenever(msg.lineNumber()).thenReturn(line)
        return msg
    }

    @Test
    fun normalPageNavigation_allowsConsoleMessages() {
        // 1. Initial page navigation starts
        webViewEngine.interceptorClient.onPageStarted(null, "https://www.cnn.com", null)

        // 2. Page logs a message
        val msg = mockConsoleMessage(text = "CNN loaded successfully")
        webViewEngine.interceptorChromeClient.onConsoleMessage(msg)

        assertEquals(1, eventStream.state.eventCount)
        assertEquals("CNN loaded successfully", eventStream.state.events.first().message)
    }

    @Test
    fun lateWebViewConsoleMessages_cannotRestorePreResetLogs() {
        // 1. Navigate to first page and log
        webViewEngine.interceptorClient.onPageStarted(null, "https://www.cnn.com", null)
        val msg1 = mockConsoleMessage(text = "Initial message from CNN")
        webViewEngine.interceptorChromeClient.onConsoleMessage(msg1)
        assertEquals(1, eventStream.state.eventCount)

        // 2. Reset app / dashboard and webViewEngine
        eventStream.reset()
        webViewEngine.reset()
        assertEquals(0, eventStream.state.eventCount)

        // 3. Late console message arrives from the pre-reset page lifecycle (e.g. unload / timer)
        val lateMsg = mockConsoleMessage(text = "Late unloading event from CNN")
        val handled = webViewEngine.interceptorChromeClient.onConsoleMessage(lateMsg)

        // Late message must be rejected and not appended
        assertTrue(!handled)
        assertEquals(0, eventStream.state.eventCount)

        // 4. about:blank during reset clearing does not prematurely activate post-reset generation
        webViewEngine.interceptorClient.onPageStarted(null, "about:blank", null)
        val blankMsg = mockConsoleMessage(text = "Message from about:blank")
        webViewEngine.interceptorChromeClient.onConsoleMessage(blankMsg)
        assertEquals(0, eventStream.state.eventCount)

        // 5. Navigate to post-reset page (Gumtree)
        webViewEngine.interceptorClient.onPageStarted(null, "https://www.gumtree.co.za", null)
        val newMsg = mockConsoleMessage(text = "Welcome to Gumtree", source = "https://www.gumtree.co.za/home.js")
        webViewEngine.interceptorChromeClient.onConsoleMessage(newMsg)

        assertEquals(1, eventStream.state.eventCount)
        assertEquals("Welcome to Gumtree", eventStream.state.events.first().message)
    }

    @Test
    fun crossDocumentConsoleMessage_delayedPreResetLog_rejectedAfterNewNavigationStarted() {
        // 1. CNN active
        webViewEngine.interceptorClient.onPageStarted(null, "https://www.cnn.com", null)
        val cnnMsg1 = mockConsoleMessage(text = "CNN initial log", source = "https://www.cnn.com/app.js")
        webViewEngine.interceptorChromeClient.onConsoleMessage(cnnMsg1)
        assertEquals(1, eventStream.state.eventCount)

        // 2. Coordinated reset
        eventStream.reset()
        webViewEngine.reset()
        assertEquals(0, eventStream.state.eventCount)

        // 3. New navigation begins to Gumtree (onPageStarted)
        webViewEngine.interceptorClient.onPageStarted(null, "https://www.gumtree.co.za/home", null)

        // 4. Delayed CNN log arrives while Gumtree is loading
        val delayedCnnMsg = mockConsoleMessage(text = "Delayed CNN beacon", source = "https://www.cnn.com/beacon.js")
        val handledDelayedCnn = webViewEngine.interceptorChromeClient.onConsoleMessage(delayedCnnMsg)

        // Must be rejected by host-matching attribution guard
        assertTrue("Delayed pre-reset host console message must be rejected", !handledDelayedCnn)
        assertEquals("Event stream must remain empty after delayed old-document log", 0, eventStream.state.eventCount)

        // 5. Legitimate Gumtree log arrives
        val gumtreeMsg = mockConsoleMessage(text = "Gumtree ready", source = "https://www.gumtree.co.za/home.js")
        val handledGumtree = webViewEngine.interceptorChromeClient.onConsoleMessage(gumtreeMsg)

        assertTrue("Legitimate post-reset document log must be accepted", handledGumtree)
        assertEquals(1, eventStream.state.eventCount)
        assertEquals("Gumtree ready", eventStream.state.events.first().message)

        // 6. Navigation finishes
        webViewEngine.interceptorClient.onPageFinished(null, "https://www.gumtree.co.za/home")

        // Further post-navigation logs are accepted
        val gumtreeMsg2 = mockConsoleMessage(text = "Gumtree interactive", source = "https://www.gumtree.co.za/home.js")
        val handled2 = webViewEngine.interceptorChromeClient.onConsoleMessage(gumtreeMsg2)
        assertTrue(handled2)
        assertEquals(2, eventStream.state.eventCount)
    }

    @Test
    fun pageLifecycleListener_notifiedOnPageStarted() {
        var startedUrl: String? = null
        val listener = object : WebViewEngine.PageLifecycleListener {
            override fun onPageLoadCompleted() {}
            override fun onPageLoadStarted(url: String?) {
                startedUrl = url
            }
        }
        webViewEngine.addPageLifecycleListener(listener)

        webViewEngine.interceptorClient.onPageStarted(null, "https://example.com/page", null)
        assertEquals("https://example.com/page", startedUrl)

        webViewEngine.removePageLifecycleListener(listener)
        webViewEngine.interceptorClient.onPageStarted(null, "https://example.com/other", null)
        assertEquals("https://example.com/page", startedUrl)
    }

    @Test
    fun pageLifecycleListener_notifiedOnPageFinished() {
        var completedCount = 0
        val listener = WebViewEngine.PageLifecycleListener { completedCount++ }
        webViewEngine.addPageLifecycleListener(listener)

        webViewEngine.interceptorClient.onPageFinished(null, "https://example.com")
        assertEquals(1, completedCount)

        webViewEngine.removePageLifecycleListener(listener)
        webViewEngine.interceptorClient.onPageFinished(null, "https://example.com")
        assertEquals(1, completedCount)
    }

    @Test
    fun pageLifecycleListener_notifiedOnReceivedError() {
        var completedCount = 0
        val listener = WebViewEngine.PageLifecycleListener { completedCount++ }
        webViewEngine.addPageLifecycleListener(listener)

        webViewEngine.interceptorClient.onReceivedError(null, null, null as android.webkit.WebResourceError?)
        assertEquals(1, completedCount)

        webViewEngine.interceptorClient.onReceivedError(null, -1, "Connection failed", "https://example.com")
        assertEquals(2, completedCount)
    }

    @Test
    fun pageLifecycleListener_notifiedOnProgressCompleted() {
        var completedCount = 0
        val listener = WebViewEngine.PageLifecycleListener { completedCount++ }
        webViewEngine.addPageLifecycleListener(listener)

        // Mid-flight progress does not complete
        webViewEngine.interceptorChromeClient.onProgressChanged(null, 50)
        assertEquals(0, completedCount)

        // 100% progress notifies completion
        webViewEngine.interceptorChromeClient.onProgressChanged(null, 100)
        assertEquals(1, completedCount)
    }

    @Test
    fun pageLifecycleListener_notifiedOnReset() {
        var completedCount = 0
        val listener = WebViewEngine.PageLifecycleListener { completedCount++ }
        webViewEngine.addPageLifecycleListener(listener)

        webViewEngine.reset()
        assertEquals(1, completedCount)
    }

    @Test
    fun canGoBack_withoutAttachedWebView_returnsFalse() {
        assertFalse(webViewEngine.canGoBack())
        assertFalse(webViewEngine.goBack())
        assertFalse(webViewEngine.canGoForward())
        assertFalse(webViewEngine.goForward())
    }

    @Test
    fun canGoBack_and_goBack_withAttachedWebView() {
        val mockWebView = mock<WebView>()
        whenever(mockWebView.canGoBack()).thenReturn(true)
        whenever(mockWebView.canGoForward()).thenReturn(false)

        webViewEngine.attachWebView(mockWebView)

        assertTrue(webViewEngine.canGoBack())
        assertFalse(webViewEngine.canGoForward())

        val dispatched = webViewEngine.goBack()
        assertTrue(dispatched)
        verify(mockWebView).goBack()
    }

    @Test
    fun goBack_whenCanGoBackIsFalse_doesNotInvokeWebViewGoBack() {
        val mockWebView = mock<WebView>()
        whenever(mockWebView.canGoBack()).thenReturn(false)

        webViewEngine.attachWebView(mockWebView)

        val dispatched = webViewEngine.goBack()
        assertFalse(dispatched)
        verify(mockWebView, never()).goBack()
    }

    @Test
    fun navigationHistoryListener_notifiedOnPageStartedAndFinished() {
        var notifiedCount = 0
        var lastCanGoBack = false
        val listener = WebViewEngine.NavigationHistoryListener { canGoBack, _ ->
            notifiedCount++
            lastCanGoBack = canGoBack
        }
        webViewEngine.addNavigationHistoryListener(listener)

        val mockWebView = mock<WebView>()
        whenever(mockWebView.canGoBack()).thenReturn(true)

        webViewEngine.interceptorClient.onPageStarted(mockWebView, "https://example.com/page2", null)
        assertEquals(1, notifiedCount)
        assertTrue(lastCanGoBack)

        whenever(mockWebView.canGoBack()).thenReturn(false)
        webViewEngine.interceptorClient.onPageFinished(mockWebView, "https://example.com/page2")
        assertEquals(2, notifiedCount)
        assertFalse(lastCanGoBack)

        webViewEngine.removeNavigationHistoryListener(listener)
        webViewEngine.interceptorClient.onPageFinished(mockWebView, "https://example.com/page3")
        assertEquals(2, notifiedCount)
    }

    @Test
    fun navigationHistoryListener_notifiedOnDoUpdateVisitedHistory() {
        var notifiedCount = 0
        var lastCanGoBack = false
        val listener = WebViewEngine.NavigationHistoryListener { canGoBack, _ ->
            notifiedCount++
            lastCanGoBack = canGoBack
        }
        webViewEngine.addNavigationHistoryListener(listener)

        val mockWebView = mock<WebView>()
        whenever(mockWebView.canGoBack()).thenReturn(true)

        webViewEngine.interceptorClient.doUpdateVisitedHistory(mockWebView, "https://example.com/#section", false)
        assertEquals(1, notifiedCount)
        assertTrue(lastCanGoBack)
    }

    @Test
    fun navigationHistoryListener_notifiedOnReset() {
        var notifiedCount = 0
        var lastCanGoBack = true
        val listener = WebViewEngine.NavigationHistoryListener { canGoBack, _ ->
            notifiedCount++
            lastCanGoBack = canGoBack
        }
        webViewEngine.addNavigationHistoryListener(listener)

        webViewEngine.reset()
        assertEquals(1, notifiedCount)
        assertFalse(lastCanGoBack)
    }

    @Test
    fun configure_enablesNativePinchToZoomAndDisablesOnScreenControls() {
        val mockWebView = mock<WebView>()
        val mockSettings = mock<WebSettings>()
        whenever(mockWebView.settings).thenReturn(mockSettings)

        org.mockito.Mockito.mockStatic(WebView::class.java).use {
            webViewEngine.configure(mockWebView)
        }

        verify(mockSettings).setSupportZoom(true)
        verify(mockSettings).builtInZoomControls = true
        verify(mockSettings).displayZoomControls = false
    }

    @Test
    fun shouldInterceptRequest_unmatchedRequest_returnsNull() {
        val mockView = mock<WebView>()
        val mockRequest = mock<android.webkit.WebResourceRequest>()
        val mockUri = mock<android.net.Uri>()
        whenever(mockUri.toString()).thenReturn("https://example.com/api/test")
        whenever(mockRequest.url).thenReturn(mockUri)
        whenever(mockRequest.method).thenReturn("GET")

        val response = webViewEngine.interceptorClient.shouldInterceptRequest(mockView, mockRequest)
        org.junit.Assert.assertNull(response)
    }

    @Test
    fun shouldInterceptRequest_nullInputs_returnsNullSafely() {
        val nullRequest: android.webkit.WebResourceRequest? = null
        org.junit.Assert.assertNull(webViewEngine.interceptorClient.shouldInterceptRequest(null, nullRequest))
        val mockView = mock<WebView>()
        org.junit.Assert.assertNull(webViewEngine.interceptorClient.shouldInterceptRequest(mockView, nullRequest))
    }

    @Test
    fun shouldInterceptRequest_recoveringFromEngineException_returnsNullWithoutCrashing() {
        val mockFaultEngine = mock<FaultInterceptorEngine>()
        whenever(mockFaultEngine.decisionFor(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenThrow(RuntimeException("Simulated fault engine failure"))

        val engineWithBrokenFaults = WebViewEngine(eventStream, mockFaultEngine)
        val mockView = mock<WebView>()
        val mockRequest = mock<android.webkit.WebResourceRequest>()
        val mockUri = mock<android.net.Uri>()
        whenever(mockUri.toString()).thenReturn("https://example.com/api/test")
        whenever(mockRequest.url).thenReturn(mockUri)
        whenever(mockRequest.method).thenReturn("GET")

        val response = engineWithBrokenFaults.interceptorClient.shouldInterceptRequest(mockView, mockRequest)
        org.junit.Assert.assertNull("Must catch exception and return null fallback", response)
    }

    @Test
    fun onPageFinished_updatesLastCommittedUrl_whenNetworkNotDropped() {
        val mockView = mock<WebView>()
        whenever(mockView.url).thenReturn("https://example.com/initial")

        webViewEngine.interceptorClient.onPageFinished(mockView, "https://example.com/initial")
        assertEquals("https://example.com/initial", webViewEngine.lastCommittedUrl)
        assertEquals("https://example.com/initial", webViewEngine.currentDocumentUrl)
    }

    @Test
    fun failedNavigationDuringNetworkDrop_doesNotOverwriteLastCommittedUrl() {
        val mockSettings = mock<WebSettings>()
        val mockView = mock<WebView>()
        whenever(mockView.settings).thenReturn(mockSettings)
        whenever(mockView.url).thenReturn("https://example.com/initial")
        webViewEngine.attachWebView(mockView)

        webViewEngine.interceptorClient.onPageFinished(mockView, "https://example.com/initial")
        assertEquals("https://example.com/initial", webViewEngine.lastCommittedUrl)

        // Drop network (CDP-only: blockNetworkLoads is never enabled)
        webViewEngine.setNetworkDrop(true)
        assertTrue(webViewEngine.isNetworkDropped)
        verify(mockSettings, never()).blockNetworkLoads = true
        verify(mockSettings, org.mockito.kotlin.atLeastOnce()).blockNetworkLoads = false

        // Attempting to navigate while dropped
        webViewEngine.interceptorClient.onPageStarted(mockView, "https://example.com/failed", null)

        // lastCommittedUrl must remain the last confirmed URL
        assertEquals("https://example.com/initial", webViewEngine.lastCommittedUrl)

        // Even if onPageFinished reports an error URL or failed URL while dropped
        webViewEngine.interceptorClient.onPageFinished(mockView, "https://example.com/failed")
        assertEquals("https://example.com/initial", webViewEngine.lastCommittedUrl)

        // Restoring network preserves URL and never enables blockNetworkLoads
        webViewEngine.setNetworkDrop(false)
        assertFalse(webViewEngine.isNetworkDropped)
        verify(mockSettings, never()).blockNetworkLoads = true
        assertEquals("https://example.com/initial", webViewEngine.lastCommittedUrl)
    }
}
