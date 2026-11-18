package com.coppermux.cutools.core.browser

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 19 — real-time network fault interceptor engine.
 *
 * Thread-safe, in-memory registry of active [FaultRule]s and the decision
 * point [WebViewEngine.shouldInterceptRequest] consults before letting a
 * request through. Matching is deterministic:
 *  1. A rule matches when its [FaultRule.method] (if present) equals the
 *     request method case-insensitively AND its [FaultRule.urlPattern]
 *     glob-matches the request URL.
 *  2. Among matching rules the earliest inserted wins (rules target distinct
 *     endpoints in the in-card editor, so this ordering is stable and
 *     predictable).
 *
 * [decisionFor] maps the matched rule onto a [FaultDecision]:
 *  - drop rules → [FaultAction.DROP] (simulated socket failure).
 *  - status/mock-body rules → [FaultAction.OVERRIDE] with the forced status
 *    (200 when only a mock body is set) and mock body.
 *  - pure latency rules → [FaultAction.CONTINUE] carrying the delay, which the
 *    interceptor applies before returning null so WebView still loads the real
 *    resource.
 *
 * The engine is pure JVM — no Android framework dependency — so every
 * matching/decision rule is unit-testable. It never fabricates a decision:
 * a request with no matching rule resolves to [FaultAction.CONTINUE] with no
 * delay, and the WebView loads the real resource untouched.
 */
class FaultInterceptorEngine {

    private val byId = ConcurrentHashMap<String, FaultRule>()
    private val order = CopyOnWriteArrayList<String>()

    /** Listener for active fault rule set changes. */
    fun interface Listener {
        fun onRulesChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l.onRulesChanged()
            } catch (_: Throwable) {
            }
        }
    }

    /** Returns all active drop rules (rules configured to drop connection). */
    fun activeDropRules(): List<FaultRule> =
        all().filter { it.dropConnection }

    /** Inserts a new rule, or replaces the existing rule with the same id. */
    fun upsert(rule: FaultRule) {
        if (byId.put(rule.id, rule) == null) {
            order.add(rule.id)
        }
        notifyChanged()
    }

    /** Removes the rule with [id]. Returns true if it existed. */
    fun remove(id: String): Boolean {
        val removed = byId.remove(id) != null
        if (removed) {
            order.remove(id)
            notifyChanged()
        }
        return removed
    }

    /** Removes all active fault rules. */
    fun clear() {
        val hadRules = byId.isNotEmpty()
        byId.clear()
        order.clear()
        if (hadRules) {
            notifyChanged()
        }
    }

    /** The rule with [id], or null. */
    fun find(id: String): FaultRule? = byId[id]

    /** Number of registered fault rules. */
    fun size(): Int = byId.size

    /** Immutable snapshot of all active rules in insertion order. */
    fun all(): List<FaultRule> = order.mapNotNull { byId[it] }

    /**
     * The best matching active rule for an outgoing request described by [url]
     * and [method], or null when no rule targets it.
     */
    fun match(url: String, method: String): FaultRule? =
        all().firstOrNull { matches(it, url, method) }

    /** Whether any active rule targets the outgoing request. */
    fun hasActiveRuleFor(url: String, method: String): Boolean = match(url, method) != null

    /**
     * The decision to apply to an outgoing request. Never throws and never
     * fabricates an action for unmatched requests.
     */
    fun decisionFor(url: String, method: String): FaultDecision {
        val rule = match(url, method) ?: return FaultDecision()
        return when {
            rule.dropConnection -> FaultDecision(
                action = FaultAction.DROP,
                delayMs = rule.latencyMs,
            )
            rule.statusCode != null || !rule.mockBody.isNullOrBlank() -> FaultDecision(
                action = FaultAction.OVERRIDE,
                delayMs = rule.latencyMs,
                statusCode = rule.statusCode ?: 200,
                body = rule.mockBody,
            )
            else -> FaultDecision(action = FaultAction.CONTINUE, delayMs = rule.latencyMs)
        }
    }

    companion object {

        private val regexCache = ConcurrentHashMap<String, Regex>()

        /** Whether [rule] targets the outgoing [url]/[method] pair. */
        fun matches(rule: FaultRule, url: String, method: String): Boolean {
            if (rule.method != null && !rule.method.equals(method, ignoreCase = true)) return false
            if (globMatch(rule.urlPattern, url)) return true
            // Also tolerate trailing slash differences: e.g. https://example.com vs https://example.com/
            if (rule.urlPattern.endsWith("/") && !url.endsWith("/")) {
                if (globMatch(rule.urlPattern.removeSuffix("/"), url)) return true
            } else if (!rule.urlPattern.endsWith("/") && url.endsWith("/")) {
                if (globMatch(rule.urlPattern + "/", url)) return true
            }

            // Explicitly entered host-only rules support origin-wide matching, including redirected subpaths
            if (isHostOnlyPattern(rule.urlPattern)) {
                val (ruleScheme, ruleHost) = extractHostAndScheme(rule.urlPattern)
                val reqScheme = extractScheme(url)
                val reqHost = extractHost(url)
                if (reqHost != null) {
                    val schemeMatches = ruleScheme == null || ruleScheme.equals(reqScheme, ignoreCase = true)
                    val hostMatches = reqHost.equals(ruleHost, ignoreCase = true) || reqHost.endsWith(".$ruleHost", ignoreCase = true)
                    if (schemeMatches && hostMatches) {
                        return true
                    }
                }
            }

            // Normalize bare hostname URL patterns without broadening exact page rules to entire domains
            val p = rule.urlPattern.trim()
            if (!p.contains("://") && !p.startsWith("*") && !p.startsWith("?")) {
                for (scheme in listOf("https://", "http://")) {
                    val candidate = scheme + p
                    if (globMatch(candidate, url)) return true
                    if (candidate.endsWith("/") && !url.endsWith("/")) {
                        if (globMatch(candidate.removeSuffix("/"), url)) return true
                    } else if (!candidate.endsWith("/") && url.endsWith("/")) {
                        if (globMatch("$candidate/", url)) return true
                    }
                }
            }
            return false
        }

        /**
         * Determines if [pattern] is a host-only pattern (no glob wildcards and no subpath beyond optional trailing slash).
         */
        fun isHostOnlyPattern(pattern: String): Boolean {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty() || trimmed.contains('*') || trimmed.contains('?')) return false
            val withoutScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
                trimmed.startsWith("//") -> trimmed.substring(2)
                else -> trimmed
            }
            val hostPart = withoutScheme.trimEnd('/')
            if (hostPart.isEmpty()) return false
            return !hostPart.contains('/') && !hostPart.contains('?') && !hostPart.contains('#')
        }

        /**
         * Extracts scheme (if present) and normalized host from a host-only pattern.
         */
        fun extractHostAndScheme(pattern: String): Pair<String?, String> {
            val trimmed = pattern.trim()
            val scheme: String? = when {
                trimmed.startsWith("https://", ignoreCase = true) -> "https"
                trimmed.startsWith("http://", ignoreCase = true) -> "http"
                else -> null
            }
            val withoutScheme = when {
                scheme != null -> trimmed.substring(scheme.length + 3)
                trimmed.startsWith("//") -> trimmed.substring(2)
                else -> trimmed
            }
            val hostPart = withoutScheme.trimEnd('/').substringBefore(':').lowercase()
            return Pair(scheme, hostPart)
        }

        fun extractScheme(url: String): String? {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> "https"
                trimmed.startsWith("http://", ignoreCase = true) -> "http"
                else -> null
            }
        }

        fun extractHost(url: String): String? {
            val trimmed = url.trim()
            val withoutScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
                trimmed.startsWith("//") -> trimmed.substring(2)
                else -> trimmed
            }
            val host = withoutScheme.substringBefore('/').substringBefore(':').trim().lowercase()
            return host.takeIf { it.isNotEmpty() }
        }

        /** Glob matching: `*` matches any run of characters, `?` a single character. */
        fun globMatch(pattern: String, value: String): Boolean {
            return try {
                if (regexCache.size > 500) {
                    regexCache.clear()
                }
                val regex = regexCache.computeIfAbsent(pattern) { p ->
                    Regex(globToRegex(p))
                }
                regex.matches(value)
            } catch (_: Exception) {
                // Safe fallback for malformed patterns: exact string equality
                pattern.equals(value, ignoreCase = true)
            }
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
}