package com.coppermux.cutools.core.console

import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic unit tests for Phase 4D Console search + severity filtering
 * state: visible-entry derivation, search/filter composition, preservation of
 * the underlying entry collection, and CuTools-wide presentation-state
 * preservation across target switches / reconnect.
 */
class ConsoleSearchFilterTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var domInspector: DomInspector
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var console: ConsoleInspector

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        domInspector = DomInspector(dispatcher, router)
        executionEngine = ExecutionEngine(dispatcher)
        executionEngine.bindSession(session)
        console = ConsoleInspector(dispatcher, router, domInspector, executionEngine)
        bindConsole()
        populateEntries()
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun bindConsole() {
        console.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.READY })
    }

    /** Delivers a console API event with [type] and [text]. */
    private fun deliverApi(type: String, text: String) {
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to type, "timestamp" to 1000.0, "executionContextId" to 1, "args" to listOf(mapOf("type" to "string", "value" to text))),
            sessionId = session.sessionId,
        ))
    }

    private fun deliverRuntimeException(text: String) {
        router.handleEvent(Event(
            "Runtime.exceptionThrown",
            mapOf(
                "timestamp" to 2000.0,
                "exceptionDetails" to mapOf(
                    "exceptionId" to 1,
                    "text" to "Uncaught",
                    "exception" to mapOf("description" to "Error: $text"),
                ),
            ),
            sessionId = session.sessionId,
        ))
    }

    private fun awaitEntryCount(count: Int) {
        assertTrue("entries >= $count", waitFor { console.state.entries.size >= count })
    }

    /** log/info/debug/warning/error messages + a runtime exception. */
    private fun populateEntries() {
        deliverApi("log", "hello world")
        deliverApi("info", "started server")
        deliverApi("debug", "debugging detail")
        deliverApi("warning", "deprecated usage")
        deliverApi("error", "boom happened")
        deliverRuntimeException("boom exception")
        awaitEntryCount(6)
    }

    private fun severityOf(text: String): ConsoleSeverity = when (text) {
        "hello world" -> ConsoleSeverity.LOG
        "started server" -> ConsoleSeverity.INFO
        "debugging detail" -> ConsoleSeverity.DEBUG
        "deprecated usage" -> ConsoleSeverity.WARNING
        "boom happened" -> ConsoleSeverity.ERROR
        else -> ConsoleSeverity.ERROR
    }

    // ============ search ============

    @Test
    fun defaultSearch_showsAllEntries() {
        assertEquals(6, console.state.visibleEntries.size)
        assertEquals(console.state.entries.size, console.state.visibleCount)
    }

    @Test
    fun setSearchQuery_filtersVisibleEntries() {
        console.setSearchQuery("boom")
        assertTrue(waitFor { console.state.searchQuery == "boom" })
        // "boom happened" and the runtime exception "boom exception"
        assertEquals(2, console.state.visibleCount)
        assertTrue(console.state.visibleEntries.all { it.displayText.contains("boom", ignoreCase = true) })
    }

    @Test
    fun setSearchQuery_matchingAndNonMatching() {
        console.setSearchQuery("server")
        assertTrue(waitFor { console.state.visibleCount == 1 })
        assertEquals(ConsoleSeverity.INFO, console.state.visibleEntries[0].severity)

        console.setSearchQuery("zzz-no-match")
        assertTrue(waitFor { console.state.visibleCount == 0 })
    }

    @Test
    fun clearSearch_restoresVisibility() {
        console.setSearchQuery("boom")
        assertTrue(waitFor { console.state.visibleCount == 2 })
        console.clearSearch()
        assertTrue(waitFor { console.state.searchQuery.isEmpty() && console.state.visibleCount == 6 })
    }

    @Test
    fun searchDoesNotMutateUnderlyingEntries() {
        val before = console.state.entries
        console.setSearchQuery("boom")
        assertTrue(waitFor { console.state.searchQuery == "boom" })
        assertEquals(6, console.state.entries.size)
        assertEquals(before, console.state.entries)
    }

    // ============ severity filtering ============

    @Test
    fun severityFilter_showsOnlySelectedSeverity() {
        console.setSeverityFilter(ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR)))
        assertTrue(waitFor { console.state.visibleCount == 2 })  // error + runtime exception (ERROR)
        assertTrue(console.state.visibleEntries.all { it.severity == ConsoleSeverity.ERROR })
    }

    @Test
    fun severityFilter_multipleSeverities() {
        console.setSeverityFilter(ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.LOG, ConsoleSeverity.WARNING)))
        assertTrue(waitFor { console.state.visibleCount == 2 })
        val sevs = console.state.visibleEntries.map { it.severity }.toSet()
        assertEquals(setOf(ConsoleSeverity.LOG, ConsoleSeverity.WARNING), sevs)
    }

    @Test
    fun runtimeExceptionsFilteredIndependentlyOfSeverity() {
        // Show ERROR only, but hide runtime exceptions → only the error message remains.
        console.setSeverityFilter(ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR), includeRuntimeExceptions = false))
        assertTrue(waitFor { console.state.visibleCount == 1 })
        assertEquals(ConsoleSeverity.ERROR, console.state.visibleEntries[0].severity)
        assertTrue(console.state.visibleEntries.none { it.kind == ConsoleEntryKind.RUNTIME_EXCEPTION })
    }

    @Test
    fun severityFilterUsesStructuredSeverity() {
        console.setSeverityFilter(ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.DEBUG)))
        assertTrue(waitFor { console.state.visibleCount == 1 })
        assertEquals("debugging detail", console.state.visibleEntries[0].displayText)
    }

    @Test
    fun clearSeverityFilter_restoresAll() {
        console.setSeverityFilter(ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR)))
        assertTrue(waitFor { console.state.visibleCount == 2 })
        console.clearSeverityFilter()
        assertTrue(waitFor { console.state.severityFilter.isAll && console.state.visibleCount == 6 })
    }

    @Test
    fun filterDoesNotDeleteUnderlyingEntries() {
        console.setSeverityFilter(ConsoleSeverityFilter(severities = setOf(ConsoleSeverity.ERROR)))
        assertTrue(waitFor { console.state.visibleCount == 2 })
        assertEquals(6, console.state.entries.size)
    }

    // ============ composition ============

    @Test
    fun searchAndFilter_composeDeterministically() {
        console.setSearchQuery("boom")
        assertTrue(waitFor { console.state.visibleCount == 2 })
        // Intersection: ERROR severity AND "boom" → only the error message (not the runtime exception, once hidden).
        console.setRuntimeExceptionsVisible(false)
        assertTrue(waitFor { console.state.visibleCount == 1 })
        assertEquals("boom happened", console.state.visibleEntries[0].displayText)
        // Underlying collection still intact.
        assertEquals(6, console.state.entries.size)
    }

    // ============ presentation preservation across lifecycle ============

    @Test
    fun searchAndFilter_preservedAcrossDetach() {
        console.setSearchQuery("boom")
        console.setRuntimeExceptionsVisible(false)
        assertTrue(waitFor { console.state.searchQuery == "boom" && !console.state.severityFilter.includeRuntimeExceptions })

        console.detach()
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.INACTIVE })
        assertEquals("search is CuTools-wide and survives detach", "boom", console.state.searchQuery)
        assertFalse(console.state.severityFilter.includeRuntimeExceptions)
        assertEquals(0, console.state.entries.size)
    }

    @Test
    fun searchAndFilter_preservedAcrossReconnect() {
        console.setSearchQuery("boom")
        assertTrue(waitFor { console.state.searchQuery == "boom" })

        // Reconnect: bind to a fresh session (new connection session).
        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        console.bindSession(newSession)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-2","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-2","result":{}}""")
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.READY })

        assertEquals("search preference survives reconnect", "boom", console.state.searchQuery)
        assertEquals(0, console.state.entries.size)
        assertEquals("session-2", console.state.sessionId)
        assertNotEquals(session.sessionId, console.state.sessionId)
    }
}