package com.coppermux.cutools

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.connection.ConnectionManager
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

private const val TAG = "CuTools/NetworkTest"

/**
 * Controlled physical-device investigation tests for native Android WebView network controls:
 * - WebSettings.setBlockNetworkLoads(true/false)
 * - WebView.setNetworkAvailable(false/true)
 */
class NativeNetworkControlInvestigationTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var wsUrl: String
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    data class ReceivedErrorInfo(
        val errorCode: Int,
        val description: String,
        val failingUrl: String,
        val isForMainFrame: Boolean,
    )

    private val errorEvents = CopyOnWriteArrayList<ReceivedErrorInfo>()
    private val pageFinishedEvents = CopyOnWriteArrayList<String>()
    private val lastJsResult = AtomicReference<String?>(null)
    private var resultLatch = CountDownLatch(1)

    private val wsServerMessages = CopyOnWriteArrayList<String>()
    private var activeServerWebSocket: WebSocket? = null
    private val wsConnectedLatch = CountDownLatch(1)

    class JsBridge(private val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun postResult(jsonStr: String) {
            Log.d(TAG, "JS Bridge received: $jsonStr")
            onResult(jsonStr)
        }
    }

    private val testHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>Native Network Control Test</title></head>
        <body>
        <h1>Native Network Control Test Page</h1>
        <div id="status">Ready</div>
        <script>
        var offlineEvents = [];
        var onlineEvents = [];
        window.addEventListener('offline', function(e) {
            offlineEvents.push(Date.now());
            console.log('JS Window Event: offline');
        });
        window.addEventListener('online', function(e) {
            onlineEvents.push(Date.now());
            console.log('JS Window Event: online');
        });

        function getNetworkStatus() {
            var res = {
                onLine: navigator.onLine,
                offlineCount: offlineEvents.length,
                onlineCount: onlineEvents.length
            };
            window.androidBridge.postResult(JSON.stringify(res));
        }

        function triggerFetch(url) {
            var startTime = Date.now();
            fetch(url)
                .then(function(res) {
                    res.text().then(function(body) {
                        window.androidBridge.postResult(JSON.stringify({
                            status: res.status,
                            statusText: res.statusText,
                            ok: res.ok,
                            body: body,
                            elapsedMs: Date.now() - startTime,
                            error: null
                        }));
                    }).catch(function(readErr) {
                        window.androidBridge.postResult(JSON.stringify({
                            status: res.status,
                            statusText: res.statusText,
                            ok: false,
                            body: null,
                            elapsedMs: Date.now() - startTime,
                            error: 'read_error: ' + readErr.name + ': ' + readErr.message
                        }));
                    });
                })
                .catch(function(err) {
                    window.androidBridge.postResult(JSON.stringify({
                        status: 0,
                        statusText: "",
                        ok: false,
                        body: null,
                        elapsedMs: Date.now() - startTime,
                        error: err.name + ': ' + err.message
                    }));
                });
        }

        var activeWs = null;
        var wsEvents = [];

        function connectWs(url) {
            try {
                wsEvents = [];
                activeWs = new WebSocket(url);
                activeWs.onopen = function(e) {
                    wsEvents.push('open');
                    window.androidBridge.postResult(JSON.stringify({
                        event: 'onopen',
                        readyState: activeWs.readyState
                    }));
                };
                activeWs.onmessage = function(e) {
                    wsEvents.push('message:' + e.data);
                    window.androidBridge.postResult(JSON.stringify({
                        event: 'onmessage',
                        data: e.data,
                        readyState: activeWs.readyState
                    }));
                };
                activeWs.onerror = function(e) {
                    wsEvents.push('error');
                    window.androidBridge.postResult(JSON.stringify({
                        event: 'onerror',
                        readyState: activeWs.readyState
                    }));
                };
                activeWs.onclose = function(e) {
                    wsEvents.push('close:' + e.code + ':' + e.reason);
                    window.androidBridge.postResult(JSON.stringify({
                        event: 'onclose',
                        code: e.code,
                        reason: e.reason,
                        wasClean: e.wasClean,
                        readyState: activeWs.readyState
                    }));
                };
            } catch (ex) {
                window.androidBridge.postResult(JSON.stringify({
                    event: 'exception',
                    error: ex.name + ': ' + ex.message
                }));
            }
        }

        function sendWs(data) {
            try {
                if (!activeWs || activeWs.readyState !== WebSocket.OPEN) {
                    window.androidBridge.postResult(JSON.stringify({
                        sent: false,
                        readyState: activeWs ? activeWs.readyState : -1,
                        error: 'WebSocket not open'
                    }));
                    return;
                }
                activeWs.send(data);
                window.androidBridge.postResult(JSON.stringify({
                    sent: true,
                    readyState: activeWs.readyState
                }));
            } catch (ex) {
                window.androidBridge.postResult(JSON.stringify({
                    sent: false,
                    error: ex.name + ': ' + ex.message
                }));
            }
        }

        function closeWs() {
            if (activeWs) {
                activeWs.close();
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
                Log.d(TAG, "MockWebServer received ${request.method} $path")
                return when {
                    path.startsWith("/test-page") -> {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "text/html; charset=UTF-8")
                            .setBody(testHtml)
                    }
                    path.startsWith("/api/data") -> {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"status": "ok", "path": "$path"}""")
                    }
                    path.startsWith("/api/redirect") -> {
                        MockResponse().setResponseCode(302)
                            .setHeader("Location", "$baseUrl/api/data?redirected=true")
                    }
                    path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                activeServerWebSocket = webSocket
                                Log.d(TAG, "Server WebSocket opened")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                wsServerMessages.add(text)
                                Log.d(TAG, "Server WebSocket received: $text")
                                webSocket.send("echo:$text")
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                Log.d(TAG, "Server WebSocket failure: ${t.message}")
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(code, reason)
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404).setBody("Not Found")
                }
            }
        }
        server.start()
        val host = server.hostName
        val port = server.port
        baseUrl = "http://$host:$port"
        wsUrl = "ws://$host:$port/ws"

        val latch = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            webView = WebView(context)
            WebView.setWebContentsDebuggingEnabled(true)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: -999 else -999
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() ?: "" else ""
                    val failingUrl = request?.url?.toString() ?: ""
                    val isMain = request?.isForMainFrame ?: false
                    Log.d(TAG, "onReceivedError: code=$code desc='$desc' url=$failingUrl mainFrame=$isMain")
                    errorEvents.add(ReceivedErrorInfo(code, desc, failingUrl, isMain))
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    Log.d(TAG, "legacy onReceivedError: code=$errorCode desc='$description' url=$failingUrl")
                    errorEvents.add(ReceivedErrorInfo(errorCode, description ?: "", failingUrl ?: "", false))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "onPageFinished: $url")
                    if (url != null) {
                        pageFinishedEvents.add(url)
                    }
                }
            }

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
        try {
            server.shutdown()
        } catch (_: Throwable) {}
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                webView.destroy()
            } catch (_: Throwable) {}
        }
    }

    private fun loadPageSync(url: String): Boolean {
        val pageLatch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: -999 else -999
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() ?: "" else ""
                    val failingUrl = request?.url?.toString() ?: ""
                    val isMain = request?.isForMainFrame ?: false
                    Log.d(TAG, "loadPageSync onReceivedError: code=$code desc='$desc' url=$failingUrl mainFrame=$isMain")
                    errorEvents.add(ReceivedErrorInfo(code, desc, failingUrl, isMain))
                    pageLatch.countDown()
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    Log.d(TAG, "loadPageSync onPageFinished: $finishedUrl")
                    if (finishedUrl != null) pageFinishedEvents.add(finishedUrl)
                    pageLatch.countDown()
                }
            }
            webView.loadUrl(url)
        }
        return pageLatch.await(10, TimeUnit.SECONDS)
    }

    private fun executeJs(script: String): JSONObject {
        resultLatch = CountDownLatch(1)
        lastJsResult.set(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script, null)
        }
        assertTrue("JS result timeout for: $script", resultLatch.await(8, TimeUnit.SECONDS))
        val raw = lastJsResult.get()
        assertNotNull("Bridge result must not be null", raw)
        return JSONObject(raw!!)
    }

    private fun setBlockNetworkLoads(blocked: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.settings.blockNetworkLoads = blocked
            Log.d(TAG, "Applied settings.blockNetworkLoads = $blocked (effective: ${webView.settings.blockNetworkLoads})")
        }
    }

    private fun setNetworkAvailable(available: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.setNetworkAvailable(available)
            Log.d(TAG, "Called webView.setNetworkAvailable($available)")
        }
    }

    // ================================================================
    // Test 1: WebSettings.setBlockNetworkLoads(true/false) detailed behavior
    // ================================================================

    @Test
    fun testBlockNetworkLoads_behaviorOnAndroid11() {
        Log.d(TAG, "=== START testBlockNetworkLoads_behaviorOnAndroid11 ===")
        assertTrue("Load initial page", loadPageSync("$baseUrl/test-page"))

        // Baseline: fetch works, navigator.onLine is true
        val baseFetch = executeJs("triggerFetch('$baseUrl/api/data?test=base');")
        assertEquals("Baseline fetch should succeed", 200, baseFetch.getInt("status"))
        assertTrue(baseFetch.getBoolean("ok"))

        val baseStatus = executeJs("getNetworkStatus();")
        assertTrue("navigator.onLine should be true initially", baseStatus.getBoolean("onLine"))

        // --- ACTIVATE setBlockNetworkLoads(true) ---
        setBlockNetworkLoads(true)

        // 1. JS fetch when blockNetworkLoads = true
        val blockedFetch = executeJs("triggerFetch('$baseUrl/api/data?test=blocked');")
        Log.d(TAG, "Blocked fetch result: $blockedFetch")
        // What does fetch do? Does it fail immediately or time out?
        assertEquals("Blocked fetch status should be 0", 0, blockedFetch.getInt("status"))
        assertFalse("Blocked fetch ok should be false", blockedFetch.getBoolean("ok"))
        val fetchError = blockedFetch.getString("error")
        Log.d(TAG, "Blocked fetch error: $fetchError")
        assertTrue("Fetch error should mention Failed to fetch or TypeError", fetchError.contains("Failed to fetch") || fetchError.contains("TypeError"))

        // 2. Check navigator.onLine when blockNetworkLoads = true
        val statusWhileBlocked = executeJs("getNetworkStatus();")
        Log.d(TAG, "Status while blockNetworkLoads=true: $statusWhileBlocked")
        // Note: Does blockNetworkLoads change navigator.onLine? Let's check!
        val onLineUnderBlockLoads = statusWhileBlocked.getBoolean("onLine")
        Log.d(TAG, "navigator.onLine under blockNetworkLoads=true is: $onLineUnderBlockLoads")

        // 3. New WebSocket connection when blockNetworkLoads = true
        val wsResult = executeJs("connectWs('$wsUrl');")
        Log.d(TAG, "WS connect under blockNetworkLoads=true: $wsResult")
        val wsEvent = wsResult.getString("event")
        Log.d(TAG, "WS event under blockNetworkLoads=true: $wsEvent")

        // 4. Navigation when blockNetworkLoads = true
        errorEvents.clear()
        val navLatch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.loadUrl("$baseUrl/api/data?test=nav-blocked")
        }
        Thread.sleep(1000)
        Log.d(TAG, "Recorded error events during nav under blockNetworkLoads: $errorEvents")
        if (errorEvents.isNotEmpty()) {
            val lastErr = errorEvents.last()
            Log.d(TAG, "Nav error code: ${lastErr.errorCode}, description: '${lastErr.description}'")
        }

        // --- RESTORE setBlockNetworkLoads(false) ---
        setBlockNetworkLoads(false)
        loadPageSync("$baseUrl/test-page")

        // 5. Fetch after restoration
        val restoredFetch = executeJs("triggerFetch('$baseUrl/api/data?test=restored');")
        Log.d(TAG, "Restored fetch result: $restoredFetch")
        assertEquals(200, restoredFetch.getInt("status"))
        assertTrue(restoredFetch.getBoolean("ok"))
        Log.d(TAG, "=== END testBlockNetworkLoads_behaviorOnAndroid11 ===")
    }

    // ================================================================
    // Test 2: WebView.setNetworkAvailable(false/true) detailed behavior
    // ================================================================

    @Test
    fun testSetNetworkAvailable_behaviorOnAndroid11() {
        Log.d(TAG, "=== START testSetNetworkAvailable_behaviorOnAndroid11 ===")
        assertTrue("Load initial page", loadPageSync("$baseUrl/test-page"))

        // Baseline
        val baseStatus = executeJs("getNetworkStatus();")
        assertTrue("Initial navigator.onLine", baseStatus.getBoolean("onLine"))

        // --- SET setNetworkAvailable(false) ---
        setNetworkAvailable(false)
        Thread.sleep(500) // Allow Blink network state event to propagate

        // 1. Check navigator.onLine
        val offlineStatus = executeJs("getNetworkStatus();")
        Log.d(TAG, "Status after setNetworkAvailable(false): $offlineStatus")
        val onLineAfterSetAvailableFalse = offlineStatus.getBoolean("onLine")
        Log.d(TAG, "navigator.onLine after setNetworkAvailable(false): $onLineAfterSetAvailableFalse")
        val offlineEventCount = offlineStatus.getInt("offlineCount")
        Log.d(TAG, "offline event fired count: $offlineEventCount")

        // 2. Does JS fetch work or get blocked when ONLY setNetworkAvailable(false) is called?
        val fetchUnderNetworkAvailableFalse = executeJs("triggerFetch('$baseUrl/api/data?test=net-avail-false');")
        Log.d(TAG, "Fetch under setNetworkAvailable(false): $fetchUnderNetworkAvailableFalse")

        // 3. Does WebSocket work when ONLY setNetworkAvailable(false) is called?
        val wsResult = executeJs("connectWs('$wsUrl');")
        Log.d(TAG, "WS connect under setNetworkAvailable(false): $wsResult")

        // --- RESTORE setNetworkAvailable(true) ---
        setNetworkAvailable(true)
        Thread.sleep(500)

        val restoredStatus = executeJs("getNetworkStatus();")
        Log.d(TAG, "Status after setNetworkAvailable(true): $restoredStatus")
        assertTrue("navigator.onLine restored", restoredStatus.getBoolean("onLine"))
        val onlineEventCount = restoredStatus.getInt("onlineCount")
        Log.d(TAG, "online event fired count: $onlineEventCount")
        Log.d(TAG, "=== END testSetNetworkAvailable_behaviorOnAndroid11 ===")
    }

    // ================================================================
    // Test 3: Existing WebSocket behavior during block
    // ================================================================

    @Test
    fun testExistingWebSocket_duringNetworkControl() {
        Log.d(TAG, "=== START testExistingWebSocket_duringNetworkControl ===")
        assertTrue("Load initial page", loadPageSync("$baseUrl/test-page"))

        // 1. Open WebSocket cleanly
        val openResult = executeJs("connectWs('$wsUrl');")
        Log.d(TAG, "Initial WS open result: $openResult")
        assertEquals("onopen", openResult.getString("event"))

        // Verify round-trip echo
        val echo1 = executeJs("sendWs('hello-1');")
        Log.d(TAG, "Echo 1 send result: $echo1")
        // Wait for onmessage bridge call
        assertTrue("sendWs ok", echo1.getBoolean("sent"))

        // 2. Now apply setBlockNetworkLoads(true)
        Log.d(TAG, "Applying setBlockNetworkLoads(true) on existing WebSocket...")
        setBlockNetworkLoads(true)

        // Try sending a message on the existing WebSocket
        val echoAfterBlock = executeJs("sendWs('hello-blocked');")
        Log.d(TAG, "sendWs after setBlockNetworkLoads(true): $echoAfterBlock")

        // Did server receive 'hello-blocked'? Let's check server messages
        Thread.sleep(500)
        Log.d(TAG, "Server received messages: $wsServerMessages")

        // 3. Now apply setNetworkAvailable(false)
        Log.d(TAG, "Applying setNetworkAvailable(false)...")
        setNetworkAvailable(false)
        Thread.sleep(500)

        val echoAfterNetAvail = executeJs("sendWs('hello-netavail-false');")
        Log.d(TAG, "sendWs after setNetworkAvailable(false): $echoAfterNetAvail")
        Thread.sleep(500)
        Log.d(TAG, "Server received messages after netavail false: $wsServerMessages")

        // Restore
        setBlockNetworkLoads(false)
        setNetworkAvailable(true)
        Log.d(TAG, "=== END testExistingWebSocket_duringNetworkControl ===")
    }

    // ================================================================
    // Test 3b: New WebSocket under Combined Controls
    // ================================================================

    @Test
    fun testNewWebSocket_underCombinedControls() {
        Log.d(TAG, "=== START testNewWebSocket_underCombinedControls ===")
        assertTrue("Load initial page", loadPageSync("$baseUrl/test-page"))

        // Set combined offline
        setBlockNetworkLoads(true)
        setNetworkAvailable(false)
        Thread.sleep(300)

        // Attempt new WebSocket connection
        val wsResult = executeJs("connectWs('$wsUrl');")
        Log.d(TAG, "New WS connect under combined offline: $wsResult")

        // Restore
        setBlockNetworkLoads(false)
        setNetworkAvailable(true)
        Log.d(TAG, "=== END testNewWebSocket_underCombinedControls ===")
    }

    // ================================================================
    // Test 4: Combined controls, Navigation, Pull-to-Refresh & Error Codes
    // ================================================================

    @Test
    fun testCombinedControls_navigationAndPullToRefresh() {
        Log.d(TAG, "=== START testCombinedControls_navigationAndPullToRefresh ===")
        val pageUrl = "$baseUrl/test-page"
        assertTrue("Load initial page", loadPageSync(pageUrl))

        // Get committed URL
        val committedUrl = AtomicReference<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            committedUrl.set(webView.url)
        }
        Log.d(TAG, "Initial committed webView.url: ${committedUrl.get()}")
        assertEquals(pageUrl, committedUrl.get())

        // Combine both APIs: Drop Connection simulation
        Log.d(TAG, "Engaging combined offline simulation (blockNetworkLoads=true, setNetworkAvailable=false)...")
        setBlockNetworkLoads(true)
        setNetworkAvailable(false)

        // 1. Test fetch request while combined offline
        val blockedFetch = executeJs("triggerFetch('$baseUrl/api/data?test=combined');")
        Log.d(TAG, "Combined blocked fetch: $blockedFetch")
        assertEquals(0, blockedFetch.getInt("status"))

        // 2. Test Pull-to-Refresh / Reload while offline
        errorEvents.clear()
        val reloadLatch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: -999 else -999
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() ?: "" else ""
                    val failingUrl = request?.url?.toString() ?: ""
                    val isMain = request?.isForMainFrame ?: false
                    Log.d(TAG, "Reload onReceivedError: code=$code desc='$desc' url=$failingUrl isMain=$isMain")
                    errorEvents.add(ReceivedErrorInfo(code, desc, failingUrl, isMain))
                    reloadLatch.countDown()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "Reload onPageFinished: $url")
                    reloadLatch.countDown()
                }
            }
            webView.reload()
        }
        assertTrue("Reload latch timed out", reloadLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        // Inspect URL after failed reload
        val urlAfterFailedReload = AtomicReference<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            urlAfterFailedReload.set(webView.url)
        }
        Log.d(TAG, "webView.url after failed reload: ${urlAfterFailedReload.get()}")
        Log.d(TAG, "Recorded error events on reload: $errorEvents")

        // 3. Test HTTP Redirect while combined offline
        errorEvents.clear()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.loadUrl("$baseUrl/api/redirect")
        }
        Thread.sleep(1500)
        Log.d(TAG, "Recorded error events on redirect attempt: $errorEvents")
        val urlAfterFailedNav = AtomicReference<String?>()
        val historyIndex = AtomicInteger()
        val historyItemUrl = AtomicReference<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            urlAfterFailedNav.set(webView.url)
            val list = webView.copyBackForwardList()
            historyIndex.set(list.currentIndex)
            historyItemUrl.set(list.currentItem?.url)
        }
        Log.d(TAG, "urlAfterFailedNav: ${urlAfterFailedNav.get()}, historyIndex: ${historyIndex.get()}, historyItemUrl: ${historyItemUrl.get()}")

        // 4. RESTORE connectivity
        Log.d(TAG, "Restoring connectivity (blockNetworkLoads=false, setNetworkAvailable=true)...")
        setBlockNetworkLoads(false)
        setNetworkAvailable(true)

        // Can we reload or re-navigate to the original committed URL without recreating WebView?
        assertTrue("Reload preserved URL after restoration", loadPageSync(pageUrl))

        val urlAfterRestore = AtomicReference<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            urlAfterRestore.set(webView.url)
        }
        Log.d(TAG, "webView.url after restoration: ${urlAfterRestore.get()}")
        assertEquals(pageUrl, urlAfterRestore.get())

        // Fetch works again immediately
        val postRestoreFetch = executeJs("triggerFetch('$baseUrl/api/data?test=post-restore');")
        Log.d(TAG, "Post-restore fetch: $postRestoreFetch")
        assertEquals(200, postRestoreFetch.getInt("status"))
        Log.d(TAG, "=== END testCombinedControls_navigationAndPullToRefresh ===")
    }

    // ================================================================
    // Test 5: Repeated ON/OFF cycling stability
    // ================================================================

    @Test
    fun testRepeatedOnOffTransitions() {
        Log.d(TAG, "=== START testRepeatedOnOffTransitions ===")
        val pageUrl = "$baseUrl/test-page"
        assertTrue("Load initial page", loadPageSync(pageUrl))

        for (cycle in 1..5) {
            Log.d(TAG, "--- Cycle $cycle: Turning OFF ---")
            setBlockNetworkLoads(true)
            setNetworkAvailable(false)

            val offStatus = executeJs("getNetworkStatus();")
            assertFalse("Cycle $cycle: navigator.onLine should be false", offStatus.getBoolean("onLine"))

            val offFetch = executeJs("triggerFetch('$baseUrl/api/data?test=cycle-$cycle-off');")
            assertEquals("Cycle $cycle: fetch should fail", 0, offFetch.getInt("status"))

            Log.d(TAG, "--- Cycle $cycle: Turning ON ---")
            setBlockNetworkLoads(false)
            setNetworkAvailable(true)

            val onStatus = executeJs("getNetworkStatus();")
            assertTrue("Cycle $cycle: navigator.onLine should be true", onStatus.getBoolean("onLine"))

            val onFetch = executeJs("triggerFetch('$baseUrl/api/data?test=cycle-$cycle-on');")
            assertEquals("Cycle $cycle: fetch should succeed", 200, onFetch.getInt("status"))
        }
        Log.d(TAG, "All 5 cycles completed successfully!")
        Log.d(TAG, "=== END testRepeatedOnOffTransitions ===")
    }

    // ================================================================
    // Test 6: CDP Independence Verification
    // ================================================================

    @Test
    fun testCdpIndependenceDuringNetworkBlock() {
        Log.d(TAG, "=== START testCdpIndependenceDuringNetworkBlock ===")
        val pageUrl = "$baseUrl/test-page"
        assertTrue("Load initial page", loadPageSync(pageUrl))

        // Set native network block
        setBlockNetworkLoads(true)
        setNetworkAvailable(false)

        // Connect CDP bridge while network is blocked
        val bridge = AndroidCdpBridge()
        bridge.start()
        try {
            val bridgeReady = bridge.awaitReady(5000)
            Log.d(TAG, "CDP bridge ready while WebView network blocked: $bridgeReady")
            assertTrue("CDP Bridge must start even if WebView network is blocked", bridgeReady)

            val cm = ConnectionManager.create(WebSocketCdpTransport())
            val connectLatch = CountDownLatch(1)
            cm.connect(bridge.debugUrl, onConnected = { connectLatch.countDown() })
            val connected = connectLatch.await(5, TimeUnit.SECONDS)
            Log.d(TAG, "CDP ConnectionManager connected: $connected")
            assertTrue("CDP ConnectionManager must connect successfully via localhost/abstract socket", connected)

            val tm = cm.targetManager
            assertNotNull("TargetManager should be available", tm)
            val attachLatch = CountDownLatch(1)
            val attachedPage = AtomicBoolean(false)
            tm?.discoverTargets { targets ->
                val page = targets.firstOrNull { it.isPage }
                if (page != null) {
                    tm.selectTarget(page.targetId)
                    tm.attachToSelectedTarget {
                        attachedPage.set(true)
                        attachLatch.countDown()
                    }
                } else {
                    attachLatch.countDown()
                }
            }
            assertTrue("Target attachment latch", attachLatch.await(5, TimeUnit.SECONDS))
            assertTrue("CDP must attach to page target while native network is blocked", attachedPage.get())

            val session = tm?.activeSession
            assertNotNull("Active session must not be null", session)

            // Test evaluating JS expression through CDP while native network is blocked
            val evalLatch = CountDownLatch(1)
            val evalResult = AtomicReference<String?>()
            cm.dispatcher.sendCommand(
                method = "Runtime.evaluate",
                params = mapOf("expression" to "navigator.onLine"),
                sessionId = session?.sessionId
            ) { result ->
                when (result) {
                    is com.coppermux.cutools.core.model.CommandResult.Success -> {
                        evalResult.set(result.response.result?.toString())
                        evalLatch.countDown()
                    }
                    is com.coppermux.cutools.core.model.CommandResult.Failure -> {
                        evalResult.set("failure: ${result.error}")
                        evalLatch.countDown()
                    }
                }
            }
            assertTrue("CDP evaluate latch", evalLatch.await(5, TimeUnit.SECONDS))
            Log.d(TAG, "CDP evaluate navigator.onLine result: ${evalResult.get()}")
            assertNotNull("CDP evaluation succeeded", evalResult.get())
            assertTrue("CDP evaluation returned navigator.onLine = false", evalResult.get()!!.contains("false"))

            cm.disconnect()
        } finally {
            bridge.close()
            setBlockNetworkLoads(false)
            setNetworkAvailable(true)
        }
        Log.d(TAG, "=== END testCdpIndependenceDuringNetworkBlock ===")
    }
}
