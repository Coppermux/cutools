package com.coppermux.cutools.core.bridge

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

private const val TAG = "CuTools/SocketResolver"

private fun logTrace(tag: String, message: String) {
    try {
        Log.d(tag, message)
    } catch (_: Throwable) {
    }
    println("[$tag] $message")
}

/**
 * Resolves and verifies the active Chromium DevTools abstract UNIX domain socket
 * for the embedded Android WebView.
 *
 * Rather than blindly guessing `webview_devtools_remote_${pid}`, this resolver:
 * 1. Discovers candidate abstract sockets by checking `/proc/net/unix` (when permitted by SELinux)
 *    and the active application process PID.
 * 2. Actively protocol-verifies each candidate by issuing a `/json/version` probe and confirming
 *    that it returns a valid HTTP 200 response with Chromium DevTools metadata.
 * 3. Provides an asynchronous retry/polling loop to account for the asynchronous startup
 *    latency of Chromium's internal DevToolsServer after [android.webkit.WebView.setWebContentsDebuggingEnabled]
 *    is enabled.
 */
open class WebViewDevToolsSocketResolver(
    private val connector: LocalSocketConnector = AndroidLocalSocketConnector(),
    private val pidProvider: () -> Int = {
        runCatching { android.os.Process.myPid() }.getOrElse { 0 }
    },
    private val unixTableReader: (() -> List<String>)? = null,
) {

    /**
     * Resolves the verified abstract socket name, polling up to [timeoutMs] milliseconds.
     *
     * @param timeoutMs Maximum time to wait for the Chromium DevTools socket to become active.
     * @return The verified abstract socket name (without leading '@'), or `null` if not resolved within timeout.
     */
    open fun resolveSocket(timeoutMs: Long = 4000): String? {
        val start = System.currentTimeMillis()
        var attempt = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++
            val candidates = discoverCandidates()
            for (candidate in candidates) {
                if (verifySocket(candidate)) {
                    logTrace(TAG, "Successfully verified active WebView DevTools socket: '$candidate' (attempt #$attempt)")
                    return candidate
                }
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        logTrace(TAG, "Failed to resolve active WebView DevTools socket within ${timeoutMs}ms (attempts=$attempt)")
        return null
    }

    /**
     * Gathers candidate socket names from the process PID and `/proc/net/unix`.
     */
    fun discoverCandidates(): List<String> {
        val candidates = linkedSetOf<String>()

        // 1. Primary candidate: active process PID
        val pid = pidProvider()
        if (pid > 0) {
            candidates.add("webview_devtools_remote_$pid")
        }

        // 2. Secondary candidates: scanned from /proc/net/unix if readable
        val scanned = discoverFromUnixTable()
        candidates.addAll(scanned)

        return candidates.toList()
    }

    /**
     * Attempts to read abstract sockets from `/proc/net/unix`.
     * Safe against Android SELinux access denials on Android 10+ (API 29+).
     */
    private fun discoverFromUnixTable(): List<String> {
        return try {
            val lines = unixTableReader?.invoke() ?: run {
                val procFile = File("/proc/net/unix")
                if (procFile.exists() && procFile.canRead()) {
                    procFile.readLines()
                } else {
                    emptyList()
                }
            }
            val found = mutableListOf<String>()
            for (line in lines) {
                val trimmed = line.trim()
                val lastSpace = trimmed.lastIndexOfAny(charArrayOf(' ', '\t'))
                if (lastSpace != -1) {
                    val path = trimmed.substring(lastSpace + 1).trim()
                    if (path.startsWith("@webview_devtools_remote_")) {
                        found.add(path.removePrefix("@"))
                    }
                }
            }
            found
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Connects to [socketName] and sends an HTTP probe for `/json/version`.
     * Returns true only if the socket answers with HTTP 200 and genuine DevTools metadata.
     */
    fun verifySocket(socketName: String): Boolean {
        return try {
            val stream = connector.connect(socketName)
            stream.use { s ->
                val request = "GET /json/version HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                s.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                s.outputStream.flush()

                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                val statusLine = reader.readLine() ?: return false
                if (!statusLine.contains("200")) return false

                var contentLength = -1
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrEmpty()) {
                    if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    headerLine = reader.readLine()
                }

                val body = if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = reader.read(charArray, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    String(charArray, 0, read)
                } else {
                    val charArray = CharArray(1024)
                    val r = reader.read(charArray)
                    if (r > 0) String(charArray, 0, r) else ""
                }

                body.contains("Browser") ||
                    body.contains("webSocketDebuggerUrl") ||
                    body.contains("Protocol-Version") ||
                    body.contains("Chrome")
            }
        } catch (_: Exception) {
            false
        }
    }
}
