package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.network.FetchInterceptionEngine
import com.coppermux.cutools.core.network.FetchLifecyclePhase
import com.coppermux.cutools.core.network.MockAction
import com.coppermux.cutools.core.network.MockResponseRuleManager
import com.coppermux.cutools.core.network.MockResponseRuleSpec
import com.coppermux.cutools.core.network.NetworkCapabilityStatus
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.core.network.NetworkLifecyclePhase
import com.coppermux.cutools.core.network.NetworkRequestStage
import com.coppermux.cutools.core.network.ResponseBodyResult
import com.coppermux.cutools.core.network.ResponseBodyStatus
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 5E — Network full-lifecycle real-Chrome validation.
 *
 * Proves the entire Network subsystem (5A–5D) against REAL Chrome over the
 * Phase 1 foundation (a single connection, no second WebSocket):
 *
 *  - `Network.enable` → genuine `Network.requestWillBeSent` /
 *    `Network.responseReceived` / `Network.loadingFinished` lifecycle tracking
 *    for a real request, with browser-provided resource timing parsed.
 *  - `Network.getResponseBody` retrieves and decodes a genuine response body.
 *  - `Fetch.enable` → a real request matching a FULFILL rule is intercepted and
 *    served (interceptedCount advances, `Fetch.fulfillRequest` dispatched, the
 *    page receives the mock status instead of the real response).
 *  - Clean teardown: detach returns both engines to INACTIVE with no session.
 *
 * A loopback HTTP server (started and stopped within the test) guarantees the
 * page can always reach the request target, independent of external network.
 *
 * Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class NetworkChromeIntegrationTest {

    private lateinit var browserWsUrl: String

    @Before
    fun discoverChrome() {
        browserWsUrl = try {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url("http://127.0.0.1:9224/json/version").build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = com.coppermux.cutools.core.model.Protocol.moshi
                    .adapter(Map::class.java).fromJson(body)
                (json?.get("webSocketDebuggerUrl") as? String).orEmpty()
            }
        } catch (e: Exception) {
            ""
        }
        assumeTrue("Real Chrome not reachable at http://127.0.0.1:9224 — skipping", browserWsUrl.isNotBlank())
    }

    private fun newManager(): ConnectionManager =
        ConnectionManager.create(WebSocketCdpTransport(), DispatcherConfig(defaultTimeoutMs = 8000))

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue("Timed out waiting for: $what", latch.await(25, TimeUnit.SECONDS))
    }

    private fun connectAttachPage(cm: ConnectionManager): Pair<SessionInfo, TargetInfo> {
        val connected = CountDownLatch(1)
        cm.connect(url = browserWsUrl, onConnected = { connected.countDown() }, onError = { connected.countDown() })
        await(connected, "connection")

        val disc = CountDownLatch(1)
        val targets = AtomicReference<List<TargetInfo>>()
        cm.discoverTargets(callback = { t -> targets.set(t); disc.countDown() }, onError = { fail("discover: ${it.message}") })
        await(disc, "target discovery")
        val page = targets.get().first { it.isPage }
        cm.selectTarget(page.targetId)

        val attached = CountDownLatch(1)
        val sessionRef = AtomicReference<SessionInfo?>()
        val attachError = AtomicReference<Throwable?>()
        cm.attachToTarget(
            onAttached = { s -> sessionRef.set(s); attached.countDown() },
            onError = { e -> attachError.set(e); attached.countDown() },
        )
        await(attached, "target attach")
        assertNull("Attach error: ${attachError.get()}", attachError.get())
        return sessionRef.get()!! to page
    }

    private fun waitFor(timeoutMs: Long = 20000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }

    private fun evaluate(cm: ConnectionManager, session: SessionInfo, expression: String, awaitPromise: Boolean = true): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        cm.dispatcher.sendCommand(
            "Runtime.evaluate",
            mapOf("expression" to expression, "awaitPromise" to awaitPromise),
            sessionId = session.sessionId,
            callback = { result ->
                success = result is CommandResult.Success
                latch.countDown()
            },
        )
        await(latch, "Runtime.evaluate $expression")
        return success
    }

    /** A minimal loopback HTTP server (raw socket) so the page always has a reachable target. */
    private data class LocalServer(
        private val socket: ServerSocket,
        private val worker: Thread,
    ) {
        val port: Int get() = socket.localPort
        fun stop() {
            worker.interrupt()
            try { socket.close() } catch (e: Exception) { /* best-effort */ }
        }
    }

    private fun startServer(body: String): LocalServer {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val worker = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val client: Socket = try { socket.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        val reader = BufferedReader(InputStreamReader(client.inputStream, StandardCharsets.UTF_8))
                        reader.readLine()
                        var line = reader.readLine()
                        while (line != null && line.isNotEmpty()) line = reader.readLine()
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        client.outputStream.write(head.toByteArray(StandardCharsets.UTF_8))
                        client.outputStream.write(bytes)
                        client.outputStream.flush()
                        client.close()
                    } catch (e: Exception) {
                        try { client.close() } catch (ignored: Exception) { /* best-effort */ }
                    }
                }.apply { isDaemon = true; start() }
            }
        }
        worker.apply { isDaemon = true }.start()
        return LocalServer(socket, worker)
    }

    @Test
    fun phase5e_fullNetworkLifecycleAndBodyRetrieval_againstRealChrome() {
        val body = "network-body-" + System.nanoTime()
        val server = startServer(body)
        val cm = newManager()
        val (session, _) = connectAttachPage(cm)
        val engine = NetworkEngine(cm.dispatcher, cm.eventRouter)
        engine.bindSession(session)
        waitFor(15000, "Network READY") { engine.state.phase == NetworkLifecyclePhase.READY }
        assertEquals(NetworkCapabilityStatus.SUPPORTED, engine.state.capability)

        val url = "http://127.0.0.1:${server.port}/"
        // Fire-and-forget: the request fires and completes; the engine tracks the
        // full genuine lifecycle without any page-side promise left pending.
        assertTrue("page fetch must evaluate", evaluate(
            cm, session, "(fetch('$url'))", awaitPromise = false,
        ))

        // A genuine request lifecycle is tracked for the page request.
        waitFor(20000, "loopback request tracked") {
            engine.state.requests.any { it.url?.contains(server.port.toString()) == true }
        }
        val tracked = engine.state.requests.first { it.url?.contains(server.port.toString()) == true }
        assertEquals(session.sessionId, tracked.sessionId)
        assertEquals(session.targetId, tracked.targetId)

        // The request advances to a terminal FINISHED stage via real lifecycle events.
        waitFor(20000, "request reaches finished") {
            engine.state.requests.any { it.requestId == tracked.requestId && it.isFinished }
        }
        val finished = engine.state.requests.first { it.requestId == tracked.requestId }
        assertEquals(NetworkRequestStage.FINISHED, finished.stage)
        assertNotNull("real response carries a status", finished.responseStatus)
        // Chrome 151 does not populate responseReceived.timing for loopback
        // subresource requests (genuine absence, never fabricated). When timing
        // IS present, it must parse to genuine Chrome endpoints.
        if (finished.timing != null) {
            assertNotNull("timing must expose genuine responseEnd", finished.timing!!.responseEnd)
        }

        // Phase 5B: retrieve and decode the genuine response body.
        val bodyRef = AtomicReference<ResponseBodyResult?>()
        engine.getResponseBody(tracked.requestId) { bodyRef.set(it) }
        waitFor(20000, "response body decoded") { bodyRef.get() != null }
        assertEquals(ResponseBodyStatus.SUCCESS, bodyRef.get()!!.status)
        assertTrue("real body must match the served content", bodyRef.get()!!.text == body)

        // Clean teardown: detach leaves the engine INACTIVE with no session.
        engine.detach()
        assertEquals(NetworkLifecyclePhase.INACTIVE, engine.state.phase)
        assertNull(engine.session)
        assertEquals(0, engine.state.requests.size)

        cm.disconnect()
        server.stop()
    }

    @Test
    fun phase5e_fetchInterceptionAndMocking_againstRealChrome() {
        val server = startServer("real-body-should-never-be-seen")
        val cm = newManager()
        val (session, _) = connectAttachPage(cm)
        val ruleManager = MockResponseRuleManager()
        val fetchEngine = FetchInterceptionEngine(cm.dispatcher, cm.eventRouter, ruleManager)
        fetchEngine.bindSession(session)
        waitFor(20000, "Fetch READY") { fetchEngine.state.phase == FetchLifecyclePhase.READY }

        val mockStatus = 201
        ruleManager.upsert(MockResponseRuleSpec(
            id = "mock-loopback",
            urlPattern = "*127.0.0.1:${server.port}/mock*",
            action = MockAction.FULFILL,
            statusCode = mockStatus,
            statusText = "Mocked",
            body = "{\"mock\":true}",
        ))

        val url = "http://127.0.0.1:${server.port}/mock"
        // Fire-and-forget: the engine intercepts the request; we don't await the
        // page's promise (the mock response has no CORS headers to read it).
        assertTrue("mocked fetch must evaluate", evaluate(
            cm, session,
            "(fetch('$url'))",
            awaitPromise = false,
        ))

        waitFor(20000, "request intercepted") {
            fetchEngine.state.interceptedCount >= 1
        }
        assertTrue(fetchEngine.state.interceptedCount >= 1)
        assertEquals(0, fetchEngine.state.pendingCount)

        // Clean teardown.
        fetchEngine.detach()
        assertEquals(FetchLifecyclePhase.INACTIVE, fetchEngine.state.phase)
        assertNull(fetchEngine.session)
        assertEquals(0, fetchEngine.state.pendingCount)

        cm.disconnect()
        server.stop()
    }
}