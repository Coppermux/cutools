package com.coppermux.cutools.core.console

/**
 * Severity filter over the structured Console entries.
 *
 * [severities] is the set of severities to show; an EMPTY set means "all
 * severities" (the filter is inactive for severity). [includeRuntimeExceptions]
 * independently controls whether runtime-exception entries (kind
 * [ConsoleEntryKind.RUNTIME_EXCEPTION]) are shown.
 *
 * Filtering affects presentation/state only: it never deletes or mutates the
 * underlying [ConsoleEntry] collection.
 */
data class ConsoleSeverityFilter(
    val severities: Set<ConsoleSeverity> = emptySet(),
    val includeRuntimeExceptions: Boolean = true,
) {
    /** Whether no filtering is applied (all severities + runtime exceptions shown). */
    val isAll: Boolean get() = severities.isEmpty() && includeRuntimeExceptions

    /** Whether any filtering is active. */
    val isActive: Boolean get() = !isAll

    /** Returns a filter with [severity] included/excluded. */
    fun withSeverity(severity: ConsoleSeverity, enabled: Boolean): ConsoleSeverityFilter =
        if (enabled) copy(severities = severities + severity)
        else copy(severities = severities - severity)

    /** Returns a filter with runtime-exception visibility set. */
    fun withRuntimeExceptions(enabled: Boolean): ConsoleSeverityFilter =
        copy(includeRuntimeExceptions = enabled)

    /** Clears all severity filtering (back to showing everything). */
    fun clear(): ConsoleSeverityFilter = ConsoleSeverityFilter()

    companion object {
        fun all(): ConsoleSeverityFilter = ConsoleSeverityFilter()
    }
}

/**
 * Pure, deterministic search + severity filtering over Console entries.
 *
 * Search is a CuTools presentation/state convenience: it operates on the
 * structured entries already in Console state, never fabricates browser
 * messages, never triggers browser commands, and never mutates the underlying
 * Console state or browser history.
 */
object ConsoleEntryFilter {

    /** Whether [entry] passes the severity filter. */
    fun severityPasses(entry: ConsoleEntry, filter: ConsoleSeverityFilter): Boolean {
        if (entry.kind == ConsoleEntryKind.RUNTIME_EXCEPTION && !filter.includeRuntimeExceptions) return false
        if (filter.severities.isEmpty()) return true
        return entry.severity in filter.severities
    }

    /**
     * The meaningful, searchable content of [entry] derived from the structured
     * model (message, source, location, stack, arguments, helper/origin, and
     * evaluation expression). Used only for CuTools presentation search.
     */
    fun searchableText(entry: ConsoleEntry): String = buildString {
        append(entry.displayText)
        append(' ')
        entry.source?.let { append(it).append(' ') }
        entry.helperLabel?.let { append(it).append(' ') }
        append(entry.origin.displayLabel)
        append(' ')
        entry.exceptionDescription?.let { append(it).append(' ') }
        entry.location?.url?.let { append(it).append(' ') }
        entry.evaluation?.request?.expression?.let { append(it).append(' ') }
        entry.args.forEach { arg ->
            arg.description?.let { append(it).append(' ') }
            arg.className?.let { append(it).append(' ') }
        }
        entry.stackTrace?.let { appendStack(it) }
        entry.evaluation?.let { outcome ->
            val details = (outcome.result as? com.coppermux.cutools.core.runtime.EvaluationResultKind.Exception)?.details
            details?.stackTrace?.let { appendStack(it) }
            details?.url?.let { append(it).append(' ') }
        }
    }

    private fun StringBuilder.appendStack(trace: com.coppermux.cutools.core.console.ConsoleStackTrace) {
        trace.callFrames.forEach { frame ->
            frame.url?.let { append(it).append(' ') }
            frame.functionName?.let { append(it).append(' ') }
        }
        trace.parent?.let { appendStack(it) }
    }

    private fun StringBuilder.appendStack(trace: com.coppermux.cutools.core.runtime.EvaluationStackTrace) {
        trace.callFrames.forEach { frame ->
            frame.url?.let { append(it).append(' ') }
            frame.functionName?.let { append(it).append(' ') }
        }
        trace.parent?.let { appendStack(it) }
    }

    /** Whether [entry] matches the search [query] (case-insensitive substring). */
    fun searchPasses(entry: ConsoleEntry, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return searchableText(entry).contains(q, ignoreCase = true)
    }

    /** Whether [entry] satisfies both the search [query] and the [filter]. */
    fun matches(entry: ConsoleEntry, query: String, filter: ConsoleSeverityFilter): Boolean =
        severityPasses(entry, filter) && searchPasses(entry, query)

    /**
     * Applies the search + severity filter to [entries], preserving order and
     * never mutating the input collection. The result is the intersection of
     * the search matches and the severity-filtered set.
     */
    fun apply(
        entries: List<ConsoleEntry>,
        query: String,
        filter: ConsoleSeverityFilter,
    ): List<ConsoleEntry> = entries.filter { matches(it, query, filter) }
}