package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.TransportException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real WebSocket implementation of [CdpTransport].
 *
 * Uses OkHttp's WebSocket support. All callbacks are delivered on the OkHttp
 * dispatcher thread, which is never the Android UI thread.
 */
class WebSocketCdpTransport(
    private val client: OkHttpClient = DefaultOkHttpClient,
) : CdpTransport {

    companion object {
        private val DefaultOkHttpClient: OkHttpClient = OkHttpClient.Builder().build()
        private val CREDENTIAL_REGEX = Regex("(?i)\\b(token|auth)=(\\[REDACTED\\]|[^&\\s'\"\\)\\]>:,;]+(?<!\\.))")

        internal fun redactCredentials(input: String): String {
            return input.replace(CREDENTIAL_REGEX, "$1=[REDACTED]")
        }
    }

    private val connected = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var activeCall: Call? = null
    private var listener: CdpTransport.Listener? = null
    private val lock = Any()

    override val isConnected: Boolean
        get() = connected.get()

    override fun connect(url: String, listener: CdpTransport.Listener) {
        synchronized(lock) {
            this.listener = listener
            activeCall?.cancel()
            activeCall = null
            if (connected.get()) {
                // Already connected — close first
                webSocket?.cancel()
                webSocket = null
            }
            connected.set(false)
        }

        if (needsEndpointResolution(url)) {
            resolveAndConnect(url)
        } else {
            connectWebSocket(url)
        }
    }

    private fun needsEndpointResolution(url: String): Boolean {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return true
        }
        val httpUrl = url
            .replaceFirst("^ws://".toRegex(RegexOption.IGNORE_CASE), "http://")
            .replaceFirst("^wss://".toRegex(RegexOption.IGNORE_CASE), "https://")
        val parsed = httpUrl.toHttpUrlOrNull() ?: return false
        val segments = parsed.pathSegments
        // e.g. ws://host:port or ws://host:port/
        if (segments.isEmpty() || (segments.size == 1 && segments[0].isEmpty())) {
            return true
        }
        // e.g. ws://host:port/devtools/browser (without GUID)
        if (segments.size == 2 && segments[0] == "devtools" && segments[1] == "browser") {
            return true
        }
        if (segments.size == 3 && segments[0] == "devtools" && segments[1] == "browser" && segments[2].isEmpty()) {
            return true
        }
        return false
    }

    private fun resolveAndConnect(originalUrl: String) {
        val httpEquivalent = originalUrl
            .replaceFirst("^ws://".toRegex(RegexOption.IGNORE_CASE), "http://")
            .replaceFirst("^wss://".toRegex(RegexOption.IGNORE_CASE), "https://")
        val parsed = httpEquivalent.toHttpUrlOrNull()
        if (parsed == null) {
            connectWebSocket(originalUrl)
            return
        }

        val queryPart = parsed.query?.let { "?$it" } ?: ""
        val versionUrl = "${parsed.scheme}://${parsed.host}:${parsed.port}/json/version$queryPart"
        val request = Request.Builder().url(versionUrl).build()

        val call = client.newCall(request)
        synchronized(lock) {
            activeCall = call
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(lock) {
                    if (activeCall != call) return
                    activeCall = null
                }
                if (originalUrl.startsWith("ws://", ignoreCase = true) || originalUrl.startsWith("wss://", ignoreCase = true)) {
                    connectWebSocket(originalUrl)
                } else {
                    val l = synchronized(lock) { listener }
                    val rawMsg = "Failed to resolve CDP endpoint at $versionUrl: ${e.message}"
                    val error = TransportException(redactCredentials(rawMsg), cause = e)
                    l?.onError(error)
                    l?.onClosed(error.message ?: "Resolution failed")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(lock) {
                    if (activeCall != call) return
                    activeCall = null
                }
                val body = response.use { resp ->
                    if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                }
                val wsDebuggerUrl = parseWebSocketDebuggerUrl(body)
                if (!wsDebuggerUrl.isNullOrEmpty()) {
                    val resolvedUrl = rewriteHostAndPort(wsDebuggerUrl, parsed.host, parsed.port, preserveQuery = parsed.query)
                    connectWebSocket(resolvedUrl)
                } else {
                    if (originalUrl.startsWith("ws://", ignoreCase = true) || originalUrl.startsWith("wss://", ignoreCase = true)) {
                        connectWebSocket(originalUrl)
                    } else {
                        val l = synchronized(lock) { listener }
                        val rawMsg = "No webSocketDebuggerUrl returned by $versionUrl (HTTP ${response.code})"
                        val error = TransportException(redactCredentials(rawMsg))
                        l?.onError(error)
                        l?.onClosed(error.message ?: "Resolution failed")
                    }
                }
            }
        })
    }

    private fun parseWebSocketDebuggerUrl(json: String): String? {
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                val array = org.json.JSONArray(trimmed)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    obj.optString("webSocketDebuggerUrl").takeIf { it.isNotEmpty() }
                } else null
            } else {
                val obj = JSONObject(trimmed)
                obj.optString("webSocketDebuggerUrl").takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun rewriteHostAndPort(
        wsUrl: String,
        desiredHost: String,
        desiredPort: Int,
        preserveQuery: String? = null,
    ): String {
        val httpEquivalent = wsUrl
            .replaceFirst("^ws://".toRegex(RegexOption.IGNORE_CASE), "http://")
            .replaceFirst("^wss://".toRegex(RegexOption.IGNORE_CASE), "https://")
        val parsed = httpEquivalent.toHttpUrlOrNull() ?: return wsUrl
        val isWss = wsUrl.startsWith("wss://", ignoreCase = true)
        val scheme = if (isWss) "https" else "http"
        val builder = parsed.newBuilder()
            .scheme(scheme)
            .host(desiredHost)
            .port(desiredPort)
        if (!preserveQuery.isNullOrEmpty()) {
            val combinedQuery = listOfNotNull(parsed.query, preserveQuery).joinToString("&")
            builder.query(combinedQuery)
        }
        val rewritten = builder.build().toString()
        val wsScheme = if (isWss) "wss://" else "ws://"
        return rewritten.replaceFirst("^https?://".toRegex(RegexOption.IGNORE_CASE), wsScheme)
    }

    private fun connectWebSocket(targetWsUrl: String) {
        synchronized(lock) {
            val l = listener ?: return
            if (connected.get()) {
                webSocket?.cancel()
                webSocket = null
            }
            connected.set(false)

            try {
                val request = Request.Builder()
                    .url(targetWsUrl)
                    .build()
                webSocket = client.newWebSocket(request, WebSocketListenerAdapter)
            } catch (e: Throwable) {
                val rawMsg = "Invalid WebSocket URL '$targetWsUrl': ${e.message}"
                val error = TransportException(redactCredentials(rawMsg), cause = e)
                l.onError(error)
                l.onClosed(error.message ?: "Invalid URL")
            }
        }
    }

    override fun send(rawMessage: String) {
        val ws = synchronized(lock) { webSocket }
        if (ws == null || !connected.get()) {
            throw TransportException("Cannot send: transport is not connected")
        }
        if (!ws.send(rawMessage)) {
            throw TransportException("Failed to send message (WebSocket not open)")
        }
    }

    override fun disconnect() {
        val ws = synchronized(lock) {
            listener = null
            activeCall?.cancel()
            activeCall = null
            val old = webSocket
            webSocket = null
            connected.set(false)
            old
        }
        ws?.close(1000, "Disconnect requested")
    }

    private val WebSocketListenerAdapter = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            val l = synchronized(lock) {
                if (ws !== webSocket) return
                connected.set(true)
                listener
            }
            l?.onConnected()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val l = synchronized(lock) {
                if (ws !== webSocket) return
                listener
            }
            l?.onMessage(text)
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            val l = synchronized(lock) {
                if (ws !== webSocket) return
                listener
            }
            l?.onMessage(bytes.utf8())
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            val l = synchronized(lock) {
                if (ws !== webSocket) return
                connected.set(false)
                listener
            } ?: return
            val error = if (response != null) {
                val rawMsg = "WebSocket connection failed: ${response.message} (${response.code})"
                TransportException(
                    redactCredentials(rawMsg),
                    cause = t,
                    code = "WS_FAILURE_${response.code}"
                )
            } else {
                val rawMsg = "WebSocket connection failed: ${t.message}"
                TransportException(redactCredentials(rawMsg), cause = t)
            }
            l.onError(error)
            l.onClosed(error.message ?: "Connection failed")
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            val l = synchronized(lock) {
                if (ws !== webSocket) return
                connected.set(false)
                listener
            }
            l?.onClosed(redactCredentials("Closing: $reason ($code)"))
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            val l = synchronized(lock) {
                if (ws !== webSocket) return
                connected.set(false)
                listener
            }
            l?.onClosed(redactCredentials("Closed: $reason ($code)"))
        }
    }
}
