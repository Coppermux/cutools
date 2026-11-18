package com.coppermux.cutools.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe, in-memory registry of [MockResponseRuleSpec]s, and the rule
 * resolution point for paused `Fetch.requestPaused` events.
 *
 * Matching is deterministic:
 *  1. Only [MockResponseRuleSpec.enabled] rules participate.
 *  2. A rule matches when its [MockResponseRuleSpec.method] (if present) equals
 *     the request method case-insensitively AND its [MockResponseRuleSpec.urlPattern]
 *     glob-matches the request URL.
 *  3. Among matching rules the highest [MockResponseRuleSpec.priority] wins;
 *     ties are broken by insertion order (earliest added first).
 *
 * No rule is ever fabricated: a request with no matching rule resolves to null,
 * which callers treat as pass-through (`Fetch.continueRequest`).
 */
class MockResponseRuleManager {

    private val byId = ConcurrentHashMap<String, MockResponseRuleSpec>()
    private val order = CopyOnWriteArrayList<String>()

    /** Inserts a new rule, or replaces the existing rule with the same id. */
    fun upsert(rule: MockResponseRuleSpec) {
        if (byId.put(rule.id, rule) == null) {
            order.add(rule.id)
        }
    }

    /** Removes the rule with [id]. Returns true if it existed. */
    fun remove(id: String): Boolean {
        val removed = byId.remove(id) != null
        if (removed) order.remove(id)
        return removed
    }

    /** Removes all rules. */
    fun clear() {
        byId.clear()
        order.clear()
    }

    /** The rule with [id], or null. */
    fun find(id: String): MockResponseRuleSpec? = byId[id]

    /** Number of registered rules. */
    fun size(): Int = byId.size

    /** Immutable snapshot in insertion order. */
    fun all(): List<MockResponseRuleSpec> = order.mapNotNull { byId[it] }

    /**
     * Resolves the best matching enabled rule for a paused request described by
     * [url] and [method], or null when no enabled rule matches (pass-through).
     */
    fun match(url: String, method: String): MockResponseRuleSpec? =
        all()
            .filter { it.enabled && matches(it, url, method) }
            .sortedWith(compareBy { -it.priority })
            .firstOrNull()

    private fun matches(rule: MockResponseRuleSpec, url: String, method: String): Boolean {
        if (rule.method != null && !rule.method.equals(method, ignoreCase = true)) return false
        if (globMatch(rule.urlPattern, url)) return true
        if (rule.urlPattern.endsWith("/") && !url.endsWith("/")) {
            if (globMatch(rule.urlPattern.removeSuffix("/"), url)) return true
        } else if (!rule.urlPattern.endsWith("/") && url.endsWith("/")) {
            if (globMatch(rule.urlPattern + "/", url)) return true
        }
        return false
    }

    /** Glob matching: `*` matches any run of characters, `?` a single character. */
    internal fun globMatch(pattern: String, value: String): Boolean {
        val regex = Regex(globToRegex(pattern))
        return regex.matches(value)
    }

    private fun globToRegex(pattern: String): String {
        val sb = StringBuilder("^")
        pattern.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> {
                    if (".$^{}[]()|+\\".contains(c.toString())) sb.append('\\')
                    sb.append(c)
                }
            }
        }
        sb.append('$')
        return sb.toString()
    }
}