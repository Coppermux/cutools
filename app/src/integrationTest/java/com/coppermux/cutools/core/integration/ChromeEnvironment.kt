package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.model.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Environment helper for integration tests that require a live headless
 * Chrome/Chromium debugging endpoint.
 *
 * Phase 14 uses port 9224 as the canonical remote-debugging port for the
 * integration test harness (see `.github/workflows/ci.yml`).
 */
class ChromeEnvironment {

    val endpointPort: Int = 9224

    fun versionUrl(): String = "http://127.0.0.1:$endpointPort/json/version"

    /** Returns the WebSocket debugging URL when Chrome is reachable, else "". */
    fun discoverWebSocketUrl(): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return try {
            val req = Request.Builder().url(versionUrl()).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = Protocol.moshi.adapter(Map::class.java).fromJson(body)
                (json?.get("webSocketDebuggerUrl") as? String).orEmpty()
            }
        } catch (e: Exception) {
            ""
        }
    }

    val isReachable: Boolean get() = webSocketDebuggerAvailable()

    private fun webSocketDebuggerAvailable(): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return try {
            val req = Request.Builder().url(versionUrl()).build()
            client.newCall(req).execute().use { resp ->
                resp.code == 200
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Attempts to spawn a headless Chromium process on [endpointPort]. Returns true on success. */
    fun startHeadlessChrome(): Boolean {
        if (isReachable) return true
        val candidates = listOf(
            "chromium-browser",
            "chromium",
            "google-chrome",
            "google-chrome-stable",
        )
        for (binary in candidates) {
            try {
                val proc = ProcessBuilder()
                    .command(
                        binary,
                        "--remote-debugging-port=$endpointPort",
                        "--headless",
                        "--no-sandbox",
                        "--disable-gpu",
                        "about:blank"
                    )
                    .start()
                if (proc.isAlive) {
                    // Poll for the debugging endpoint to come up.
                    for (i in 0..29) {
                        if (isReachable) return true
                        Thread.sleep(1000)
                    }
                    proc.destroy()
                }
            } catch (e: Exception) {
                // Try the next candidate.
            }
        }
        return false
    }
}