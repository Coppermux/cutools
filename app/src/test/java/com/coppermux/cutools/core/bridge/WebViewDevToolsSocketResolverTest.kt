package com.coppermux.cutools.core.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WebViewDevToolsSocketResolverTest {

    private class FakeByteStreamPair(
        override val inputStream: ByteArrayInputStream,
        override val outputStream: ByteArrayOutputStream = ByteArrayOutputStream(),
    ) : ByteStreamPair {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeLocalSocketConnector(
        private val responses: Map<String, String> = emptyMap(),
        private val throwOnConnect: Set<String> = emptySet(),
    ) : LocalSocketConnector {
        val connectAttempts = mutableListOf<String>()

        override fun connect(abstractSocketName: String): ByteStreamPair {
            connectAttempts.add(abstractSocketName)
            if (abstractSocketName in throwOnConnect) {
                throw IOException("Connection refused to $abstractSocketName")
            }
            val responseBody = responses[abstractSocketName] ?: ""
            val httpResponse = if (responseBody.isNotEmpty()) {
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${responseBody.length}\r\n\r\n$responseBody"
            } else {
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            }
            return FakeByteStreamPair(
                inputStream = ByteArrayInputStream(httpResponse.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    @Test
    fun `discoverCandidates includes PID and unix table entries`() {
        val unixLines = listOf(
            "0000000000000000: 00000003 00000000 00000000 0001 03 45554 /run/user/1000/bus",
            "0000000000000000: 00000002 00000000 00000000 0002 01 25344 @webview_devtools_remote_9999",
            "0000000000000000: 00000002 00000000 00000000 0002 01 25345 @webview_devtools_remote_1234",
        )
        val connector = FakeLocalSocketConnector()
        val resolver = WebViewDevToolsSocketResolver(
            connector = connector,
            pidProvider = { 1234 },
            unixTableReader = { unixLines },
        )

        val candidates = resolver.discoverCandidates()

        // 1234 is primary PID, 9999 is discovered from table, duplicates removed
        assertEquals(2, candidates.size)
        assertEquals("webview_devtools_remote_1234", candidates[0])
        assertEquals("webview_devtools_remote_9999", candidates[1])
    }

    @Test
    fun `verifySocket returns true when candidate returns valid DevTools version JSON`() {
        val json = """
            {
              "Browser": "Chrome/124.0.0.0",
              "Protocol-Version": "1.3",
              "webSocketDebuggerUrl": "ws://127.0.0.1:9222/devtools/browser/abc"
            }
        """.trimIndent()

        val connector = FakeLocalSocketConnector(
            responses = mapOf("webview_devtools_remote_1234" to json),
        )
        val resolver = WebViewDevToolsSocketResolver(
            connector = connector,
            pidProvider = { 1234 },
        )

        val result = resolver.verifySocket("webview_devtools_remote_1234")
        assertTrue(result)
    }

    @Test
    fun `verifySocket returns false when socket connection fails or is not DevTools`() {
        val connector = FakeLocalSocketConnector(
            throwOnConnect = setOf("webview_devtools_remote_fail"),
            responses = mapOf("webview_devtools_remote_empty" to ""),
        )
        val resolver = WebViewDevToolsSocketResolver(
            connector = connector,
            pidProvider = { 1234 },
        )

        assertFalse(resolver.verifySocket("webview_devtools_remote_fail"))
        assertFalse(resolver.verifySocket("webview_devtools_remote_empty"))
    }

    @Test
    fun `resolveSocket selects first verified candidate`() {
        val json = """{"Browser": "Chrome/124", "Protocol-Version": "1.3"}"""
        val connector = FakeLocalSocketConnector(
            throwOnConnect = setOf("webview_devtools_remote_1234"),
            responses = mapOf("webview_devtools_remote_5678" to json),
        )
        val resolver = WebViewDevToolsSocketResolver(
            connector = connector,
            pidProvider = { 1234 },
            unixTableReader = { listOf("0: 0 0 0 0 0 0 @webview_devtools_remote_5678") },
        )

        val resolved = resolver.resolveSocket(timeoutMs = 500)
        assertEquals("webview_devtools_remote_5678", resolved)
    }

    @Test
    fun `resolveSocket returns null when all candidates fail within timeout`() {
        val connector = FakeLocalSocketConnector(
            throwOnConnect = setOf("webview_devtools_remote_1234"),
        )
        val resolver = WebViewDevToolsSocketResolver(
            connector = connector,
            pidProvider = { 1234 },
        )

        val resolved = resolver.resolveSocket(timeoutMs = 250)
        assertNull(resolved)
    }
}
