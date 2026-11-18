package com.coppermux.cutools.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 18 — pure unit tests for the dashboard filter chip bar semantics and
 * the live search query. All assertions are deterministic JVM tests over the
 * pure [DashboardEventFilter].
 */
class DashboardEventFilterTest {

    // A representative spread of intercepted events.
    private val document = DashboardEventClassifier.buildDocumentFinishedEvent(
        url = "https://example.com/", timestamp = 1_000, durationMs = 80,
    )
    private val stylesheet = DashboardEventClassifier.buildNetworkRequestEvent(
        url = "https://example.com/site.css", method = "GET", isForMainFrame = false, timestamp = 2_000,
    )
    private val script = DashboardEventClassifier.buildNetworkRequestEvent(
        url = "https://example.com/app.js", method = "GET", isForMainFrame = false, timestamp = 3_000,
    )
    private val xhr = DashboardEventClassifier.buildNetworkRequestEvent(
        url = "https://api.example.com/items", method = "POST", isForMainFrame = false, timestamp = 4_000,
    )
    private val redirect = DashboardEventClassifier.buildNetworkRequestEvent(
        url = "https://example.com/old", method = "GET", isForMainFrame = true,
        timestamp = 5_000, isRedirect = true,
    )
    private val httpError = DashboardEventClassifier.buildHttpErrorEvent(
        url = "https://example.com/missing", method = "GET",
        statusCode = 404, statusText = "Not Found", timestamp = 6_000,
    )
    private val networkFailure = DashboardEventClassifier.buildNetworkFailureEvent(
        url = "https://example.com/", detail = "net::ERR_TIMED_OUT", timestamp = 7_000,
    )
    private val consoleLog = DashboardEventClassifier.buildConsoleEvent(
        level = "log", message = "page mounted", timestamp = 8_000,
        sourceUrl = "https://example.com/app.js",
    )
    private val consoleWarn = DashboardEventClassifier.buildConsoleEvent(
        level = "warning", message = "deprecated call", timestamp = 9_000,
    )
    private val consoleError = DashboardEventClassifier.buildConsoleEvent(
        level = "error", message = "exploded", timestamp = 10_000,
    )
    private val jsException = DashboardEventClassifier.buildRuntimeExceptionEvent(
        message = "ReferenceError: x is not defined", timestamp = 11_000,
    )

    private val feed = listOf(
        document, stylesheet, script, xhr, redirect, httpError,
        networkFailure, consoleLog, consoleWarn, consoleError, jsException,
    )

    private fun visible(filter: DashboardFilter, query: String = ""): List<DashboardEvent> =
        DashboardEventFilter.apply(feed, filter, query)

    @Test
    fun allFilter_returnsCompleteChronologicalFeed() {
        assertEquals(feed, visible(DashboardFilter.ALL))
    }

    @Test
    fun networkFilter_excludesXhrFetch() {
        val result = visible(DashboardFilter.NETWORK)
        assertTrue(result.contains(document))
        assertTrue(result.contains(stylesheet))
        assertTrue(result.contains(script))
        assertFalse(result.contains(xhr))
        assertEquals(feed.count { it.isNetwork && !it.isXhrFetch }, result.size)
    }

    @Test
    fun xhrFetchFilter_returnsOnlyAsyncApiCalls() {
        val result = visible(DashboardFilter.XHR_FETCH)
        assertEquals(listOf(xhr), result)
    }

    @Test
    fun consoleFilter_returnsOnlyConsoleEvents() {
        val result = visible(DashboardFilter.CONSOLE)
        assertEquals(listOf(consoleLog, consoleWarn, consoleError, jsException), result)
    }

    @Test
    fun warningsFilter_returnsWarningsAndRedirects() {
        val result = visible(DashboardFilter.WARNINGS)
        assertTrue(result.contains(consoleWarn))
        assertTrue(result.contains(redirect))
        assertFalse(result.contains(consoleLog))
        assertFalse(result.contains(consoleError))
        assertTrue(result.all { it.isWarning })
    }

    @Test
    fun errorsFilter_returnsErrorsAndFailures() {
        val result = visible(DashboardFilter.ERRORS)
        assertTrue(result.contains(consoleError))
        assertTrue(result.contains(httpError))
        assertTrue(result.contains(networkFailure))
        assertTrue(result.contains(jsException))
        assertTrue(result.all { it.isError })
    }

    // ================================================================
    // Live search
    // ================================================================

    @Test
    fun search_blank_returnsEverything() {
        assertEquals(feed.size, visible(DashboardFilter.ALL, "   ").size)
    }

    @Test
    fun search_matchesUrlCaseInsensitive() {
        val result = visible(DashboardFilter.ALL, "API.EXAMPLE.com")
        assertTrue(result.contains(xhr))
        assertTrue(result.all { it.url?.lowercase()?.contains("api.example.com") == true })
    }

    @Test
    fun search_matchesMessageText() {
        val result = visible(DashboardFilter.ALL, "deprecated")
        assertEquals(listOf(consoleWarn), result)
    }

    @Test
    fun search_matchesKindBadge() {
        val result = visible(DashboardFilter.ALL, "js exception")
        assertEquals(listOf(jsException), result)
    }

    @Test
    fun search_noMatch_returnsEmpty() {
        assertTrue(visible(DashboardFilter.ALL, "zzzz-not-here").isEmpty())
    }

    @Test
    fun searchCombinedWithFilter_isIntersection() {
        val result = DashboardEventFilter.apply(feed, DashboardFilter.CONSOLE, "error")
        assertEquals(listOf(consoleError, jsException), result)
    }

    // ================================================================
    // Active state
    // ================================================================

    @Test
    fun isActive_onlyWhenRestricted() {
        assertFalse(DashboardEventFilter.isActive(DashboardFilter.ALL, ""))
        assertTrue(DashboardEventFilter.isActive(DashboardFilter.ALL, "x"))
        assertTrue(DashboardEventFilter.isActive(DashboardFilter.ERRORS, ""))
    }
}