package com.coppermux.cutools.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for Phase 5C rule management: glob pattern matching,
 * method filtering, enablement, and deterministic rule prioritization in the
 * in-memory [MockResponseRuleManager].
 */
class MockResponseRuleManagerTest {

    private val manager = MockResponseRuleManager()

    private fun rule(
        id: String,
        urlPattern: String,
        method: String? = null,
        enabled: Boolean = true,
        priority: Int = 0,
        action: MockAction = MockAction.FULFILL,
    ) = MockResponseRuleSpec(
        id = id,
        urlPattern = urlPattern,
        method = method,
        enabled = enabled,
        priority = priority,
        action = action,
    )

    // ============ matching ============

    @Test
    fun exactUrlPattern_matchesRequestUrl() {
        manager.upsert(rule("r1", "https://api.example.com/users"))
        val matched = manager.match("https://api.example.com/users", "GET")
        assertEquals("r1", matched?.id)
    }

    @Test
    fun trailingGlob_matchesAnyPath() {
        manager.upsert(rule("r1", "https://api.example.com/*"))
        assertTrue(manager.match("https://api.example.com/users", "GET")?.id == "r1")
        assertTrue(manager.match("https://api.example.com/users/123/posts", "GET")?.id == "r1")
    }

    @Test
    fun embeddedGlob_matchesAnyUrlSegment() {
        manager.upsert(rule("r1", "https://*.example.com/*"))
        assertTrue(manager.match("https://api.example.com/users", "GET")?.id == "r1")
        assertTrue(manager.match("https://cdn.example.com/assets/app.js", "GET")?.id == "r1")
    }

    @Test
    fun questionMark_matchesSingleCharacter() {
        manager.upsert(rule("r1", "https://api.example.com/user?"))
        assertTrue(manager.match("https://api.example.com/user1", "GET")?.id == "r1")
        assertNull(manager.match("https://api.example.com/user12", "GET"))
    }

    @Test
    fun regexSpecialCharacters_areTreatedLiterally() {
        manager.upsert(rule("r1", "https://api.example.com/v1.2/items"))
        val matched = manager.match("https://api.example.com/v1.2/items", "GET")
        assertEquals("r1", matched?.id)
        // A dot must not act as "any char" — a different char in that position fails.
        assertNull(manager.match("https://api.example.com/v1X2/items", "GET"))
    }

    @Test
    fun noMatch_returnsNull() {
        manager.upsert(rule("r1", "https://api.example.com/*"))
        assertNull(manager.match("https://other.example.com/users", "GET"))
    }

    @Test
    fun emptyUrlPattern_matchesEverything() {
        manager.upsert(rule("r1", "*"))
        assertTrue(manager.match("https://anything.example.com/x", "GET")?.id == "r1")
        assertTrue(manager.match("data:image/png;base64,xxx", "GET")?.id == "r1")
    }

    // ============ method filtering ============

    @Test
    fun methodFilter_restrictsToRequestMethod() {
        manager.upsert(rule("get", "https://api.example.com/*", method = "GET"))
        manager.upsert(rule("post", "https://api.example.com/*", method = "POST"))
        assertTrue(manager.match("https://api.example.com/users", "GET")?.id == "get")
        assertTrue(manager.match("https://api.example.com/users", "POST")?.id == "post")
    }

    @Test
    fun methodFilter_isCaseInsensitive() {
        manager.upsert(rule("r1", "https://api.example.com/*", method = "get"))
        assertTrue(manager.match("https://api.example.com/users", "GET")?.id == "r1")
    }

    @Test
    fun nullMethod_matchesAnyMethod() {
        manager.upsert(rule("r1", "https://api.example.com/*"))
        assertTrue(manager.match("https://api.example.com/users", "DELETE")?.id == "r1")
    }

    // ============ enablement ============

    @Test
    fun disabledRule_neverMatches() {
        manager.upsert(rule("r1", "https://api.example.com/*", enabled = false))
        assertNull(manager.match("https://api.example.com/users", "GET"))
    }

    @Test
    fun disableViaUpsert_surfacesInManager() {
        manager.upsert(rule("r1", "https://api.example.com/*"))
        manager.upsert(rule("r1", "https://api.example.com/*", enabled = false))
        assertFalse(manager.find("r1")!!.enabled)
        assertNull(manager.match("https://api.example.com/users", "GET"))
    }

    // ============ prioritization ============

    @Test
    fun higherPriorityRule_winsOverLowerPriority() {
        manager.upsert(rule("low", "https://api.example.com/*", priority = 1))
        manager.upsert(rule("high", "https://api.example.com/users", priority = 100))
        val matched = manager.match("https://api.example.com/users", "GET")
        assertEquals("high", matched?.id)
    }

    @Test
    fun equalPriority_tieBrokenByInsertionOrder() {
        manager.upsert(rule("first", "https://api.example.com/users"))
        manager.upsert(rule("second", "https://api.example.com/users"))
        assertEquals("first", manager.match("https://api.example.com/users", "GET")?.id)
    }

    @Test
    fun upsertReplace_keepsOriginalInsertionPosition() {
        manager.upsert(rule("first", "https://api.example.com/users"))
        manager.upsert(rule("second", "https://api.example.com/users"))
        manager.upsert(rule("first", "https://api.example.com/users", priority = 5))
        // "first" still precedes "second"; equal priority → insertion order wins.
        assertEquals("first", manager.match("https://api.example.com/users", "GET")?.id)
    }

    @Test
    fun higherPriorityRule_regardlessOfInsertionOrder() {
        manager.upsert(rule("second", "https://api.example.com/*", priority = 1))
        manager.upsert(rule("first", "https://api.example.com/*", priority = 10))
        assertEquals("first", manager.match("https://api.example.com/users", "GET")?.id)
    }

    // ============ collection management ============

    @Test
    fun remove_deletesRule() {
        manager.upsert(rule("r1", "https://api.example.com/*"))
        assertTrue(manager.remove("r1"))
        assertFalse(manager.remove("r1"))
        assertEquals(0, manager.size())
        assertNull(manager.match("https://api.example.com/users", "GET"))
    }

    @Test
    fun clear_removesAllRules() {
        manager.upsert(rule("r1", "https://api.example.com/*"))
        manager.upsert(rule("r2", "https://other.example.com/*"))
        manager.clear()
        assertEquals(0, manager.size())
        assertTrue(manager.all().isEmpty())
    }

    @Test
    fun all_returnsSnapshotInInsertionOrder() {
        manager.upsert(rule("a", "https://a.example.com/*"))
        manager.upsert(rule("b", "https://b.example.com/*"))
        manager.upsert(rule("c", "https://c.example.com/*"))
        assertEquals(listOf("a", "b", "c"), manager.all().map { it.id })
    }
}