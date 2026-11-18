package com.coppermux.cutools

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.FaultAction
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.network.FetchInterceptionEngine
import com.coppermux.cutools.core.network.FetchLifecyclePhase
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end integration test executed on a genuine Android WebView.
 *
 * Verifies the complete execution path:
 * FaultRule -> FaultInterceptorEngine -> WebViewClient.shouldInterceptRequest -> WebResourceResponse -> JS / Feed
 */
class FaultInjectionEndToEndTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var eventStream: DashboardEventStream
    private lateinit var faultEngine: FaultInterceptorEngine
    private lateinit var webViewEngine: WebViewEngine
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lastJsResult = AtomicReference<String?>(null)
    private var resultLatch = CountDownLatch(1)

    class JsBridge(private val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun postResult(jsonStr: String) {
            onResult(jsonStr)
        }
    }

    private var cdpBridge: AndroidCdpBridge? = null
    private var connectionManager: ConnectionManager? = null
    private var fetchEngine: FetchInterceptionEngine? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return when {
                    path.startsWith("/test-page") -> {
                        val html = """
                            <!DOCTYPE html>
                            <html>
                            <head><title>Fault Injection Test</title></head>
                            <body>
                            <h1>Test Harness</h1>
                            <script>
                            function triggerFetch(url) {
                                fetch(url)
                                    .then(function(res) {
                                        var faultHeader = res.headers.get('x-cutools-fault');
                                        res.text().then(function(body) {
                                            window.androidBridge.postResult(JSON.stringify({
                                                status: res.status,
                                                statusText: res.statusText,
                                                faultHeader: faultHeader,
                                                ok: res.ok,
                                                body: body,
                                                error: null
                                            }));
                                        }).catch(function(readErr) {
                                            window.androidBridge.postResult(JSON.stringify({
                                                status: res.status,
                                                statusText: res.statusText,
                                                faultHeader: faultHeader,
                                                ok: false,
                                                body: null,
                                                error: 'read_error: ' + readErr.name + ': ' + readErr.message
                                            }));
                                        });
                                    })
                                    .catch(function(err) {
                                        window.androidBridge.postResult(JSON.stringify({
                                            status: 0,
                                            statusText: "",
                                            faultHeader: null,
                                            ok: false,
                                            body: null,
                                            error: err.name + ': ' + err.message
                                        }));
                                    });
                            }
                            </script>
                            </body>
                            </html>
                        """.trimIndent()
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "text/html; charset=UTF-8")
                            .setBody(html)
                    }
                    path.startsWith("/api/data") -> {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"server": "real_200", "path": "$path"}""")
                    }
                    else -> MockResponse().setResponseCode(404).setBody("Not Found")
                }
            }
        }
        server.start()
        baseUrl = server.url("/").toString().removeSuffix("/")

        eventStream = DashboardEventStream()
        faultEngine = FaultInterceptorEngine()
        webViewEngine = WebViewEngine(eventStream, faultEngine)

        val latch = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            webView = WebView(context)
            webViewEngine.configure(webView)
            webView.addJavascriptInterface(JsBridge { json ->
                lastJsResult.set(json)
                resultLatch.countDown()
            }, "androidBridge")
            latch.countDown()
        }
        assertTrue("WebView initialization timeout", latch.await(5, TimeUnit.SECONDS))

        // Load the test page
        loadPageSync("$baseUrl/test-page")

        // Start CDP bridge and attach to embedded WebView
        val bridge = AndroidCdpBridge()
        bridge.start()
        cdpBridge = bridge
        val ready = bridge.awaitReady(5000)
        if (ready) {
            val cm = ConnectionManager.create(com.coppermux.cutools.core.transport.WebSocketCdpTransport())
            connectionManager = cm
            val connectLatch = CountDownLatch(1)
            cm.connect(bridge.debugUrl, onConnected = { connectLatch.countDown() })
            connectLatch.await(5, TimeUnit.SECONDS)
            val tm = cm.targetManager
            if (tm != null) {
                val attachLatch = CountDownLatch(1)
                tm.discoverTargets { targets ->
                    val page = targets.firstOrNull { it.isPage }
                    if (page != null) {
                        tm.selectTarget(page.targetId)
                        tm.attachToSelectedTarget {
                            attachLatch.countDown()
                        }
                    } else {
                        attachLatch.countDown()
                    }
                }
                attachLatch.await(5, TimeUnit.SECONDS)
                val session = tm.activeSession
                if (session != null) {
                    val fe = FetchInterceptionEngine(cm.dispatcher, cm.eventRouter, faultInterceptorEngine = faultEngine)
                    fetchEngine = fe
                    fe.bindSession(session)
                }
            }
        }
    }

    @After
    fun tearDown() {
        try { fetchEngine?.detach() } catch (_: Throwable) {}
        try { connectionManager?.disconnect() } catch (_: Throwable) {}
        try { cdpBridge?.close() } catch (_: Throwable) {}
        server.shutdown()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.destroy()
        }
    }

    private fun loadPageSync(url: String) {
        val loadLatch = CountDownLatch(1)
        val listener = WebViewEngine.PageLifecycleListener {
            loadLatch.countDown()
        }
        webViewEngine.addPageLifecycleListener(listener)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.loadUrl(url)
        }
        assertTrue("Page load timeout for $url", loadLatch.await(10, TimeUnit.SECONDS))
        webViewEngine.removePageLifecycleListener(listener)
    }

    private fun executeFetchInPage(endpoint: String): JSONObject {
        resultLatch = CountDownLatch(1)
        lastJsResult.set(null)
        val fullUrl = if (endpoint.startsWith("http")) endpoint else "$baseUrl$endpoint"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript("triggerFetch('$fullUrl');", null)
        }
        assertTrue("Fetch response timeout for $fullUrl", resultLatch.await(10, TimeUnit.SECONDS))
        val raw = lastJsResult.get()
        assertNotNull("Bridge result must not be null", raw)
        return JSONObject(raw!!)
    }

    // ================================================================
    // Test 1: Deterministic HTTP 200 Baseline -> HTTP 404 Injection -> Restoration
    // ================================================================

    @Test
    fun http404FaultInjection_fullLifecycle() {
        val targetEndpoint = "/api/data?id=test-404-1"
        val fullTargetUrl = "$baseUrl$targetEndpoint"

        // 1. Request succeeds normally with HTTP 200
        val normalResult = executeFetchInPage(targetEndpoint)
        assertEquals(200, normalResult.getInt("status"))
        assertTrue(normalResult.getBoolean("ok"))
        assertTrue(normalResult.isNull("faultHeader"))
        assertTrue(normalResult.getString("body").contains("real_200"))

        // 2. Arm a matching HTTP 404 rule
        val rule = FaultRule(
            id = "rule-404-e2e",
            urlPattern = "$baseUrl/api/data?id=test-404-1",
            method = "GET",
            statusCode = 404,
        )
        faultEngine.upsert(rule)
        assertTrue(faultEngine.hasActiveRuleFor(fullTargetUrl, "GET"))

        // 3. Trigger a new request
        val injectedResult = executeFetchInPage(targetEndpoint)

        // 4. Actual response is HTTP 404 with X-CuTools-Fault header
        assertEquals(404, injectedResult.getInt("status"))
        assertFalse(injectedResult.getBoolean("ok"))
        assertEquals("injected", injectedResult.getString("faultHeader"))

        // 6. Remove the rule
        assertTrue(faultEngine.remove("rule-404-e2e"))
        assertFalse(faultEngine.hasActiveRuleFor(fullTargetUrl, "GET"))

        // 7. Subsequent request succeeds normally again with HTTP 200
        val restoredResult = executeFetchInPage(targetEndpoint)
        assertEquals(200, restoredResult.getInt("status"))
        assertTrue(restoredResult.getBoolean("ok"))
        assertTrue(restoredResult.isNull("faultHeader"))
        assertTrue(restoredResult.getString("body").contains("real_200"))
    }

    // ================================================================
    // Test 2: Connection Drop Fault Injection and Recovery
    // ================================================================

    @Test
    fun connectionDropFaultInjection_fullLifecycle() {
        val targetEndpoint = "/api/data?id=test-drop-1"
        val fullTargetUrl = "$baseUrl$targetEndpoint"

        // 1. Confirm request succeeds normally
        val normalResult = executeFetchInPage(targetEndpoint)
        assertEquals(200, normalResult.getInt("status"))
        assertTrue(normalResult.getBoolean("ok"))

        // 2. Arm a connection-drop rule
        val dropRule = FaultRule(
            id = "rule-drop-e2e",
            urlPattern = "$baseUrl/api/data?id=test-drop-1",
            method = "GET",
            dropConnection = true,
        )
        faultEngine.upsert(dropRule)
        assertTrue(faultEngine.hasActiveRuleFor(fullTargetUrl, "GET"))

        // Ensure FetchInterceptionEngine has armed Fetch.enable with targeted pattern
        val fe = fetchEngine
        assertNotNull("FetchInterceptionEngine must be attached", fe)
        val deadline = System.currentTimeMillis() + 5000
        while (fe?.state?.phase != FetchLifecyclePhase.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(FetchLifecyclePhase.READY, fe?.state?.phase)

        // 3. Trigger a new matching request
        val dropResult = executeFetchInPage(targetEndpoint)
        println("REAL ANDROID DROP RESULT: $dropResult")

        // 4. Confirm CuTools does not crash
        // 5. Determine the actual failure observed by JavaScript
        assertFalse("Request must not succeed", dropResult.getBoolean("ok"))
        val errorMsg = dropResult.optString("error", "")
        assertTrue(
            "JavaScript fetch must report a network TypeError (got: $errorMsg)",
            errorMsg.contains("TypeError") || errorMsg.contains("Failed to fetch")
        )

        // 7. Remove rule and confirm normal operation resumes
        assertTrue(faultEngine.remove("rule-drop-e2e"))
        assertFalse(faultEngine.hasActiveRuleFor(fullTargetUrl, "GET"))

        val restoredResult = executeFetchInPage(targetEndpoint)
        assertEquals(200, restoredResult.getInt("status"))
        assertTrue(restoredResult.getBoolean("ok"))
        assertTrue(restoredResult.getString("body").contains("real_200"))
    }

    // ================================================================
    // Test 3: Wikipedia Address-Bar Navigation and Pull-to-Refresh Verification
    // ================================================================

    @Test
    fun wikipediaAddressBarNavigationAndPullToRefresh_verification() {
        val fe = fetchEngine
        assertNotNull("FetchInterceptionEngine must be attached", fe)

        // 1. Initial navigation: simulate address bar navigation to wikipedia.org
        loadPageSync("https://wikipedia.org")

        // Committed URL in WebViewEngine reflects final destination
        val committedUrl = webViewEngine.currentDocumentUrl
        assertNotNull("Committed URL must not be null", committedUrl)
        assertTrue(
            "Committed URL must reflect Wikipedia destination (got: $committedUrl)",
            committedUrl!!.contains("wikipedia.org")
        )

        // 2. Arm Drop Connection rule targeting the origin / committed URL
        val dropRule = FaultRule(
            id = "wiki-drop-rule",
            urlPattern = "wikipedia.org",
            dropConnection = true,
        )
        faultEngine.upsert(dropRule)
        assertTrue(faultEngine.hasActiveRuleFor(committedUrl, "GET"))

        val deadline = System.currentTimeMillis() + 5000
        while (fe?.state?.phase != FetchLifecyclePhase.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(FetchLifecyclePhase.READY, fe?.state?.phase)

        // 3. Test Pull-to-refresh: webView.reload()
        val reloadLatch = CountDownLatch(1)
        val reloadListener = WebViewEngine.PageLifecycleListener {
            reloadLatch.countDown()
        }
        webViewEngine.addPageLifecycleListener(reloadListener)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.reload()
        }
        assertTrue("Reload completion latch timed out", reloadLatch.await(10, TimeUnit.SECONDS))
        webViewEngine.removePageLifecycleListener(reloadListener)

        // Confirm intercepted count incremented
        assertTrue(
            "Interception engine must have intercepted the pull-to-refresh reload request",
            fe!!.state.interceptedCount > 0
        )

        // 4. Clear restores normal browsing
        faultEngine.clear()
        val clearDeadline = System.currentTimeMillis() + 5000
        while (fe.state.phase != FetchLifecyclePhase.DISABLED && System.currentTimeMillis() < clearDeadline) {
            Thread.sleep(50)
        }
        assertEquals(FetchLifecyclePhase.DISABLED, fe.state.phase)

        // Normal browsing succeeds without interception
        loadPageSync("https://www.wikipedia.org/")
        assertTrue(webViewEngine.currentDocumentUrl?.contains("wikipedia.org") == true)

        // 5. Page-specific rule does not affect unrelated pages
        val specificRule = FaultRule(
            id = "specific-wiki-page",
            urlPattern = "https://en.wikipedia.org/wiki/Special:Random",
            dropConnection = true,
        )
        faultEngine.upsert(specificRule)
        assertFalse(
            "Page-specific rule must not affect main Wikipedia portal",
            faultEngine.hasActiveRuleFor("https://www.wikipedia.org/", "GET")
        )
        faultEngine.clear()
    }
}
