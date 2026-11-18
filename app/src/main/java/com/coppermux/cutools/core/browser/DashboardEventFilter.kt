package com.coppermux.cutools.core.browser

/**
 * Phase 18 — pure filtering of the dashboard event stream.
 *
 * Applies the dashboard filter bar chips ([DashboardFilter]) and the live
 * search query over the underlying event collection. This is deterministic,
 * session-independent presentation logic: it never mutates the underlying
 * event list and never triggers any WebView or CDP command.
 */
object DashboardEventFilter {

    /**
     * Returns the events matching [filter] AND [searchQuery]. [ALL] is the
     * complete chronological feed; the remaining chips are strict subsets.
     */
    fun apply(
        events: List<DashboardEvent>,
        filter: DashboardFilter,
        searchQuery: String,
    ): List<DashboardEvent> {
        val query = searchQuery.trim()
        return events.filter { event ->
            matchesFilter(event, filter) && matchesSearch(event, query)
        }
    }

    /**
     * Filter bar chip semantics:
     *  - [DashboardFilter.ALL]: full chronological feed.
     *  - [DashboardFilter.NETWORK]: document / asset / script network requests,
     *    explicitly excluding XHR/Fetch (which have their own chip).
     *  - [DashboardFilter.XHR_FETCH]: async API calls only.
     *  - [DashboardFilter.CONSOLE]: in-page JS console messages and exceptions.
     *  - [DashboardFilter.WARNINGS]: console warnings/deprecations + HTTP 3xx.
     *  - [DashboardFilter.ERRORS]: console errors, JS exceptions, HTTP 4xx/5xx,
     *    and network failures.
     */
    fun matchesFilter(event: DashboardEvent, filter: DashboardFilter): Boolean = when (filter) {
        DashboardFilter.ALL -> true
        DashboardFilter.NETWORK -> event.isNetwork && !event.isXhrFetch
        DashboardFilter.XHR_FETCH -> event.isXhrFetch
        DashboardFilter.CONSOLE -> event.isConsole
        DashboardFilter.WARNINGS -> event.isWarning
        DashboardFilter.ERRORS -> event.isError
    }

    /** Live search: case-insensitive substring over URL, message, badge, detail. */
    fun matchesSearch(event: DashboardEvent, query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isEmpty()) return true
        val needle = normalized.lowercase()
        fun contains(text: String?): Boolean = text?.lowercase()?.contains(needle) == true
        return contains(event.url) ||
            contains(event.message) ||
            contains(event.kind) ||
            contains(event.detail) ||
            contains(event.statusText)
    }

    /**
     * True when any filter or search restriction is active (used by the UI to
     * show that the feed is filtered).
     */
    fun isActive(filter: DashboardFilter, searchQuery: String): Boolean =
        filter != DashboardFilter.ALL || searchQuery.isNotBlank()
}