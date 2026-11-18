package com.coppermux.cutools.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 19 — pure unit tests for the cURL command generator. Every assertion
 * verifies deterministic, shell-safe output: correct quoting/escaping of URLs,
 * header names/values, and request bodies, plus method pinning and event
 * mapping.
 */
class CurlGeneratorTest {

    // ================================================================
    // Basic command shape
    // ================================================================

    @Test
    fun generate_getOnly_emitsBareCurlWithQuotedUrl() {
        assertEquals(
            """curl "https://example.com/api/items?page=2" """.trimEnd(),
            CurlGenerator.generate(url = "https://example.com/api/items?page=2"),
        )
    }

    @Test
    fun generate_getOnly_omitsMethodFlag() {
        val cmd = CurlGenerator.generate(url = "https://example.com/")
        assertTrue("GET should not emit -X", !cmd.contains("-X"))
        assertEquals("""curl "https://example.com/"""", cmd)
    }

    @Test
    fun generate_blankUrl_yieldsBareCurl() {
        assertEquals("curl", CurlGenerator.generate(url = ""))
    }

    @Test
    fun generate_blankUrlWithMethod_pinsMethodHonestly() {
        assertEquals("""curl -X "POST"""", CurlGenerator.generate(url = "", method = "POST"))
    }

    // ================================================================
    // Headers
    // ================================================================

    @Test
    fun generate_headers_emitQuotedDashHFlags() {
        val cmd = CurlGenerator.generate(
            url = "https://api.example.com/items",
            headers = mapOf("Accept" to "application/json", "X-Api-Key" to "abc123"),
        )
        assertTrue(cmd.contains("""-H "Accept: application/json""""))
        assertTrue(cmd.contains("""-H "X-Api-Key: abc123""""))
    }

    @Test
    fun generate_headerValueQuotedAndEscaped() {
        val cmd = CurlGenerator.generate(
            url = "https://example.com/",
            headers = mapOf("X-Token" to "a\"b\\c"),
        )
        assertTrue(cmd.contains("""-H "X-Token: a\"b\\c""""))
    }

    @Test
    fun generate_emptyHeaders_emitNoHeaderFlags() {
        val cmd = CurlGenerator.generate(url = "https://example.com/", headers = emptyMap())
        assertFalse(cmd.contains("-H"))
    }

    // ================================================================
    // Methods + bodies
    // ================================================================

    @Test
    fun generate_postWithBody_emitsMethodAndDataRaw() {
        val cmd = CurlGenerator.generate(
            url = "https://api.example.com/login",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"user":"a"}""",
        )
        assertTrue(cmd.startsWith("""curl "https://api.example.com/login""""))
        assertTrue(cmd.contains("""-X "POST""""))
        assertTrue(cmd.contains("""--data-raw '{"user":"a"}'"""))
    }

    @Test
    fun generate_getWithBody_pinsGetInsteadOfPost() {
        val cmd = CurlGenerator.generate(
            url = "https://example.com/inspect?x=1",
            method = "GET",
            body = "probe",
        )
        assertTrue(cmd.contains("""-X "GET""""))
        assertTrue(cmd.contains("""--data-raw 'probe'"""))
    }

    @Test
    fun generate_bodyWithSingleQuote_escapedPosixSafe() {
        val body = "it's fine"
        val cmd = CurlGenerator.generate(url = "https://example.com/", method = "POST", body = body)
        assertTrue(cmd.contains("""--data-raw 'it'\''s fine'"""))
    }

    @Test
    fun generate_methodNormalizedToUpper() {
        val cmd = CurlGenerator.generate(url = "https://example.com/", method = "patch", body = "x")
        assertTrue(cmd.contains("""-X "PATCH""""))
    }

    @Test
    fun generate_nullBody_emitsNoDataRaw() {
        val cmd = CurlGenerator.generate(
            url = "https://example.com/",
            method = "POST",
            headers = mapOf("X-K" to "v"),
            body = null,
        )
        assertFalse(cmd.contains("--data-raw"))
        assertTrue(cmd.contains("""-X "POST""""))
    }

    // ================================================================
    // URL handling
    // ================================================================

    @Test
    fun generate_preservesQueryParametersVerbatim() {
        val url = "https://example.com/search?q=hello+world&format=json&x=%20"
        val cmd = CurlGenerator.generate(url = url)
        assertTrue(cmd.contains("\"$url\""))
    }

    @Test
    fun generate_urlWithEmbeddedQuote_isEscaped() {
        val url = "https://example.com/\"weird\""
        val cmd = CurlGenerator.generate(url = url)
        assertTrue(cmd.contains("\"https://example.com/\\\"weird\\\"\""))
    }

    // ================================================================
    // Event mapping
    // ================================================================

    @Test
    fun fromEvent_usesEventMethodUrlHeadersAndBody() {
        val event = DashboardEventClassifier.buildNetworkRequestEvent(
            url = "https://api.example.com/order",
            method = "PUT",
            isForMainFrame = false,
            timestamp = 1_000,
            requestHeaders = mapOf("Authorization" to "Bearer tok"),
        ).copy(requestBody = "{\"qty\":1}")

        val cmd = CurlGenerator.fromEvent(event)
        assertTrue(cmd.contains("""curl "https://api.example.com/order""""))
        assertTrue(cmd.contains("""-X "PUT""""))
        assertTrue(cmd.contains("""-H "Authorization: Bearer tok""""))
        assertTrue(cmd.contains("""--data-raw '{"qty":1}'"""))
    }

    @Test
    fun fromEvent_nullMethodDefaultsToGet() {
        val event = DashboardEventClassifier.buildNetworkFailureEvent(
            url = "https://example.com/",
            timestamp = 1_000,
        ).copy(method = null)
        val cmd = CurlGenerator.fromEvent(event)
        assertTrue(cmd.startsWith("""curl "https://example.com/""""))
        assertFalse(cmd.contains("-X"))
    }

    // ================================================================
    // Escaping primitives
    // ================================================================

    @Test
    fun quoteDouble_escapesBackslashAndQuote() {
        assertEquals("\"a\\\\b\\\"c\"", CurlGenerator.quoteDouble("a\\b\"c"))
    }

    @Test
    fun quoteSingle_escapesSingleQuotes() {
        assertEquals("'a'\\''b'", CurlGenerator.quoteSingle("a'b"))
    }
}