package com.coppermux.cutools.core.bridge

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CuTools/AndroidCdpBridge"
private const val MAX_HEADER_BYTES = 16 * 1024
private const val AUTH_READ_TIMEOUT_MS = 3000
private val CREDENTIAL_REGEX = Regex("(?i)\\b(token|auth)=(\\[REDACTED\\]|[^&\\s'\"\\)\\]>:,;]+(?<!\\.))")

private fun logTrace(tag: String, message: String) {
    val sanitized = message.replace(CREDENTIAL_REGEX, "$1=[REDACTED]")
    try {
        Log.d(tag, sanitized)
    } catch (_: Throwable) {
    }
    println("[$tag] $sanitized")
}

private class AuthResult(
    val isAuthorized: Boolean,
    val rejectReason: String? = null,
    val errorHttpResponse: String? = null,
    val sanitizedHeaderBytes: ByteArray? = null,
)

/**
 * Native in-process CDP bridge for Android WebView with SEC-01 authentication.
 *
 * Bridges the CuTools CDP networking stack ([com.coppermux.cutools.core.transport.WebSocketCdpTransport])
 * to the Android Chromium WebView's abstract UNIX domain socket (`@webview_devtools_remote_<PID>`).
 *
 * Key Architectural Characteristics:
 * 1. **Dynamic Ephemeral Port:** Binds a loopback TCP [ServerSocket] to `127.0.0.1:0` to guarantee
 *    zero port collisions with external Chrome, ADB forwards, or other services.
 * 2. **SEC-01 In-Memory Authentication:** Enforces cryptographically random token authentication
 *    for every incoming TCP connection. Unauthorized connections are rejected before opening the
 *    Chromium debugging socket or forwarding any bytes.
 * 3. **Credential Stripping:** Automatically removes authentication credentials (query parameters
 *    and CuTools auth headers) before forwarding requests to Chromium, preserving Chromium's
 *    native HTTP and RFC 6455 WebSocket protocol handling.
 * 4. **Concurrency & Lifecycle Management:** Safely tracks all active connections, gracefully
 *    terminating all child sockets and background threads upon [stop] or [close].
 * 5. **Endpoint Readiness Synchronization:** Exposes [awaitReady] to coordinate with
 *    [WebViewDevToolsSocketResolver] so CDP connection attempts are never initiated before
 *    Chromium has opened and initialized its DevTools server socket.
 */
class AndroidCdpBridge(
    private val socketResolver: WebViewDevToolsSocketResolver = WebViewDevToolsSocketResolver(),
    private val connector: LocalSocketConnector = AndroidLocalSocketConnector(),
) : Closeable {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var verifiedSocketName: String? = null

    @Volatile
    private var currentAuthToken: String? = null

    private val isRunning = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "cutools-bridge-worker").apply { isDaemon = true }
    }

    private val activeConnections = ConcurrentHashMap.newKeySet<Pair<Socket, ByteStreamPair>>()
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()

    /** Dynamic TCP port assigned by the OS. Returns 0 if not running. */
    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    /** The active cryptographically secure authentication token, or null if not running. Kept private in-memory. */
    val authToken: String?
        get() = currentAuthToken

    /** The canonical browser debugger WebSocket URL for this bridge, including authentication token. */
    val debugUrl: String
        get() {
            val tokenQuery = currentAuthToken?.let { "?token=$it" }.orEmpty()
            return "ws://127.0.0.1:$localPort/devtools/browser$tokenQuery"
        }

    /** The verified abstract socket name currently bridged, or null. */
    val activeDebugSocketName: String?
        get() = verifiedSocketName

    /** Whether the bridge is currently started and accepting connections. */
    val running: Boolean
        get() = isRunning.get() && serverSocket?.isClosed == false

    /** Whether the bridge has successfully resolved and verified the WebView DevTools socket. */
    val ready: Boolean
        get() = isReady.get() && running && verifiedSocketName != null

    private fun generateSecureToken(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        return randomBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Starts the loopback TCP server on an ephemeral port.
     * Generates a new cryptographically secure random authentication token.
     * Idempotent: returns existing port if already running.
     *
     * @return The dynamically allocated local port.
     */
    @Synchronized
    fun start(): Int {
        val existing = serverSocket
        if (isRunning.get() && existing != null && !existing.isClosed) {
            return existing.localPort
        }

        currentAuthToken = generateSecureToken()
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        isRunning.set(true)
        logTrace(TAG, "Bridge server started on 127.0.0.1:${ss.localPort}")

        val acceptThread = Thread({
            while (isRunning.get() && !ss.isClosed) {
                try {
                    val clientSocket = ss.accept()
                    clientSocket.tcpNoDelay = true
                    activeClientSockets.add(clientSocket)
                    handleClient(clientSocket)
                } catch (_: SocketException) {
                    if (!isRunning.get()) break
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    logTrace(TAG, "Error accepting connection: ${e.message}")
                }
            }
        }, "cutools-bridge-accept").apply { isDaemon = true }
        acceptThread.start()

        return ss.localPort
    }

    /**
     * Ensures the bridge is started and waits up to [timeoutMs] for the active
     * WebView DevTools socket to be resolved and protocol-verified.
     *
     * @return `true` if ready to accept CDP connections; `false` if resolution timed out.
     */
    fun awaitReady(timeoutMs: Long = 4000): Boolean {
        if (!isRunning.get()) {
            start()
        }
        if (verifiedSocketName != null && isReady.get()) {
            return true
        }

        val resolved = socketResolver.resolveSocket(timeoutMs)
        if (resolved != null) {
            verifiedSocketName = resolved
            isReady.set(true)
            logTrace(TAG, "Bridge ready with target socket: '$resolved' on port $localPort")
            return true
        }
        logTrace(TAG, "Bridge not ready: failed to resolve DevTools socket within ${timeoutMs}ms")
        return false
    }

    private fun handleClient(tcpSocket: Socket) {
        executor.execute {
            try {
                if (!isRunning.get()) {
                    activeClientSockets.remove(tcpSocket)
                    runCatching { tcpSocket.close() }
                    return@execute
                }

                tcpSocket.soTimeout = AUTH_READ_TIMEOUT_MS
                val authResult = authenticateAndSanitize(tcpSocket.getInputStream())
                if (!authResult.isAuthorized) {
                    activeClientSockets.remove(tcpSocket)
                    logTrace(TAG, "Unauthorized connection rejected: ${authResult.rejectReason}")
                    val response = authResult.errorHttpResponse
                    if (response != null && isRunning.get()) {
                        runCatching {
                            val out = tcpSocket.getOutputStream()
                            out.write(response.toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                    }
                    runCatching { tcpSocket.close() }
                    return@execute
                }

                // Authorized: reset read timeout for regular streaming
                tcpSocket.soTimeout = 0

                val targetSocketName = verifiedSocketName ?: run {
                    socketResolver.resolveSocket(1000)?.also {
                        verifiedSocketName = it
                        isReady.set(true)
                    }
                }

                if (targetSocketName == null) {
                    logTrace(TAG, "Client rejected: no active WebView DevTools socket verified")
                    runCatching { tcpSocket.close() }
                    return@execute
                }

                val localStream: ByteStreamPair
                try {
                    localStream = connector.connect(targetSocketName)
                } catch (e: Exception) {
                    logTrace(TAG, "Failed to connect to abstract socket '$targetSocketName': ${e.message}")
                    runCatching { tcpSocket.close() }
                    return@execute
                }

                // Forward sanitized request bytes (with credentials stripped) to Chromium
                val sanitizedHeader = authResult.sanitizedHeaderBytes
                if (sanitizedHeader != null && sanitizedHeader.isNotEmpty()) {
                    try {
                        localStream.outputStream.write(sanitizedHeader)
                        localStream.outputStream.flush()
                    } catch (e: Exception) {
                        logTrace(TAG, "Failed to forward sanitized request to abstract socket: ${e.message}")
                        runCatching { tcpSocket.close() }
                        runCatching { localStream.close() }
                        return@execute
                    }
                }

                val pair = tcpSocket to localStream
                activeConnections.add(pair)

                val closed = AtomicBoolean(false)
                val closeBoth = {
                    if (closed.compareAndSet(false, true)) {
                        activeConnections.remove(pair)
                        activeClientSockets.remove(tcpSocket)
                        runCatching { tcpSocket.close() }
                        runCatching { localStream.close() }
                    }
                }

                val tcpIn = runCatching { tcpSocket.getInputStream() }.getOrNull()
                val tcpOut = runCatching { tcpSocket.getOutputStream() }.getOrNull()
                if (tcpIn == null || tcpOut == null) {
                    closeBoth()
                    return@execute
                }

                // Forward: TCP client -> Abstract UNIX socket
                executor.execute {
                    try {
                        pipe(tcpIn, localStream.outputStream)
                    } catch (_: Exception) {
                    } finally {
                        closeBoth()
                    }
                }

                // Backward: Abstract UNIX socket -> TCP client
                executor.execute {
                    try {
                        pipe(localStream.inputStream, tcpOut)
                    } catch (_: Exception) {
                    } finally {
                        closeBoth()
                    }
                }
            } catch (e: Exception) {
                logTrace(TAG, "Error handling client connection: ${e.message}")
                runCatching { tcpSocket.close() }
            }
        }
    }

    private fun readHeaderBytes(input: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4)
        var count = 0
        while (baos.size() < MAX_HEADER_BYTES) {
            val b = try {
                input.read()
            } catch (_: Exception) {
                -1
            }
            if (b == -1) {
                return null
            }
            baos.write(b)
            count++
            val byteVal = b.toByte()
            buf[0] = buf[1]
            buf[1] = buf[2]
            buf[2] = buf[3]
            buf[3] = byteVal

            if (count >= 4 && buf[0] == 13.toByte() && buf[1] == 10.toByte() && buf[2] == 13.toByte() && buf[3] == 10.toByte()) {
                return baos.toByteArray()
            }
            if (count >= 2 && buf[2] == 10.toByte() && buf[3] == 10.toByte()) {
                return baos.toByteArray()
            }
        }
        return null
    }

    private fun authenticateAndSanitize(input: InputStream): AuthResult {
        if (!isRunning.get()) {
            return AuthResult(
                isAuthorized = false,
                rejectReason = "Bridge stopped",
                errorHttpResponse = null
            )
        }

        val expectedToken = currentAuthToken ?: return AuthResult(
            isAuthorized = false,
            rejectReason = "Bridge is not running or token is missing",
            errorHttpResponse = "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: 12\r\nConnection: close\r\n\r\nUnauthorized"
        )

        val rawBytes = readHeaderBytes(input) ?: return AuthResult(
            isAuthorized = false,
            rejectReason = "Malformed HTTP request or header size exceeded limit",
            errorHttpResponse = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: 11\r\nConnection: close\r\n\r\nBad Request"
        )

        val rawHeaderString = String(rawBytes, Charsets.ISO_8859_1)
        val lines = rawHeaderString.split("\r\n").let { list ->
            if (list.size <= 1) rawHeaderString.split("\n") else list
        }
        if (lines.isEmpty() || lines[0].isBlank()) {
            return AuthResult(
                isAuthorized = false,
                rejectReason = "Empty HTTP request",
                errorHttpResponse = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: 11\r\nConnection: close\r\n\r\nBad Request"
            )
        }

        val requestLine = lines[0].trim()
        val requestLineTokens = requestLine.split(" ").filter { it.isNotEmpty() }
        if (requestLineTokens.size != 3) {
            return AuthResult(
                isAuthorized = false,
                rejectReason = "Invalid HTTP request line",
                errorHttpResponse = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: 11\r\nConnection: close\r\n\r\nBad Request"
            )
        }

        val method = requestLineTokens[0]
        val uri = requestLineTokens[1]
        val httpVersion = requestLineTokens[2]

        if (!httpVersion.startsWith("HTTP/1.", ignoreCase = true)) {
            return AuthResult(
                isAuthorized = false,
                rejectReason = "Unsupported HTTP version",
                errorHttpResponse = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: 11\r\nConnection: close\r\n\r\nBad Request"
            )
        }

        // Parse headers and look for credentials
        val headerLines = mutableListOf<String>()
        var tokenFromHeader: String? = null
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val name = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                if (name.equals("X-CuTools-Token", ignoreCase = true)) {
                    tokenFromHeader = value
                    continue // Omit from forwarded headers
                }
                if (name.equals("Authorization", ignoreCase = true) && value.startsWith("Bearer ", ignoreCase = true)) {
                    val bearer = value.substring(7).trim()
                    if (MessageDigest.isEqual(bearer.toByteArray(Charsets.UTF_8), expectedToken.toByteArray(Charsets.UTF_8))) {
                        tokenFromHeader = bearer
                        continue // Omit from forwarded headers
                    }
                }
                headerLines.add(line)
            }
        }

        // Parse query string in URI and look for credentials
        val queryStart = uri.indexOf('?')
        val path = if (queryStart >= 0) uri.substring(0, queryStart) else uri
        val queryString = if (queryStart >= 0) uri.substring(queryStart + 1) else null

        var tokenFromUri: String? = null
        val remainingQueryParams = mutableListOf<String>()

        if (queryString != null) {
            val params = queryString.split("&")
            for (param in params) {
                if (param.isEmpty()) continue
                val eqIdx = param.indexOf('=')
                val key = if (eqIdx >= 0) param.substring(0, eqIdx) else param
                val value = if (eqIdx >= 0) param.substring(eqIdx + 1) else ""
                if (key.equals("token", ignoreCase = true) || key.equals("auth", ignoreCase = true)) {
                    tokenFromUri = value
                } else {
                    remainingQueryParams.add(param)
                }
            }
        }

        val candidateToken = tokenFromUri ?: tokenFromHeader

        if (candidateToken.isNullOrEmpty()) {
            return AuthResult(
                isAuthorized = false,
                rejectReason = "Missing credentials",
                errorHttpResponse = "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: 12\r\nConnection: close\r\n\r\nUnauthorized"
            )
        }

        val isMatch = MessageDigest.isEqual(
            candidateToken.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8)
        )

        if (!isMatch) {
            return AuthResult(
                isAuthorized = false,
                rejectReason = "Invalid credentials",
                errorHttpResponse = "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: 12\r\nConnection: close\r\n\r\nUnauthorized"
            )
        }

        // Construct sanitized Request-URI (without authentication credentials)
        val sanitizedUri = if (remainingQueryParams.isEmpty()) {
            path
        } else {
            "$path?${remainingQueryParams.joinToString("&")}"
        }

        val sanitizedRequestLine = "$method $sanitizedUri $httpVersion"
        val sb = StringBuilder()
        sb.append(sanitizedRequestLine).append("\r\n")
        for (h in headerLines) {
            sb.append(h).append("\r\n")
        }
        sb.append("\r\n")

        return AuthResult(
            isAuthorized = true,
            sanitizedHeaderBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        )
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (isRunning.get()) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: IOException) {
        } catch (_: SocketException) {
        }
    }

    /**
     * Stops the bridge server, invalidates the authentication token, and closes all active connections.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        isReady.set(false)
        verifiedSocketName = null
        currentAuthToken = null

        val ss = serverSocket
        serverSocket = null
        if (ss != null) {
            runCatching { ss.close() }
        }

        activeClientSockets.forEach { tcp ->
            runCatching { tcp.close() }
        }
        activeClientSockets.clear()

        activeConnections.forEach { (tcp, local) ->
            runCatching { tcp.close() }
            runCatching { local.close() }
        }
        activeConnections.clear()
        logTrace(TAG, "Bridge server stopped cleanly")
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }
}
