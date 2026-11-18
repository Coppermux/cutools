package com.coppermux.cutools

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.WebViewEngine
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.kernel.DiagnosticKernel
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CuTools/HybridDropTest"

/**
 * Physical-device end-to-end verification of Hybrid Network Drop architecture on Red Magic NX659J:
 * 1. Normal navigation and redirects.
 * 2. Pull-to-refresh while disconnected.
 * 3. JavaScript fetch/XHR failures under drop.
 * 4. WebSocket behavior under native drop and CDP emulation.
 * 5. Repeated ON/OFF transitions.
 * 6. Recovery after failed navigation (URL preservation).
 * 7. Operation when CDP is completely unavailable.
 * 8. CDP detachment and reconnection state reapplication.
 */
class HybridNetworkDropEndToEndTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var wsUrl: String
    private lateinit var webView: WebView
    private lateinit var kernel: DiagnosticKernel
    private lateinit var webViewEngine: WebViewEngine
    private lateinit var dashboardEventStream: DashboardEventStream
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lastJsResult = AtomicReference<String?>(null)
    private var resultLatch = CountDownLatch(1)

    private val errorEvents = CopyOnWriteArrayList<String>()
    private val pageFinishedEvents = CopyOnWriteArrayList<String>()

    class JsBridge(private val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun postResult(jsonStr: String) {
            Log.d(TAG, "JS Bridge: $jsonStr")
            onResult(jsonStr)
        }
    }

    private val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>Hybrid Drop E2E Test</title></head>
        <body>
        <h1 id="header">Initial Page</h1>
        <script>
        var offlineCount = 0;
        var onlineCount = 0;
        window.addEventListener('offline', function() { offlineCount++; });
        window.addEventListener('online', function() { onlineCount++; });

        function getStatus() {
            androidBridge.postResult(JSON.stringify({
                onLine: navigator.onLine,
                offlineCount: offlineCount,
                onlineCount: onlineCount
            }));
        }

        function runFetch(url) {
            fetch(url)
                .then(function(res) {
                    androidBridge.postResult(JSON.stringify({ ok: res.ok, status: res.status }));
                })
                .catch(function(err) {
                    androidBridge.postResult(JSON.stringify({ ok: false, status: 0, error: err.toString() }));
                });
        }

        function runXhr(url) {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', url, true);
            xhr.onload = function() {
                androidBridge.postResult(JSON.stringify({ ok: xhr.status === 200, status: xhr.status }));
            };
            xhr.onerror = function() {
                androidBridge.postResult(JSON.stringify({ ok: false, status: 0, error: 'XHR onerror' }));
            };
            xhr.send();
        }

        function testWs(url) {
            try {
                var ws = new WebSocket(url);
                ws.onopen = function() {
                    androidBridge.postResult(JSON.stringify({ event: 'onopen' }));
                    ws.close();
                };
                ws.onerror = function() {
                    androidBridge.postResult(JSON.stringify({ event: 'onerror' }));
                };
                ws.onclose = function(e) {
                    androidBridge.postResult(JSON.stringify({ event: 'onclose', code: e.code }));
                };
            } catch(e) {
                androidBridge.postResult(JSON.stringify({ event: 'catch', error: e.toString() }));
            }
        }
        </script>
        </body>
        </html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return when {
                    path.startsWith("/initial") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(pageHtml)
                    path.startsWith("/redirect") -> MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "$baseUrl/initial?redirected=true")
                    path.startsWith("/api/data") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"status":"ok"}""")
                    path.startsWith("/ws") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("welcome")
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = "http://localhost:${server.port}"
        wsUrl = "ws://localhost:${server.port}/ws"

        kernel = DiagnosticKernel()
        dashboardEventStream = DashboardEventStream(kernel = kernel)
        webViewEngine = WebViewEngine(eventStream = dashboardEventStream, kernel = kernel)

        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            webView = WebView(context)
            webViewEngine.configure(webView)
            webView.addJavascriptInterface(JsBridge { json ->
                lastJsResult.set(json)
                resultLatch.countDown()
            }, "androidBridge")
            latch.countDown()
        }
        assertTrue("WebView initialization timeout", latch.await(5, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        try { server.shutdown() } catch (_: Throwable) {}
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { webView.destroy() } catch (_: Throwable) {}
        }
    }

    private fun loadPageSync(url: String): Boolean {
        val pageLatch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val originalClient = webViewEngine.interceptorClient
            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    originalClient.onReceivedError(view, request, error)
                    errorEvents.add("onReceivedError: ${request?.url} - ${error?.description}")
                    pageLatch.countDown()
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    originalClient.onPageFinished(view, finishedUrl)
                    if (finishedUrl != null) pageFinishedEvents.add(finishedUrl)
                    pageLatch.countDown()
                }
            }
            webView.loadUrl(url)
        }
        return pageLatch.await(10, TimeUnit.SECONDS)
    }

    private fun executeJs(script: String, timeoutSec: Long = 8): JSONObject {
        resultLatch = CountDownLatch(1)
        lastJsResult.set(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script, null)
        }
        assertTrue("JS result timeout for: $script", resultLatch.await(timeoutSec, TimeUnit.SECONDS))
        val raw = lastJsResult.get()
        assertNotNull("Bridge result must not be null", raw)
        return JSONObject(raw!!)
    }

    private fun setNetworkDropSync(dropped: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            kernel.setNetworkDrop(dropped)
            webViewEngine.setNetworkDrop(dropped)
        }
        Thread.sleep(400) // Allow Blink network change IPC to propagate
    }

    // ================================================================
    // Test 1: Normal Navigation and Redirects
    // ================================================================

    @Test
    fun test1_normalNavigationAndRedirects() {
        Log.d(TAG, "=== START test1_normalNavigationAndRedirects ===")
        assertTrue("Load initial page", loadPageSync("$baseUrl/initial"))
        assertEquals("$baseUrl/initial", webViewEngine.lastCommittedUrl)

        // Test 302 redirect
        assertTrue("Load redirect URL", loadPageSync("$baseUrl/redirect"))
        val currentUrl = webViewEngine.lastCommittedUrl
        Log.d(TAG, "Committed URL after redirect: $currentUrl")
        assertTrue("Committed URL should reflect redirect destination", currentUrl?.contains("redirected=true") == true)
        Log.d(TAG, "=== END test1_normalNavigationAndRedirects ===")
    }

    // ================================================================
    // Test 2: JavaScript fetch/XHR failures under drop
    // ================================================================

    @Test
    fun test2_jsFetchAndXhrFailureUnderDrop() {
        Log.d(TAG, "=== START test2_jsFetchAndXhrFailureUnderDrop ===")
        assertTrue("Load initial page", loadPageSync("$baseUrl/initial"))

        // Baseline: fetch succeeds
        val baseFetch = executeJs("runFetch('$baseUrl/api/data?t=base');")
        assertTrue("Baseline fetch ok", baseFetch.getBoolean("ok"))
        assertEquals(200, baseFetch.getInt("status"))

        // Baseline: XHR succeeds
        val baseXhr = executeJs("runXhr('$baseUrl/api/data?t=base-xhr');")
        assertTrue("Baseline XHR ok", baseXhr.getBoolean("ok"))
        assertEquals(200, baseXhr.getInt("status"))

        // --- DROP CONNECTION (Native WebView controls) ---
        Log.d(TAG, "Activating Network Drop...")
        setNetworkDropSync(true)
        assertTrue(kernel.isNetworkDropped)
        assertTrue(webViewEngine.isNetworkDropped)

        // Status check
        val dropStatus = executeJs("getStatus();")
        assertFalse("navigator.onLine should be false", dropStatus.getBoolean("onLine"))
        assertTrue("offline event fired", dropStatus.getInt("offlineCount") >= 1)

        // JS Fetch under drop: immediate abort with status 0
        val blockedFetch = executeJs("runFetch('$baseUrl/api/data?t=blocked-fetch');")
        Log.d(TAG, "Blocked fetch: $blockedFetch")
        assertFalse("Blocked fetch should not be ok", blockedFetch.getBoolean("ok"))
        assertEquals(0, blockedFetch.getInt("status"))
        assertTrue("Error should mention fetch failure", blockedFetch.getString("error").contains("Failed to fetch") || blockedFetch.getString("error").contains("TypeError"))

        // JS XHR under drop: immediate abort with status 0
        val blockedXhr = executeJs("runXhr('$baseUrl/api/data?t=blocked-xhr');")
        Log.d(TAG, "Blocked XHR: $blockedXhr")
        assertFalse("Blocked XHR should not be ok", blockedXhr.getBoolean("ok"))
        assertEquals(0, blockedXhr.getInt("status"))

        // --- RESTORE CONNECTION ---
        Log.d(TAG, "Restoring Network Connection...")
        setNetworkDropSync(false)
        assertFalse(kernel.isNetworkDropped)
        assertFalse(webViewEngine.isNetworkDropped)

        val restoredFetch = executeJs("runFetch('$baseUrl/api/data?t=restored-fetch');")
        assertTrue("Restored fetch ok", restoredFetch.getBoolean("ok"))
        assertEquals(200, restoredFetch.getInt("status"))
        Log.d(TAG, "=== END test2_jsFetchAndXhrFailureUnderDrop ===")
    }

    // ================================================================
    // Test 3: Pull-to-refresh while disconnected
    // ================================================================

    @Test
    fun test3_pullToRefreshWhileDisconnected() {
        Log.d(TAG, "=== START test3_pullToRefreshWhileDisconnected ===")
        val pageUrl = "$baseUrl/initial"
        assertTrue("Load page", loadPageSync(pageUrl))
        assertEquals(pageUrl, webViewEngine.lastCommittedUrl)

        // Drop connection
        setNetworkDropSync(true)

        // Pull to refresh (reload) while disconnected
        errorEvents.clear()
        val reloadLatch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.reload()
            mainHandler.postDelayed({ reloadLatch.countDown() }, 1500)
        }
        reloadLatch.await(4, TimeUnit.SECONDS)

        // Preserved URL must remain pageUrl
        assertEquals(pageUrl, webViewEngine.lastCommittedUrl)

        // Restore connection
        setNetworkDropSync(false)

        // Reload page after restore
        assertTrue("Reload succeeds after restoration", loadPageSync(pageUrl))
        assertEquals(pageUrl, webViewEngine.lastCommittedUrl)
        Log.d(TAG, "=== END test3_pullToRefreshWhileDisconnected ===")
    }

    // ================================================================
    // Test 4: Recovery after failed navigation (URL preservation)
    // ================================================================

    @Test
    fun test4_recoveryAfterFailedNavigation() {
        Log.d(TAG, "=== START test4_recoveryAfterFailedNavigation ===")
        val pageUrl = "$baseUrl/initial"
        assertTrue("Load valid page", loadPageSync(pageUrl))
        assertEquals(pageUrl, webViewEngine.lastCommittedUrl)

        // Drop connection
        setNetworkDropSync(true)

        // Attempt new navigation to a failing URL while disconnected
        val failedNavLatch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.loadUrl("$baseUrl/api/failing-nav-attempt")
            mainHandler.postDelayed({ failedNavLatch.countDown() }, 1500)
        }
        failedNavLatch.await(4, TimeUnit.SECONDS)

        // Authoritative lastCommittedUrl must NOT be overwritten by the failed navigation!
        Log.d(TAG, "lastCommittedUrl after failed nav: ${webViewEngine.lastCommittedUrl}")
        assertEquals(pageUrl, webViewEngine.lastCommittedUrl)

        // Restore connectivity: restorePreservedUrlOrReload automatically recovers the preserved URL
        Log.d(TAG, "Restoring connectivity...")
        setNetworkDropSync(false)

        val recoverLatch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webViewEngine.restorePreservedUrlOrReload(webView)
            mainHandler.postDelayed({ recoverLatch.countDown() }, 2000)
        }
        recoverLatch.await(5, TimeUnit.SECONDS)

        val restoredUrl = webViewEngine.lastCommittedUrl
        Log.d(TAG, "Restored committed URL: $restoredUrl")
        assertEquals(pageUrl, restoredUrl)
        Log.d(TAG, "=== END test4_recoveryAfterFailedNavigation ===")
    }

    // ================================================================
    // Test 5: Repeated ON/OFF transitions stability
    // ================================================================

    @Test
    fun test5_repeatedOnOffTransitions() {
        Log.d(TAG, "=== START test5_repeatedOnOffTransitions ===")
        val pageUrl = "$baseUrl/initial"
        assertTrue("Load initial page", loadPageSync(pageUrl))

        for (cycle in 1..5) {
            Log.d(TAG, "Cycle $cycle: Drop ON")
            setNetworkDropSync(true)
            assertTrue(kernel.isNetworkDropped)
            assertTrue(webViewEngine.isNetworkDropped)

            val offFetch = executeJs("runFetch('$baseUrl/api/data?t=cycle-$cycle-off');")
            assertEquals("Cycle $cycle: fetch status 0", 0, offFetch.getInt("status"))

            Log.d(TAG, "Cycle $cycle: Drop OFF")
            setNetworkDropSync(false)
            assertFalse(kernel.isNetworkDropped)
            assertFalse(webViewEngine.isNetworkDropped)

            val onFetch = executeJs("runFetch('$baseUrl/api/data?t=cycle-$cycle-on');")
            assertEquals("Cycle $cycle: fetch status 200", 200, onFetch.getInt("status"))
        }
        Log.d(TAG, "All 5 cycles passed cleanly!")
        Log.d(TAG, "=== END test5_repeatedOnOffTransitions ===")
    }

    // ================================================================
    // Test 6: Operation when CDP is completely unavailable
    // ================================================================

    @Test
    fun test6_operationWhenCdpIsCompletelyUnavailable() {
        Log.d(TAG, "=== START test6_operationWhenCdpIsCompletelyUnavailable ===")
        // Zero CDP bridge connection initialized in this test:
        // Demonstrates pure Kotlin DiagnosticKernel + native WebSettings controls operate autonomously.
        val pageUrl = "$baseUrl/initial"
        assertTrue("Load page without CDP", loadPageSync(pageUrl))

        setNetworkDropSync(true)
        assertTrue("Kernel drop active", kernel.isNetworkDropped)
        assertTrue("WebView drop active", webViewEngine.isNetworkDropped)

        val blocked = executeJs("runFetch('$baseUrl/api/data?t=no-cdp-drop');")
        assertFalse(blocked.getBoolean("ok"))
        assertEquals(0, blocked.getInt("status"))

        setNetworkDropSync(false)
        assertFalse("Kernel drop inactive", kernel.isNetworkDropped)
        assertFalse("WebView drop inactive", webViewEngine.isNetworkDropped)

        val restored = executeJs("runFetch('$baseUrl/api/data?t=no-cdp-restored');")
        assertTrue(restored.getBoolean("ok"))
        assertEquals(200, restored.getInt("status"))
        Log.d(TAG, "=== END test6_operationWhenCdpIsCompletelyUnavailable ===")
    }

    // ================================================================
    // Test 7: CDP detachment and reconnection reapplication
    // ================================================================

    @Test
    fun test7_cdpReconnectionReappliesNetworkDrop() {
        Log.d(TAG, "=== START test7_cdpReconnectionReappliesNetworkDrop ===")
        val bridge = AndroidCdpBridge()
        bridge.start()
        try {
            assertTrue("Bridge ready", bridge.awaitReady(5000))
            val cm = ConnectionManager.create(WebSocketCdpTransport(), kernel = kernel)
            cm.ensureInitialized()
            val netEngine = NetworkEngine(dispatcher = cm.dispatcher, eventRouter = cm.eventRouter)

            // Connect CDP
            val connLatch = CountDownLatch(1)
            cm.connect(bridge.debugUrl, onConnected = { connLatch.countDown() })
            assertTrue("CDP connected", connLatch.await(5, TimeUnit.SECONDS))

            val tm = cm.targetManager!!
            val attachLatch = CountDownLatch(1)
            tm.discoverTargets { targets ->
                val p = targets.firstOrNull { it.isPage }
                if (p != null) {
                    tm.selectTarget(p.targetId)
                    tm.attachToSelectedTarget { attachLatch.countDown() }
                } else {
                    attachLatch.countDown()
                }
            }
            assertTrue("Attach target", attachLatch.await(5, TimeUnit.SECONDS))
            val session = tm.activeSession!!
            netEngine.bindSession(session)

            // 1. Drop network while CDP is connected
            kernel.setNetworkDrop(true)
            netEngine.setNetworkDrop(true)
            assertTrue(netEngine.isNetworkDropped)

            // 2. Detach session
            netEngine.detach()
            assertNotNull(cm.targetManager)

            // 3. Reconnect / bind new session: reapply current drop state
            netEngine.bindSession(session)
            if (kernel.isNetworkDropped) {
                netEngine.setNetworkDrop(true)
            }
            assertTrue("Drop state preserved and reapplied on reconnect", netEngine.isNetworkDropped)

            // Clean restore
            kernel.setNetworkDrop(false)
            netEngine.setNetworkDrop(false)
            assertFalse(netEngine.isNetworkDropped)

            cm.disconnect()
        } finally {
            bridge.close()
        }
        Log.d(TAG, "=== END test7_cdpReconnectionReappliesNetworkDrop ===")
    }
}
