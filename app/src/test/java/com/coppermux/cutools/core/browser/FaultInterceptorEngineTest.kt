package com.coppermux.cutools.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 19 — pure unit tests for the fault-interceptor engine: rule registry
 * semantics, deterministic glob/method matching, and the decision mapping for
 * latency / status override / connection drop / mock-body faults.
 */
class FaultInterceptorEngineTest {

    private fun rule(
        id: String = "rule-1",
        urlPattern: String = "https://api.example.com/*",
        method: String? = null,
        latencyMs: Int = 0,
        statusCode: Int? = null,
        dropConnection: Boolean = false,
        mockBody: String? = null,
    ): FaultRule = FaultRule(
        id = id,
        urlPattern = urlPattern,
        method = method,
        latencyMs = latencyMs,
        statusCode = statusCode,
        dropConnection = dropConnection,
        mockBody = mockBody,
    )

    // ================================================================
    // Registry semantics
    // ================================================================

    @Test
    fun upsert_addsAndReplacesById() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(id = "a", urlPattern = "https://x.com/*", statusCode = 500))
        engine.upsert(rule(id = "a", urlPattern = "https://x.com/*", statusCode = 503))
        assertEquals(1, engine.size())
        assertEquals(503, engine.find("a")?.statusCode)
    }

    @Test
    fun remove_returnsExistenceAndClears() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(id = "a"))
        assertTrue(engine.remove("a"))
        assertFalse(engine.remove("a"))
        assertEquals(0, engine.size())
    }

    @Test
    fun clear_removesAllRules() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(id = "a"))
        engine.upsert(rule(id = "b"))
        engine.clear()
        assertEquals(0, engine.size())
        assertTrue(engine.all().isEmpty())
    }

    @Test
    fun all_preservesInsertionOrder() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(id = "z"))
        engine.upsert(rule(id = "a"))
        engine.upsert(rule(id = "m"))
        assertEquals(listOf("z", "a", "m"), engine.all().map { it.id })
    }

    @Test
    fun rule_isActive_reflectsConfiguredFaults() {
        assertFalse(rule().isActive)
        assertTrue(rule(latencyMs = 500).isActive)
        assertTrue(rule(statusCode = 500).isActive)
        assertTrue(rule(dropConnection = true).isActive)
        assertTrue(rule(mockBody = "{}").isActive)
    }

    // ================================================================
    // Matching
    // ================================================================

    @Test
    fun match_globStar_matchesAnyPath() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(urlPattern = "https://api.example.com/*"))
        assertTrue(engine.hasActiveRuleFor("https://api.example.com/items", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://api.example.com/v2/order?id=1", "POST"))
        assertFalse(engine.hasActiveRuleFor("https://other.example.com/items", "GET"))
    }

    @Test
    fun match_methodFilter_restrictsByMethod() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(method = "POST"))
        assertNull(engine.match("https://api.example.com/items", "GET"))
        assertEquals("rule-1", engine.match("https://api.example.com/items", "post")?.id)
    }

    @Test
    fun matches_caseInsensitiveMethod_evaluatesGlob() {
        assertTrue(
            FaultInterceptorEngine.matches(
                rule(urlPattern = "https://a.com/*", method = "POST"),
                "https://a.com/",
                "post",
            )
        )
        assertTrue(FaultInterceptorEngine.globMatch("https://a.com/*", "https://a.com/x/y"))
        assertTrue(FaultInterceptorEngine.globMatch("*/items?id=?", "https://api.example.com/items?id=1"))
        assertFalse(FaultInterceptorEngine.globMatch("*/items?id=?", "https://api.example.com/items?id=12"))
    }

    @Test
    fun match_noRuleReturnsNull() {
        val engine = FaultInterceptorEngine()
        assertNull(engine.match("https://api.example.com/items", "GET"))
        assertFalse(engine.hasActiveRuleFor("https://api.example.com/items", "GET"))
    }

    // ================================================================
    // Decisions
    // ================================================================

    @Test
    fun decisionFor_noMatch_continuesWithoutDelay() {
        val engine = FaultInterceptorEngine()
        val decision = engine.decisionFor("https://api.example.com/items", "GET")
        assertEquals(FaultAction.CONTINUE, decision.action)
        assertEquals(0, decision.delayMs)
        assertNull(decision.statusCode)
        assertNull(decision.body)
        assertFalse(decision.shouldDelay)
    }

    @Test
    fun decisionFor_latencyRule_continueWithDelay() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(latencyMs = 2_500))
        val decision = engine.decisionFor("https://api.example.com/items", "GET")
        assertEquals(FaultAction.CONTINUE, decision.action)
        assertEquals(2_500, decision.delayMs)
        assertTrue(decision.shouldDelay)
    }

    @Test
    fun decisionFor_statusOverride_forcesStatusCode() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(statusCode = 503, latencyMs = 1_000))
        val decision = engine.decisionFor("https://api.example.com/items", "GET")
        assertEquals(FaultAction.OVERRIDE, decision.action)
        assertEquals(503, decision.statusCode)
        assertEquals(1_000, decision.delayMs)
        assertTrue(decision.shouldDelay)
    }

    @Test
    fun decisionFor_mockBody_defaultsTo200() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(mockBody = """{"ok":false}"""))
        val decision = engine.decisionFor("https://api.example.com/items", "GET")
        assertEquals(FaultAction.OVERRIDE, decision.action)
        assertEquals(200, decision.statusCode)
        assertEquals("""{"ok":false}""", decision.body)
    }

    @Test
    fun decisionFor_dropConnection_dropsImmediately() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(dropConnection = true, latencyMs = 2_000))
        val decision = engine.decisionFor("https://api.example.com/items", "GET")
        assertEquals(FaultAction.DROP, decision.action)
        assertFalse("drop must not sleep before failing", decision.shouldDelay)
    }

    @Test
    fun decisionFor_priority_isFirstInserted() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(id = "first", urlPattern = "https://api.example.com/*", statusCode = 400))
        engine.upsert(rule(id = "second", urlPattern = "https://api.example.com/*", statusCode = 503))
        assertEquals("first", engine.match("https://api.example.com/items", "GET")?.id)
        assertEquals(400, engine.decisionFor("https://api.example.com/items", "GET").statusCode)
    }

    @Test
    fun decisionFor_afterRemoval_continues() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(id = "a", statusCode = 500))
        engine.remove("a")
        assertEquals(FaultAction.CONTINUE, engine.decisionFor("https://api.example.com/items", "GET").action)
    }

    @Test
    fun decisionFor_combinedRule_appliesDelayWithOverride() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(latencyMs = 3_500, statusCode = 503, mockBody = """{"down":true}"""))
        val decision = engine.decisionFor("https://api.example.com/items", "GET")
        assertEquals(FaultAction.OVERRIDE, decision.action)
        assertEquals(3_500, decision.delayMs)
        assertEquals(503, decision.statusCode)
        assertEquals("""{"down":true}""", decision.body)
    }

    @Test
    fun summary_describesConfiguredFaults() {
        assertEquals("", rule().summary)
        assertEquals("+500ms", rule(latencyMs = 500).summary)
        assertEquals("+500ms · drop", rule(latencyMs = 500, dropConnection = true).summary)
        assertEquals("+500ms · HTTP 500 · mock body", rule(
            latencyMs = 500,
            statusCode = 500,
            mockBody = "{}",
        ).summary)
        assertEquals("HTTP 404", rule(statusCode = 404).summary)
    }

    @Test(expected = IllegalArgumentException::class)
    fun faultRule_enforcesMutualExclusivity_dropAndStatus() {
        rule(statusCode = 404, dropConnection = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun faultRule_enforcesMutualExclusivity_dropAndMockBody() {
        rule(dropConnection = true, mockBody = """{"error": true}""")
    }

    @Test
    fun decisionFor_http404Override() {
        val engine = FaultInterceptorEngine()
        engine.upsert(rule(id = "not-found", statusCode = 404))
        val decision = engine.decisionFor("https://api.example.com/items", "GET")
        assertEquals(FaultAction.OVERRIDE, decision.action)
        assertEquals(404, decision.statusCode)
        assertNull(decision.body)
    }

    @Test
    fun exactUrlMatching_withQueryParams() {
        val engine = FaultInterceptorEngine()
        val exactUrl = "https://c2.piano.io/xbuilder/experience/execute?client=123&ts=999"
        engine.upsert(rule(id = "piano", urlPattern = exactUrl, statusCode = 404))

        // Same URL with exact params matches
        assertTrue(engine.hasActiveRuleFor(exactUrl, "GET"))
        assertEquals(404, engine.decisionFor(exactUrl, "GET").statusCode)

        // Same endpoint with different dynamic query param fails to match exact pattern
        val differentTsUrl = "https://c2.piano.io/xbuilder/experience/execute?client=123&ts=1000"
        assertFalse(engine.hasActiveRuleFor(differentTsUrl, "GET"))
        assertEquals(FaultAction.CONTINUE, engine.decisionFor(differentTsUrl, "GET").action)

        // Wildcarded pattern matches any query params
        val wildcardUrl = "https://c2.piano.io/xbuilder/experience/execute?*"
        engine.upsert(rule(id = "piano-wildcard", urlPattern = wildcardUrl, statusCode = 404))
        assertTrue(engine.hasActiveRuleFor(differentTsUrl, "GET"))
    }

    @Test
    fun globMatch_malformedPattern_doesNotThrow() {
        // Unclosed regex constructs or malformed patterns must not crash the matcher
        val safe = FaultInterceptorEngine.globMatch("[unclosed", "test")
        assertFalse(safe)
        assertTrue(FaultInterceptorEngine.globMatch("[unclosed", "[unclosed"))
    }

    @Test
    fun restorationOfNormalRequests_afterRuleRemoval() {
        val engine = FaultInterceptorEngine()
        val url = "https://api.example.com/data"
        engine.upsert(rule(id = "temp-rule", urlPattern = url, statusCode = 500))

        // Active rule forces 500
        assertEquals(FaultAction.OVERRIDE, engine.decisionFor(url, "GET").action)
        assertEquals(500, engine.decisionFor(url, "GET").statusCode)

        // Remove rule restores CONTINUE without delay
        assertTrue(engine.remove("temp-rule"))
        val restored = engine.decisionFor(url, "GET")
        assertEquals(FaultAction.CONTINUE, restored.action)
        assertEquals(0, restored.delayMs)
        assertNull(restored.statusCode)
    }

    // ================================================================
    // Host-Only Origin-Wide Matching vs Page-Specific Rules
    // ================================================================

    @Test
    fun hostOnlyRule_matchesOriginWideIncludingRedirectedSubpaths() {
        val engine = FaultInterceptorEngine()
        val rule = rule(id = "wiki-origin", urlPattern = "wikipedia.org", dropConnection = true)
        engine.upsert(rule)

        // Matches apex domain under https and http
        assertTrue(engine.hasActiveRuleFor("https://wikipedia.org", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://wikipedia.org/", "GET"))
        assertTrue(engine.hasActiveRuleFor("http://wikipedia.org", "GET"))

        // Matches subdomains (e.g. www.wikipedia.org, en.wikipedia.org)
        assertTrue(engine.hasActiveRuleFor("https://www.wikipedia.org/", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://www.wikipedia.org/wiki/Main_Page", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://en.wikipedia.org/wiki/Kotlin", "GET"))

        // Does NOT match unrelated domains
        assertFalse(engine.hasActiveRuleFor("https://notwikipedia.org/", "GET"))
        assertFalse(engine.hasActiveRuleFor("https://example.com/wiki/Main_Page", "GET"))
    }

    @Test
    fun hostOnlyRule_withSchemeAndSlash_matchesOriginWide() {
        val engine = FaultInterceptorEngine()
        val rule = rule(id = "www-wiki", urlPattern = "https://www.wikipedia.org/", dropConnection = true)
        engine.upsert(rule)

        assertTrue(engine.hasActiveRuleFor("https://www.wikipedia.org", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://www.wikipedia.org/", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://www.wikipedia.org/wiki/Main_Page", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://www.wikipedia.org/static/logo.png", "GET"))

        // Does NOT match http (scheme mismatch) or other hosts
        assertFalse(engine.hasActiveRuleFor("http://www.wikipedia.org/", "GET"))
        assertFalse(engine.hasActiveRuleFor("https://example.com/", "GET"))
    }

    @Test
    fun pageSpecificRule_preservesExactMatchingWithoutAffectingUnrelatedPages() {
        val engine = FaultInterceptorEngine()
        val rule = rule(id = "specific-page", urlPattern = "https://example.com/api/users", statusCode = 404)
        engine.upsert(rule)

        // Exact page matches
        assertTrue(engine.hasActiveRuleFor("https://example.com/api/users", "GET"))
        assertTrue(engine.hasActiveRuleFor("https://example.com/api/users/", "GET"))

        // Unrelated pages on same host are NOT affected
        assertFalse(engine.hasActiveRuleFor("https://example.com/api/posts", "GET"))
        assertFalse(engine.hasActiveRuleFor("https://example.com/", "GET"))
        assertFalse(engine.hasActiveRuleFor("https://example.com/dashboard", "GET"))
    }
}