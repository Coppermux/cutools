package com.coppermux.cutools.core.bridge

import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.transport.CdpTransport
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AndroidCdpBridgeTest {

    private var bridge: AndroidCdpBridge? = null

    @After
    fun tearDown() {
        bridge?.close()
        bridge = null
    }

    /**
     * In-memory full-duplex loopback stream pair simulating a local abstract socket.
     */
    private class LoopbackStreamPair(
        val serverSide: Socket,
        val clientSide: Socket,
    ) : ByteStreamPair {
        override val inputStream: InputStream = clientSide.getInputStream()
        override val outputStream: OutputStream = clientSide.getOutputStream()

        override fun close() {
            runCatching { clientSide.close() }
            runCatching { serverSide.close() }
        }
    }

    private class EchoLocalSocketConnector(
        private val customHandler: ((String, OutputStream) -> Boolean)? = null
    ) : LocalSocketConnector {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val pairs = mutableListOf<LoopbackStreamPair>()
        val connectCount = AtomicInteger(0)
        val receivedLines = CopyOnWriteArrayList<String>()
        private val executor = Executors.newCachedThreadPool()

        override fun connect(abstractSocketName: String): ByteStreamPair {
            connectCount.incrementAndGet()
            val clientSide = Socket("127.0.0.1", serverSocket.localPort)
            val serverSide = serverSocket.accept()
            clientSide.tcpNoDelay = true
            serverSide.tcpNoDelay = true
            val pair = LoopbackStreamPair(serverSide, clientSide)
            pairs.add(pair)

            // Start worker on the fake DevTools side
            executor.execute {
                try {
                    val reader = BufferedReader(InputStreamReader(serverSide.getInputStream(), Charsets.UTF_8))
                    val writer = serverSide.getOutputStream()
                    while (!serverSide.isClosed) {
                        val line = reader.readLine() ?: break
                        receivedLines.add(line)
                        if (customHandler != null && customHandler.invoke(line, writer)) {
                            continue
                        }
                        if (line.isEmpty()) {
                            continue
                        }
                        val response = "ECHO:$line\n"
                        writer.write(response.toByteArray(Charsets.UTF_8))
                        writer.flush()
                    }
                } catch (_: Exception) {
                }
            }
            return pair
        }

        fun shutdown() {
            pairs.forEach { it.close() }
            runCatching { serverSocket.close() }
            executor.shutdownNow()
        }
    }

    private fun sendRawHttpRequest(
        port: Int,
        rawRequest: String
    ): Pair<String, Socket> {
        val client = Socket("127.0.0.1", port)
        client.soTimeout = 3000
        val writer = client.getOutputStream()
        writer.write(rawRequest.toByteArray(Charsets.UTF_8))
        writer.flush()
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val firstLine = reader.readLine() ?: ""
        return firstLine to client
    }

    @Test
    fun `lifecycle - starts on ephemeral loopback port and stops cleanly`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = WebViewDevToolsSocketResolver(
            connector = fakeConnector,
            pidProvider = { 1234 },
        )
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b

        assertFalse(b.running)
        assertEquals(0, b.localPort)
        assertNull(b.authToken)

        val port1 = b.start()
        assertTrue(port1 > 0)
        assertTrue(b.running)
        assertEquals(port1, b.localPort)
        assertNotNull(b.authToken)
        assertEquals(64, b.authToken?.length)
        assertEquals("ws://127.0.0.1:$port1/devtools/browser?token=${b.authToken}", b.debugUrl)

        // Idempotent start
        val tokenBefore = b.authToken
        val port2 = b.start()
        assertEquals(port1, port2)
        assertEquals(tokenBefore, b.authToken)

        b.stop()
        assertFalse(b.running)
        assertFalse(b.ready)
        assertEquals(0, b.localPort)
        assertNull(b.authToken)
        assertEquals("ws://127.0.0.1:0/devtools/browser", b.debugUrl)

        fakeConnector.shutdown()
    }

    @Test
    fun `awaitReady - verifies and reports ready when socket is active`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_42"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b

        val isReady = b.awaitReady(timeoutMs = 1000)
        assertTrue(isReady)
        assertTrue(b.ready)
        assertEquals("webview_devtools_remote_42", b.activeDebugSocketName)

        fakeConnector.shutdown()
    }

    @Test
    fun `bidirectional forwarding - forwards client bytes to local socket and replies back`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_test"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b

        assertTrue(b.awaitReady(timeoutMs = 1000))

        val client = Socket("127.0.0.1", b.localPort)
        val writer = client.getOutputStream()
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))

        // Initial authorized request line and headers
        writer.write("GET /devtools/browser?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\nHello CDP Bridge\n".toByteArray(Charsets.UTF_8))
        writer.flush()

        val reply1 = reader.readLine()
        assertEquals("ECHO:GET /devtools/browser HTTP/1.1", reply1)
        val reply2 = reader.readLine()
        assertEquals("ECHO:Host: 127.0.0.1", reply2)
        val reply3 = reader.readLine()
        assertEquals("ECHO:Hello CDP Bridge", reply3)

        // Verify Chromium received the sanitized request without the token
        assertTrue(fakeConnector.receivedLines.any { it == "GET /devtools/browser HTTP/1.1" })
        assertFalse(fakeConnector.receivedLines.any { it.contains("token=") })

        client.close()
        fakeConnector.shutdown()
    }

    @Test
    fun `concurrency - proxies multiple concurrent client connections independently`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_multi"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b

        assertTrue(b.awaitReady(timeoutMs = 1000))

        val clientCount = 4
        val latch = CountDownLatch(clientCount)
        val successCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(clientCount)

        for (i in 1..clientCount) {
            executor.execute {
                try {
                    val client = Socket("127.0.0.1", b.localPort)
                    val writer = client.getOutputStream()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))

                    writer.write("GET /devtools/browser?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\nREQ_$i\n".toByteArray(Charsets.UTF_8))
                    writer.flush()

                    reader.readLine() // ECHO:GET ...
                    reader.readLine() // ECHO:Host ...
                    val response = reader.readLine()
                    if (response == "ECHO:REQ_$i") {
                        successCount.incrementAndGet()
                    }
                    client.close()
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(clientCount, successCount.get())

        executor.shutdownNow()
        fakeConnector.shutdown()
    }

    @Test
    fun `teardown - stop cleanly closes all active client connections`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_teardown"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b

        assertTrue(b.awaitReady(timeoutMs = 1000))

        val client = Socket("127.0.0.1", b.localPort)
        val reader = client.getInputStream()

        // Stop the bridge
        b.stop()

        // Reading from client should now hit EOF (-1) or throw SocketException
        val read = runCatching { reader.read() }.getOrDefault(-1)
        assertEquals(-1, read)

        client.close()
        fakeConnector.shutdown()
    }

    // =========================================================================
    // SEC-01 SPECIFIC AUTOMATED VERIFICATION TESTS
    // =========================================================================

    @Test
    fun `SEC-01 - 1 Authorized HTTP discovery succeeds with URI token and X-CuTools-Token header`() {
        val fakeConnector = EchoLocalSocketConnector { line, writer ->
            if (line == "GET /json/version HTTP/1.1") {
                val json = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 26\r\n\r\n{\"Browser\":\"Chrome/118\"}"
                writer.write(json.toByteArray(Charsets.UTF_8))
                writer.flush()
                true
            } else false
        }
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        // 1a. Via query parameter ?token=
        val (resp1, sock1) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sock1.close()
        assertEquals("HTTP/1.1 200 OK", resp1)

        // 1b. Via HTTP header X-CuTools-Token
        val (resp2, sock2) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version HTTP/1.1\r\nHost: 127.0.0.1\r\nX-CuTools-Token: ${b.authToken}\r\n\r\n"
        )
        sock2.close()
        assertEquals("HTTP/1.1 200 OK", resp2)

        // Verify Chromium debugging socket was accessed authorized
        assertEquals(2, fakeConnector.connectCount.get())
        // Verify credentials stripped: Chromium never received token query or X-CuTools-Token header
        assertFalse(fakeConnector.receivedLines.any { it.contains("token=") })
        assertFalse(fakeConnector.receivedLines.any { it.contains("X-CuTools-Token", ignoreCase = true) })

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 2 Unauthorized HTTP discovery fails`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        val (resp, sock) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sock.close()

        assertEquals("HTTP/1.1 401 Unauthorized", resp)
        assertEquals(0, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 3 Authorized WebSocket connections succeed`() {
        val fakeConnector = EchoLocalSocketConnector { line, writer ->
            if (line.startsWith("GET /devtools/browser HTTP/1.1")) {
                val upgradeResp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
                writer.write(upgradeResp.toByteArray(Charsets.UTF_8))
                writer.flush()
                true
            } else false
        }
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        val (resp, sock) = sendRawHttpRequest(
            b.localPort,
            "GET /devtools/browser?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.close()

        assertEquals("HTTP/1.1 101 Switching Protocols", resp)
        assertEquals(1, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 4 Unauthorized WebSocket connections fail`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        val (resp, sock) = sendRawHttpRequest(
            b.localPort,
            "GET /devtools/browser HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.close()

        assertEquals("HTTP/1.1 401 Unauthorized", resp)
        assertEquals(0, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 5 Missing credentials are rejected`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        val (resp, sock) = sendRawHttpRequest(
            b.localPort,
            "GET /json/list HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sock.close()

        assertEquals("HTTP/1.1 401 Unauthorized", resp)
        assertEquals(0, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 6 Incorrect credentials are rejected`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        // Incorrect token in query
        val (resp1, sock1) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version?token=completely_wrong_token HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sock1.close()
        assertEquals("HTTP/1.1 401 Unauthorized", resp1)

        // Incorrect token in header
        val (resp2, sock2) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version HTTP/1.1\r\nHost: 127.0.0.1\r\nX-CuTools-Token: completely_wrong_token\r\n\r\n"
        )
        sock2.close()
        assertEquals("HTTP/1.1 401 Unauthorized", resp2)

        // Prefix match attempt (security bypass attempt)
        val validToken = b.authToken ?: ""
        val prefixToken = validToken.substring(0, 10)
        val (resp3, sock3) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version?token=$prefixToken HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sock3.close()
        assertEquals("HTTP/1.1 401 Unauthorized", resp3)

        assertEquals(0, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 7 Malformed requests are rejected`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        // Raw non-HTTP garbage
        val (resp1, sock1) = sendRawHttpRequest(b.localPort, "MALFORMED_GARBAGE_PAYLOAD\r\n\r\n")
        sock1.close()
        assertTrue(resp1.startsWith("HTTP/1.1 400") || resp1.isEmpty())

        // Incomplete HTTP request line
        val (resp2, sock2) = sendRawHttpRequest(b.localPort, "GET\r\n\r\n")
        sock2.close()
        assertTrue(resp2.startsWith("HTTP/1.1 400") || resp2.isEmpty())

        assertEquals(0, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 8 Authentication cannot be bypassed using alternative paths`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        val testPaths = listOf(
            "/",
            "/json",
            "/json/list",
            "/json/new",
            "/json/activate/42",
            "/json/close/42",
            "/devtools/browser",
            "/devtools/page/abc-123",
            "/../../etc/passwd",
            "/unknown/probe/endpoint",
        )

        for (path in testPaths) {
            val (resp, sock) = sendRawHttpRequest(
                b.localPort,
                "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            )
            sock.close()
            assertEquals("Path $path must be rejected with 401 Unauthorized", "HTTP/1.1 401 Unauthorized", resp)
        }

        // None of these alternative path requests reached Chromium
        assertEquals(0, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 9 Unauthorized connections never reach Chromium debugging socket`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        // Multiple unauthorized attempts
        for (i in 1..10) {
            val (resp, sock) = sendRawHttpRequest(
                b.localPort,
                "GET /json/version?token=attempt_$i HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            )
            sock.close()
            assertEquals("HTTP/1.1 401 Unauthorized", resp)
        }

        // Connector must have received ZERO connect calls
        assertEquals(0, fakeConnector.connectCount.get())
        assertEquals(0, fakeConnector.receivedLines.size)

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 10 Authentication tokens change after bridge restart`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b

        b.start()
        val token1 = b.authToken
        assertNotNull(token1)
        assertTrue(token1!!.isNotEmpty())

        b.stop()
        assertNull(b.authToken)

        b.start()
        val token2 = b.authToken
        assertNotNull(token2)
        assertTrue(token2!!.isNotEmpty())

        assertNotEquals("Token must change on bridge restart", token1, token2)

        assertTrue(b.awaitReady(1000))

        // Request with old token must be rejected
        val (respOld, sockOld) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version?token=$token1 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sockOld.close()
        assertEquals("HTTP/1.1 401 Unauthorized", respOld)

        // Request with new token must be accepted
        val (respNew, sockNew) = sendRawHttpRequest(
            b.localPort,
            "GET /devtools/browser?token=$token2 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sockNew.close()
        assertEquals("ECHO:GET /devtools/browser HTTP/1.1", respNew)

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 11 Existing connection and reconnection behaviour remains functional`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        // Initial connection
        val (resp1, sock1) = sendRawHttpRequest(
            b.localPort,
            "GET /devtools/browser?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\nMSG1\n"
        )
        sock1.close()
        assertEquals("ECHO:GET /devtools/browser HTTP/1.1", resp1)

        // Reconnect with same token
        val (resp2, sock2) = sendRawHttpRequest(
            b.localPort,
            "GET /devtools/browser?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\nMSG2\n"
        )
        sock2.close()
        assertEquals("ECHO:GET /devtools/browser HTTP/1.1", resp2)

        assertEquals(2, fakeConnector.connectCount.get())

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 12 Authentication is enforced independently for every TCP connection`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        // Connection 1: Authorized
        val (r1, s1) = sendRawHttpRequest(b.localPort, "GET /devtools/browser?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals("ECHO:GET /devtools/browser HTTP/1.1", r1)

        // Connection 2: Missing auth
        val (r2, s2) = sendRawHttpRequest(b.localPort, "GET /json/version HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals("HTTP/1.1 401 Unauthorized", r2)
        s2.close()

        // Connection 3: Authorized
        val (r3, s3) = sendRawHttpRequest(b.localPort, "GET /devtools/browser?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals("ECHO:GET /devtools/browser HTTP/1.1", r3)

        // Connection 4: Invalid auth
        val (r4, s4) = sendRawHttpRequest(b.localPort, "GET /devtools/browser?token=bad HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals("HTTP/1.1 401 Unauthorized", r4)
        s4.close()

        // Only connections 1 and 3 ever reached Chromium
        assertEquals(2, fakeConnector.connectCount.get())

        s1.close()
        s3.close()
        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 13 End-to-end WebSocketCdpTransport endpoint resolution and authentication`() {
        var secWebSocketKey: String? = null
        var inJsonVersion = false
        var inWebSocket = false

        val fakeConnector = EchoLocalSocketConnector { line, writer ->
            if (line == "GET /json/version HTTP/1.1") {
                inJsonVersion = true
                true
            } else if (inJsonVersion) {
                if (line.isEmpty()) {
                    inJsonVersion = false
                    val json = "{\"Browser\":\"Chrome/118\",\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/browser/uuid-1234\"}"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\n\r\n$json"
                    writer.write(resp.toByteArray(Charsets.UTF_8))
                    writer.flush()
                }
                true
            } else if (line.startsWith("GET /devtools/browser")) {
                inWebSocket = true
                true
            } else if (inWebSocket) {
                if (line.startsWith("Sec-WebSocket-Key: ", ignoreCase = true)) {
                    secWebSocketKey = line.substringAfter(": ").trim()
                } else if (line.isEmpty()) {
                    inWebSocket = false
                    val key = secWebSocketKey ?: ""
                    val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                    val accept = java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-1").digest((key + magic).toByteArray(Charsets.UTF_8))
                    )
                    val resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                    writer.write(resp.toByteArray(Charsets.UTF_8))
                    writer.flush()
                }
                true
            } else false
        }
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        val transport = com.coppermux.cutools.core.transport.WebSocketCdpTransport()
        val connectedLatch = CountDownLatch(1)
        var lastError: Throwable? = null
        transport.connect(b.debugUrl, object : com.coppermux.cutools.core.transport.CdpTransport.Listener {
            override fun onConnected() {
                connectedLatch.countDown()
            }
            override fun onMessage(message: String) {}
            override fun onError(error: com.coppermux.cutools.core.error.TransportException) {
                lastError = error
            }
            override fun onClosed(reason: String) {}
        })

        assertTrue("WebSocketCdpTransport must connect successfully: ${lastError?.message}", connectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(transport.isConnected)

        transport.disconnect()
        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 14 Authentication rejection logs do not contain malformed HTTP request lines or credentials`() {
        val fakeConnector = EchoLocalSocketConnector()
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        val originalOut = System.out
        val logCapture = java.io.ByteArrayOutputStream()
        val printStream = java.io.PrintStream(logCapture)
        System.setOut(printStream)

        try {
            // Malformed request with invalid request line containing token
            val (r1, s1) = sendRawHttpRequest(b.localPort, "GET /devtools/browser?token=sensitive_token_123\r\n\r\n")
            s1.close()
            assertTrue(r1.startsWith("HTTP/1.1 400"))

            // Malformed request with invalid request line containing auth
            val (r2, s2) = sendRawHttpRequest(b.localPort, "GET /devtools/browser?auth=sensitive_auth_456\r\n\r\n")
            s2.close()
            assertTrue(r2.startsWith("HTTP/1.1 400"))

            // Malformed garbage payload
            val (r3, s3) = sendRawHttpRequest(b.localPort, "MALFORMED_GARBAGE_PAYLOAD_TOKEN_LEAK\r\n\r\n")
            s3.close()
            assertTrue(r3.startsWith("HTTP/1.1 400"))

            // Incomplete request line
            val (r4, s4) = sendRawHttpRequest(b.localPort, "GET\r\n\r\n")
            s4.close()
            assertTrue(r4.startsWith("HTTP/1.1 400"))

            // Unsupported HTTP version with sensitive token
            val (r5, s5) = sendRawHttpRequest(b.localPort, "GET /devtools/browser?token=version_token_789 HTTP/0.9\r\n\r\n")
            s5.close()
            assertTrue(r5.startsWith("HTTP/1.1 400"))

            printStream.flush()
            val capturedLogs = logCapture.toString(Charsets.UTF_8.name())

            // Verify rejection reasons are sanitized and do not contain malformed request lines or credentials
            assertFalse("Rejection logs must not contain sensitive token", capturedLogs.contains("sensitive_token_123"))
            assertFalse("Rejection logs must not contain sensitive auth", capturedLogs.contains("sensitive_auth_456"))
            assertFalse("Rejection logs must not contain version token", capturedLogs.contains("version_token_789"))
            assertFalse("Rejection logs must not contain raw garbage payload", capturedLogs.contains("MALFORMED_GARBAGE_PAYLOAD_TOKEN_LEAK"))
            assertFalse("Rejection logs must not contain malformed request line prefix", capturedLogs.contains("Invalid HTTP request line: "))
            assertTrue("Rejection logs must record generic invalid request line rejection", capturedLogs.contains("Unauthorized connection rejected: Invalid HTTP request line"))
            assertTrue("Rejection logs must record unsupported HTTP version rejection", capturedLogs.contains("Unauthorized connection rejected: Unsupported HTTP version"))
        } finally {
            System.setOut(originalOut)
            fakeConnector.shutdown()
        }
    }

    @Test
    fun `SEC-01 - 15 Both token= and auth= query parameters are supported for bridge authentication and sanitized`() {
        val fakeConnector = EchoLocalSocketConnector { line, writer ->
            if (line == "GET /json/version HTTP/1.1") {
                val json = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 26\r\n\r\n{\"Browser\":\"Chrome/118\"}"
                writer.write(json.toByteArray(Charsets.UTF_8))
                writer.flush()
                true
            } else false
        }
        val resolver = object : WebViewDevToolsSocketResolver(connector = fakeConnector) {
            override fun resolveSocket(timeoutMs: Long): String = "webview_devtools_remote_sec01"
        }
        val b = AndroidCdpBridge(socketResolver = resolver, connector = fakeConnector)
        bridge = b
        assertTrue(b.awaitReady(1000))

        // 1. Authenticate with ?auth= parameter
        val (respAuth, sockAuth) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version?auth=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sockAuth.close()
        assertEquals("HTTP/1.1 200 OK", respAuth)

        // 2. Authenticate with ?token= parameter
        val (respToken, sockToken) = sendRawHttpRequest(
            b.localPort,
            "GET /json/version?token=${b.authToken} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        sockToken.close()
        assertEquals("HTTP/1.1 200 OK", respToken)

        // Verify Chromium debugging socket was accessed 2 times
        assertEquals(2, fakeConnector.connectCount.get())

        // Verify credentials stripped: Chromium never received token or auth query parameters
        assertFalse("Chromium must never receive token= parameter", fakeConnector.receivedLines.any { it.contains("token=") })
        assertFalse("Chromium must never receive auth= parameter", fakeConnector.receivedLines.any { it.contains("auth=") })

        fakeConnector.shutdown()
    }

    @Test
    fun `SEC-01 - 16 WebSocketCdpTransport credential redaction for token= and auth= query parameters`() {
        val redact = WebSocketCdpTransport::redactCredentials

        // Redacts token= query parameter
        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser?token=[REDACTED]",
            redact("ws://127.0.0.1:9222/devtools/browser?token=secret123")
        )

        // Redacts auth= query parameter
        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser?auth=[REDACTED]",
            redact("ws://127.0.0.1:9222/devtools/browser?auth=secret456")
        )

        // Redacts both token= and auth= query parameters together
        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser?token=[REDACTED]&auth=[REDACTED]&foo=bar",
            redact("ws://127.0.0.1:9222/devtools/browser?token=secret123&auth=secret456&foo=bar")
        )

        // Case-insensitivity
        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser?TOKEN=[REDACTED]&AUTH=[REDACTED]",
            redact("ws://127.0.0.1:9222/devtools/browser?TOKEN=secret123&AUTH=secret456")
        )

        // Preserves error message context and punctuation
        assertEquals(
            "Failed to resolve CDP endpoint at http://127.0.0.1:9222/json/version?token=[REDACTED]: Connection refused",
            redact("Failed to resolve CDP endpoint at http://127.0.0.1:9222/json/version?token=secret123: Connection refused")
        )

        assertEquals(
            "Invalid WebSocket URL 'ws://127.0.0.1:9222/bad?auth=[REDACTED]': unexpected url: ws://127.0.0.1:9222/bad?auth=[REDACTED]",
            redact("Invalid WebSocket URL 'ws://127.0.0.1:9222/bad?auth=secret456': unexpected url: ws://127.0.0.1:9222/bad?auth=secret456")
        )

        // Does not false-positive on other parameters
        assertEquals(
            "http://127.0.0.1:9222/devtools?author=john&tokenizer=fast",
            redact("http://127.0.0.1:9222/devtools?author=john&tokenizer=fast")
        )

        // Idempotent on already redacted text
        assertEquals(
            "ws://127.0.0.1:9222/devtools?token=[REDACTED]&auth=[REDACTED]",
            redact("ws://127.0.0.1:9222/devtools?token=[REDACTED]&auth=[REDACTED]")
        )
    }

    @Test
    fun `SEC-01 - 17 WebSocketCdpTransport redacts credentials from transport error messages and closure reasons`() {
        val transport = WebSocketCdpTransport()

        // 1. Test connect with invalid URL containing token= and auth=
        val invalidUrl = "ws://invalid host:9222/devtools/browser?token=super_secret_token&auth=super_secret_auth"
        val errorLatch = CountDownLatch(1)
        var receivedError: TransportException? = null
        var receivedCloseReason: String? = null

        transport.connect(invalidUrl, object : CdpTransport.Listener {
            override fun onConnected() {}
            override fun onMessage(message: String) {}
            override fun onError(error: TransportException) {
                receivedError = error
                errorLatch.countDown()
            }
            override fun onClosed(reason: String) {
                receivedCloseReason = reason
            }
        })

        assertTrue("onError must be called for invalid URL", errorLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedError)
        val errorMessage = receivedError!!.message ?: ""
        assertFalse("Error message must not contain super_secret_token", errorMessage.contains("super_secret_token"))
        assertFalse("Error message must not contain super_secret_auth", errorMessage.contains("super_secret_auth"))
        assertTrue("Error message must contain redacted token parameter", errorMessage.contains("token=[REDACTED]"))
        assertTrue("Error message must contain redacted auth parameter", errorMessage.contains("auth=[REDACTED]"))

        assertNotNull(receivedCloseReason)
        assertFalse("Close reason must not contain super_secret_token", receivedCloseReason!!.contains("super_secret_token"))
        assertFalse("Close reason must not contain super_secret_auth", receivedCloseReason!!.contains("super_secret_auth"))

        // 2. Test endpoint resolution failure to unreachable port containing credentials
        val unresolvableUrl = "http://127.0.0.1:1/json/version?token=resolve_token_xyz&auth=resolve_auth_abc"
        val resolveErrorLatch = CountDownLatch(1)
        var resolveError: TransportException? = null
        var resolveCloseReason: String? = null

        val transport2 = WebSocketCdpTransport()
        transport2.connect(unresolvableUrl, object : CdpTransport.Listener {
            override fun onConnected() {}
            override fun onMessage(message: String) {}
            override fun onError(error: TransportException) {
                resolveError = error
                resolveErrorLatch.countDown()
            }
            override fun onClosed(reason: String) {
                resolveCloseReason = reason
            }
        })

        assertTrue("onError must be called for unresolvable endpoint", resolveErrorLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(resolveError)
        val resolveMsg = resolveError!!.message ?: ""
        assertFalse("Resolution error message must not contain resolve_token_xyz", resolveMsg.contains("resolve_token_xyz"))
        assertFalse("Resolution error message must not contain resolve_auth_abc", resolveMsg.contains("resolve_auth_abc"))
        assertTrue("Resolution error message must contain redacted token parameter", resolveMsg.contains("token=[REDACTED]"))
        assertTrue("Resolution error message must contain redacted auth parameter", resolveMsg.contains("auth=[REDACTED]"))

        assertNotNull(resolveCloseReason)
        assertFalse("Resolution close reason must not contain resolve_token_xyz", resolveCloseReason!!.contains("resolve_token_xyz"))
        assertFalse("Resolution close reason must not contain resolve_auth_abc", resolveCloseReason!!.contains("resolve_auth_abc"))
    }
}