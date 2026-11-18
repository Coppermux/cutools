package com.coppermux.cutools

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.coppermux.cutools.core.bridge.AndroidCdpBridge
import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.model.CommandResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CuTools/CdpWsTest"

/**
 * Investigates whether CDP Network.emulateNetworkConditions(offline = true)
 * interrupts existing and new WebSocket connections in Android WebView on physical device.
 */
class CdpNetworkEmulationWebSocketInvestigationTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var wsUrl: String
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

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
        <head><title>CDP WS Test</title></head>
        <body>
        <h1>CDP WS Test Page</h1>
        <script>
        var activeWs = null;
        var wsMessages = [];
        var wsClosed = false;
        var wsCloseEvent = null;

        function connectWs(url) {
            wsClosed = false;
            wsCloseEvent = null;
            try {
                activeWs = new WebSocket(url);
                activeWs.onopen = function() {
                    console.log('WS onopen');
                    androidBridge.postResult(JSON.stringify({event: 'onopen'}));
                };
                activeWs.onmessage = function(e) {
                    console.log('WS onmessage: ' + e.data);
                    wsMessages.push(e.data);
                    androidBridge.postResult(JSON.stringify({event: 'onmessage', data: e.data}));
                };
                activeWs.onerror = function(e) {
                    console.log('WS onerror');
                    androidBridge.postResult(JSON.stringify({event: 'onerror'}));
                };
                activeWs.onclose = function(e) {
                    console.log('WS onclose: code=' + e.code + ' reason=' + e.reason);
                    wsClosed = true;
                    wsCloseEvent = {code: e.code, reason: e.reason, clean: e.wasClean};
                    androidBridge.postResult(JSON.stringify({event: 'onclose', code: e.code, reason: e.reason, clean: e.wasClean}));
                };
            } catch (err) {
                androidBridge.postResult(JSON.stringify({event: 'catch', error: err.toString()}));
            }
        }

        function sendWsMessage(msg) {
            if (!activeWs || activeWs.readyState !== WebSocket.OPEN) {
                androidBridge.postResult(JSON.stringify({status: 'not_open', readyState: activeWs ? activeWs.readyState : -1}));
                return;
            }
            try {
                activeWs.send(msg);
                androidBridge.postResult(JSON.stringify({status: 'sent', readyState: activeWs.readyState}));
            } catch (err) {
                androidBridge.postResult(JSON.stringify({status: 'error', error: err.toString()}));
            }
        }

        function getWsState() {
            androidBridge.postResult(JSON.stringify({
                hasWs: activeWs !== null,
                readyState: activeWs ? activeWs.readyState : -1,
                closed: wsClosed,
                closeEvent: wsCloseEvent
            }));
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
                    path.startsWith("/test-page") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(testHtml)
                    path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                activeServerWebSocket = webSocket
                                wsConnectedLatch.countDown()
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                wsServerMessages.add(text)
                                webSocket.send("echo:$text")
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = "http://localhost:${server.port}"
        wsUrl = "ws://localhost:${server.port}/ws"

        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            webView = WebView(context)
            WebView.setWebContentsDebuggingEnabled(true)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.webViewClient = WebViewClient()
            webView.addJavascriptInterface(JsBridge { json ->
                lastJsResult.set(json)
                resultLatch.countDown()
            }, "androidBridge")
            latch.countDown()
        }
        assertTrue("WebView init timeout", latch.await(5, TimeUnit.SECONDS))
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
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
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

    @Test
    fun testCdpNetworkEmulateConditions_webSocketBehavior() {
        Log.d(TAG, "=== START testCdpNetworkEmulateConditions_webSocketBehavior ===")
        assertTrue("Load page", loadPageSync("$baseUrl/test-page"))

        // Connect CDP bridge
        val bridge = AndroidCdpBridge()
        bridge.start()
        try {
            assertTrue("CDP bridge ready", bridge.awaitReady(5000))
            val cm = ConnectionManager.create(WebSocketCdpTransport())
            val connectLatch = CountDownLatch(1)
            cm.connect(bridge.debugUrl, onConnected = { connectLatch.countDown() })
            assertTrue("CDP connected", connectLatch.await(5, TimeUnit.SECONDS))

            val tm = cm.targetManager!!
            val attachLatch = CountDownLatch(1)
            tm.discoverTargets { targets ->
                val page = targets.firstOrNull { it.isPage }
                if (page != null) {
                    tm.selectTarget(page.targetId)
                    tm.attachToSelectedTarget { attachLatch.countDown() }
                } else {
                    attachLatch.countDown()
                }
            }
            assertTrue("Target attached", attachLatch.await(5, TimeUnit.SECONDS))
            val sessionId = tm.activeSession!!.sessionId

            // Enable Network domain
            val netEnableLatch = CountDownLatch(1)
            cm.dispatcher.sendCommand("Network.enable", sessionId = sessionId) {
                netEnableLatch.countDown()
            }
            assertTrue("Network.enable", netEnableLatch.await(5, TimeUnit.SECONDS))

            // 1. Establish initial WebSocket
            Log.d(TAG, "Connecting initial WebSocket: $wsUrl")
            val wsOpenResult = executeJs("connectWs('$wsUrl');")
            Log.d(TAG, "Initial WS result: $wsOpenResult")
            assertTrue("WS onopen", wsConnectedLatch.await(5, TimeUnit.SECONDS))

            // Test echo
            val echoResult = executeJs("sendWsMessage('hello-before-offline');")
            Log.d(TAG, "Echo sent: $echoResult")
            // Wait for onmessage
            val msgJson = executeJs("/* wait for msg */", timeoutSec = 2).let {
                // If executeJs was already unlatched by onmessage callback:
                it
            }
            Log.d(TAG, "Message response: $msgJson")

            // 2. NOW APPLY Network.emulateNetworkConditions(offline = true) via CDP
            Log.d(TAG, "Applying Network.emulateNetworkConditions(offline = true) via CDP...")
            val offlineLatch = CountDownLatch(1)
            val offlineResult = AtomicReference<CommandResult?>()
            cm.dispatcher.sendCommand(
                method = "Network.emulateNetworkConditions",
                params = mapOf(
                    "offline" to true,
                    "latency" to 0,
                    "downloadThroughput" to -1,
                    "uploadThroughput" to -1,
                ),
                sessionId = sessionId,
            ) { result ->
                offlineResult.set(result)
                offlineLatch.countDown()
            }
            assertTrue("emulateNetworkConditions latch", offlineLatch.await(5, TimeUnit.SECONDS))
            Log.d(TAG, "Network.emulateNetworkConditions response: ${offlineResult.get()}")
            assertTrue("emulateNetworkConditions succeeded", offlineResult.get() is CommandResult.Success)

            // Let network loop run a bit to see if existing WebSocket drops
            Thread.sleep(1500)

            // Check existing WebSocket state
            val wsStateUnderOffline = executeJs("getWsState();")
            Log.d(TAG, "Existing WebSocket state under CDP offline=true: $wsStateUnderOffline")
            val readyState = wsStateUnderOffline.getInt("readyState")
            Log.d(TAG, "Existing WS readyState (1=OPEN, 2=CLOSING, 3=CLOSED): $readyState")

            // Try sending message over existing WebSocket under CDP offline=true
            resultLatch = CountDownLatch(1)
            lastJsResult.set(null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView.evaluateJavascript("sendWsMessage('msg-during-cdp-offline');", null)
            }
            val sendWait = resultLatch.await(3, TimeUnit.SECONDS)
            Log.d(TAG, "Send during CDP offline resultLatch: $sendWait, raw: ${lastJsResult.get()}")

            // 3. Try to establish a NEW WebSocket while CDP offline=true is active
            Log.d(TAG, "Attempting NEW WebSocket under CDP offline=true...")
            resultLatch = CountDownLatch(1)
            lastJsResult.set(null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView.evaluateJavascript("connectWs('$wsUrl?attempt=new-under-offline');", null)
            }
            val newWsWait = resultLatch.await(4, TimeUnit.SECONDS)
            Log.d(TAG, "New WS connection attempt under CDP offline=true resultLatch: $newWsWait, raw: ${lastJsResult.get()}")

            // 4. NOW RESTORE Network.emulateNetworkConditions(offline = false)
            Log.d(TAG, "Restoring Network.emulateNetworkConditions(offline = false)...")
            val restoreLatch = CountDownLatch(1)
            cm.dispatcher.sendCommand(
                method = "Network.emulateNetworkConditions",
                params = mapOf(
                    "offline" to false,
                    "latency" to 0,
                    "downloadThroughput" to -1,
                    "uploadThroughput" to -1,
                ),
                sessionId = sessionId,
            ) { restoreLatch.countDown() }
            assertTrue("Restore latch", restoreLatch.await(5, TimeUnit.SECONDS))

            cm.disconnect()
        } finally {
            bridge.close()
        }
        Log.d(TAG, "=== END testCdpNetworkEmulateConditions_webSocketBehavior ===")
    }
}
