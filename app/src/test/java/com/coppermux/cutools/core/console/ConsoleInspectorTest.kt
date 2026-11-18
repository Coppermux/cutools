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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic unit tests for [ConsoleInspector] state, lifecycle, capability
 * handling, and session isolation, driven through the real [CommandDispatcher]
 * + [CdpEventRouter] on an in-memory fake transport. These never touch Chrome.
 */
class ConsoleInspectorTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var domInspector: DomInspector
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var inspector: ConsoleInspector

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        domInspector = DomInspector(dispatcher, router)
        executionEngine = ExecutionEngine(dispatcher)
        inspector = ConsoleInspector(dispatcher, router, domInspector, executionEngine)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun awaitPhase(expected: ConsoleLifecyclePhase) {
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state.phase != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, inspector.state.phase)
    }

    private fun awaitEntryCount(count: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state.entries.size < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("expected >= $count entries, got ${inspector.state.entries.size}", inspector.state.entries.size >= count)
    }

    /** Binds and responds to both enable commands → READY. */
    private fun bindAndEnable(sess: SessionInfo = session) {
        inspector.bindSession(sess)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"${sess.sessionId}","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"${sess.sessionId}","result":{}}""")
        awaitPhase(ConsoleLifecyclePhase.READY)
    }

    private fun deliverApiCalled(type: String, text: String = "text-$type", ts: Double = 1000.0) {
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to type, "timestamp" to ts, "executionContextId" to 1, "args" to listOf(mapOf("type" to "string", "value" to text))),
            sessionId = session.sessionId,
        ))
    }

    // ============ enablement / capability ============

    @Test
    fun bindSession_enablesBothDomains_onTheSession() {
        inspector.bindSession(session)
        assertTrue(transport.sentMessages.any { it.contains("Runtime.enable") })
        assertTrue(transport.sentMessages.any { it.contains("Console.enable") })
        assertEquals(session.sessionId, inspector.session?.sessionId)
        awaitPhase(ConsoleLifecyclePhase.ENABLING)
    }

    @Test
    fun enableSuccess_reachesReadySupported() {
        bindAndEnable()
        assertEquals(ConsoleCapabilityStatus.SUPPORTED, inspector.state.capability)
        assertEquals(ConsoleCapabilityStatus.SUPPORTED, inspector.state.runtimeCapability)
        assertEquals(ConsoleCapabilityStatus.SUPPORTED, inspector.state.consoleDomainCapability)
        assertNull(inspector.state.error)
    }

    @Test
    fun runtimeUnsupported_reportsUnsupportedAndNotifiesError() {
        var errorRef: Throwable? = null
        inspector.addListener(object : ConsoleInspectorListener {
            override fun onConsoleStateChanged(state: ConsoleState) {}
            override fun onError(error: Throwable) { errorRef = error }
        })
        inspector.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        awaitPhase(ConsoleLifecyclePhase.UNSUPPORTED)
        assertEquals(ConsoleCapabilityStatus.UNSUPPORTED, inspector.state.capability)
        assertEquals(ConsoleCapabilityStatus.UNSUPPORTED, inspector.state.runtimeCapability)
        assertNotNull(errorRef)
        // A late console-enable response must not flip the terminal state.
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        inspector.drain()
        assertEquals(ConsoleLifecyclePhase.UNSUPPORTED, inspector.state.phase)
    }

    @Test
    fun runtimeEnableFailure_marksFailed() {
        inspector.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","error":{"code":-32000,"message":"boom"}}""")
        awaitPhase(ConsoleLifecyclePhase.FAILED)
        assertEquals(ConsoleCapabilityStatus.FAILED, inspector.state.capability)
        assertEquals(ConsoleCapabilityStatus.FAILED, inspector.state.runtimeCapability)
        assertNotNull(inspector.state.error)
    }

    @Test
    fun consoleDomainUnsupported_butRuntimeWorks_reachesReady() {
        inspector.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        awaitPhase(ConsoleLifecyclePhase.READY)
        assertEquals(ConsoleCapabilityStatus.SUPPORTED, inspector.state.capability)
        assertEquals(ConsoleCapabilityStatus.UNSUPPORTED, inspector.state.consoleDomainCapability)
    }

    // ============ event reception ============

    @Test
    fun consoleApiCalled_appendsStructuredEntry() {
        bindAndEnable()
        deliverApiCalled("warning", "careful", 1500.0)
        awaitEntryCount(1)
        val entry = inspector.state.entries[0]
        assertEquals(1L, entry.id)
        assertEquals(ConsoleSeverity.WARNING, entry.severity)
        assertEquals("careful", entry.args[0].value)
        assertEquals(1500.0, entry.timestamp!!, 0.0001)
        assertEquals(session.sessionId, entry.sessionId)
        assertEquals(session.targetId, entry.targetId)
    }

    @Test
    fun exceptionThrown_appendsRuntimeExceptionEntry() {
        bindAndEnable()
        router.handleEvent(Event(
            "Runtime.exceptionThrown",
            mapOf(
                "timestamp" to 2000.0,
                "exceptionDetails" to mapOf(
                    "exceptionId" to 7,
                    "text" to "Uncaught",
                    "lineNumber" to 4,
                    "columnNumber" to 22,
                    "exception" to mapOf("description" to "Error: boom"),
                ),
            ),
            sessionId = session.sessionId,
        ))
        awaitEntryCount(1)
        val entry = inspector.state.entries[0]
        assertTrue(entry.isRuntimeException)
        assertEquals(ConsoleSeverity.ERROR, entry.severity)
        assertEquals(2000.0, entry.timestamp!!, 0.0001)
        assertEquals("Error: boom", entry.exceptionDescription)
        assertEquals(4, entry.location?.line)
    }

    @Test
    fun messageAdded_consoleApiSource_isSkippedAsDuplicateProjection() {
        bindAndEnable()
        router.handleEvent(Event(
            "Console.messageAdded",
            mapOf("message" to mapOf("source" to "console-api", "level" to "log", "text" to "dup")),
            sessionId = session.sessionId,
        ))
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
    }

    @Test
    fun messageAdded_nonConsoleApiSource_isAppendedHonestly() {
        bindAndEnable()
        router.handleEvent(Event(
            "Console.messageAdded",
            mapOf("message" to mapOf("source" to "network", "level" to "error", "text" to "Failed to load resource")),
            sessionId = session.sessionId,
        ))
        awaitEntryCount(1)
        val entry = inspector.state.entries[0]
        assertEquals("network", entry.source)
        assertEquals("Failed to load resource", entry.text)
        assertEquals(ConsoleSeverity.ERROR, entry.severity)
        // This Chrome version exposes no timestamp on messageAdded — honest null.
        assertNull(entry.timestamp)
    }

    @Test
    fun messageAdded_malformedMessage_isIgnored() {
        bindAndEnable()
        router.handleEvent(Event("Console.messageAdded", mapOf("message" to "not-a-map"), sessionId = session.sessionId))
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
    }

    @Test
    fun entryIds_increaseInArrivalOrder() {
        bindAndEnable()
        deliverApiCalled("log", "a", 1000.0)
        deliverApiCalled("error", "b", 2000.0)
        awaitEntryCount(2)
        assertEquals(listOf(1L, 2L), inspector.state.entries.map { it.id })
    }

    @Test
    fun entriesFromOtherSession_areIgnored() {
        bindAndEnable()
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to "log", "timestamp" to 1.0, "args" to emptyList<Any>()),
            sessionId = "other-session",
        ))
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
    }

    // ============ lifecycle ============

    @Test
    fun detach_clearsEntriesAndBecomesInactive() {
        bindAndEnable()
        deliverApiCalled("log")
        awaitEntryCount(1)
        inspector.detach()
        assertEquals(ConsoleLifecyclePhase.INACTIVE, inspector.state.phase)
        assertEquals(0, inspector.state.entries.size)
        assertNull(inspector.session)
        assertNull(inspector.state.sessionId)
    }

    @Test
    fun onConnectionLost_clearsEverything() {
        bindAndEnable()
        deliverApiCalled("log")
        awaitEntryCount(1)
        inspector.onConnectionLost()
        assertEquals(ConsoleLifecyclePhase.INACTIVE, inspector.state.phase)
        assertEquals(0, inspector.state.entries.size)
        assertNull(inspector.session)
    }

    @Test
    fun targetSwitch_oldSessionEventsCannotLeakIntoNewSession() {
        bindAndEnable()
        // A message from the old binding, still queued, must be ignored after switch.
        inspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-2","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-2","result":{}}""")
        awaitPhase(ConsoleLifecyclePhase.READY)
        assertEquals(0, inspector.state.entries.size)

        // Old-session events are rejected by the new active session.
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to "log", "timestamp" to 1.0, "args" to emptyList<Any>()),
            sessionId = "session-1",
        ))
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)

        // New-session events are accepted and associated with the new session.
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to "error", "timestamp" to 2.0, "args" to emptyList<Any>()),
            sessionId = "session-2",
        ))
        awaitEntryCount(1)
        assertEquals("session-2", inspector.state.entries[0].sessionId)
        assertEquals("target-2", inspector.state.entries[0].targetId)
    }

    @Test
    fun delayedEnableResponse_afterDetach_isIgnored() {
        inspector.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        inspector.detach()
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        inspector.drain()
        assertEquals(ConsoleLifecyclePhase.INACTIVE, inspector.state.phase)
        assertEquals(0, inspector.state.entries.size)
    }

    @Test
    fun eventsAfterDetach_areIgnored() {
        bindAndEnable()
        inspector.detach()
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to "log", "args" to emptyList<Any>()),
            sessionId = session.sessionId,
        ))
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
    }

    @Test
    fun shutdown_unsubscribesAndStopsExecutor() {
        bindAndEnable()
        inspector.shutdown()
        // Events delivered after shutdown must not be processed.
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to "log", "args" to emptyList<Any>()),
            sessionId = session.sessionId,
        ))
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
    }

    // ============ Phase 4B — evaluation result integration ============

    private fun successOutcome(id: Long = 1, sessId: String = session.sessionId) =
        com.coppermux.cutools.core.runtime.EvaluationOutcome(
            id = id,
            request = com.coppermux.cutools.core.runtime.EvaluationRequest("1 + 1"),
            result = com.coppermux.cutools.core.runtime.EvaluationResultKind.Success(
                com.coppermux.cutools.core.runtime.EvaluationRemoteObject(type = "number", value = 2, description = "2"),
            ),
            sessionId = sessId,
            targetId = session.targetId,
        )

    @Test
    fun recordEvaluation_appendsEvaluationResultEntry() {
        bindAndEnable()
        inspector.recordEvaluation(successOutcome())
        awaitEntryCount(1)
        val entry = inspector.state.entries[0]
        assertEquals(ConsoleEntryKind.EVALUATION_RESULT, entry.kind)
        assertTrue(entry.isEvaluationResult)
        assertEquals(ConsoleSeverity.LOG, entry.severity)
        assertEquals("2", entry.displayText)
        assertEquals(session.sessionId, entry.sessionId)
        assertNotNull("evaluation outcome must be preserved on the entry", entry.evaluation)
        assertNull("evaluation entries have no browser timestamp", entry.timestamp)
    }

    @Test
    fun recordEvaluation_exception_entryIsErrorSeverity() {
        bindAndEnable()
        val outcome = com.coppermux.cutools.core.runtime.EvaluationOutcome(
            id = 1,
            request = com.coppermux.cutools.core.runtime.EvaluationRequest("boom()"),
            result = com.coppermux.cutools.core.runtime.EvaluationResultKind.Exception(
                com.coppermux.cutools.core.runtime.EvaluationExceptionDetails(
                    text = "Uncaught",
                    exception = com.coppermux.cutools.core.runtime.EvaluationRemoteObject(
                        type = "object", subtype = "error", description = "ReferenceError: boom is not defined",
                    ),
                ),
            ),
            sessionId = session.sessionId,
            targetId = session.targetId,
        )
        inspector.recordEvaluation(outcome)
        awaitEntryCount(1)
        val entry = inspector.state.entries[0]
        assertEquals(ConsoleEntryKind.EVALUATION_RESULT, entry.kind)
        assertEquals(ConsoleSeverity.ERROR, entry.severity)
        assertTrue(entry.displayText.contains("boom is not defined"))
    }

    @Test
    fun recordEvaluation_protocolError_entryIsErrorSeverity() {
        bindAndEnable()
        val outcome = com.coppermux.cutools.core.runtime.EvaluationOutcome(
            id = 1,
            request = com.coppermux.cutools.core.runtime.EvaluationRequest("1"),
            result = com.coppermux.cutools.core.runtime.EvaluationResultKind.ProtocolError(
                com.coppermux.cutools.core.error.ConnectionException("Session not found"),
            ),
            sessionId = session.sessionId,
            targetId = session.targetId,
        )
        inspector.recordEvaluation(outcome)
        awaitEntryCount(1)
        assertEquals(ConsoleSeverity.ERROR, inspector.state.entries[0].severity)
        assertTrue(inspector.state.entries[0].displayText.contains("Session not found"))
    }

    @Test
    fun recordEvaluation_fromOtherSession_isDropped() {
        bindAndEnable()
        inspector.recordEvaluation(successOutcome(sessId = "other-session"))
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
    }

    @Test
    fun recordEvaluation_afterDetach_isDropped() {
        bindAndEnable()
        inspector.detach()
        inspector.recordEvaluation(successOutcome())
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
    }

    @Test
    fun clear_clearsEntriesAndInput_whilePreservingSessionAndCapabilities() {
        bindAndEnable()
        inspector.setInput("1 + 1")
        inspector.recordEvaluation(successOutcome())
        awaitEntryCount(1)
        assertEquals(1, inspector.state.entries.size)
        assertEquals("1 + 1", inspector.state.input)
        assertNotNull(inspector.session)

        inspector.clear()
        inspector.drain()
        assertTrue("entries cleared", inspector.state.entries.isEmpty())
        assertEquals("", inspector.state.input)
        assertNotNull("session preserved", inspector.session)
        assertEquals(ConsoleCapabilityStatus.SUPPORTED, inspector.state.capability)
    }

    @Test
    fun submitInput_whenNoSessionAttached_preservesInputAndNotifiesError() {
        var capturedError: Throwable? = null
        inspector.addListener(object : ConsoleInspectorListener {
            override fun onConsoleStateChanged(state: ConsoleState) {}
            override fun onError(error: Throwable) { capturedError = error }
        })
        inspector.setInput("location.href")
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state.input != "location.href" && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("location.href", inspector.state.input)

        inspector.submitInput()
        val errorDeadline = System.currentTimeMillis() + 3000
        while (capturedError == null && System.currentTimeMillis() < errorDeadline) Thread.sleep(10)

        assertNotNull(capturedError)
        assertTrue(capturedError!!.message!!.contains("Attach a target"))
        // Input must NOT be cleared when evaluation cannot be submitted!
        assertEquals("location.href", inspector.state.input)
        assertTrue(inspector.state.entries.isEmpty())
        assertFalse(transport.sentMessages.any { it.contains("Runtime.evaluate") })
    }

    @Test
    fun submitInput_whenSessionAttached_clearsInputAndEvaluates() {
        bindAndEnable()
        executionEngine.bindSession(session)
        inspector.setInput("location.href")
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state.input != "location.href" && System.currentTimeMillis() < deadline) Thread.sleep(10)

        inspector.submitInput()
        val clearedDeadline = System.currentTimeMillis() + 3000
        while (inspector.state.input.isNotEmpty() && System.currentTimeMillis() < clearedDeadline) Thread.sleep(10)

        assertEquals("", inspector.state.input)
        assertTrue(inspector.state.history.entries.contains("location.href"))
        assertTrue(inspector.state.entries.any { it.isCommand && it.text == "location.href" })
        val evalDeadline = System.currentTimeMillis() + 3000
        while (!transport.sentMessages.any { it.contains("Runtime.evaluate") } && System.currentTimeMillis() < evalDeadline) Thread.sleep(10)
        assertTrue(transport.sentMessages.any { it.contains("Runtime.evaluate") })
    }

    // ============ PERF-01 — bounded entry collection (1,000 entries max) ============

    @Test
    fun perf01_1_fewerThan1000Entries_noEviction() {
        bindAndEnable()
        for (i in 1..500) {
            deliverApiCalled("log", "msg-$i", ts = i.toDouble())
        }
        inspector.drain()
        assertEquals(500, inspector.state.entries.size)
        assertEquals(1L, inspector.state.entries.first().id)
        assertEquals(500L, inspector.state.entries.last().id)
        assertEquals("msg-1", inspector.state.entries.first().args.first().value)
        assertEquals("msg-500", inspector.state.entries.last().args.first().value)
    }

    @Test
    fun perf01_2_exactly1000Entries_noEviction() {
        bindAndEnable()
        for (i in 1..1000) {
            deliverApiCalled("log", "msg-$i", ts = i.toDouble())
        }
        inspector.drain()
        assertEquals(1000, inspector.state.entries.size)
        assertEquals(1L, inspector.state.entries.first().id)
        assertEquals(1000L, inspector.state.entries.last().id)
        assertEquals("msg-1", inspector.state.entries.first().args.first().value)
        assertEquals("msg-1000", inspector.state.entries.last().args.first().value)
    }

    @Test
    fun perf01_3_entry1001_oldestEntryRemoved() {
        bindAndEnable()
        for (i in 1..1001) {
            deliverApiCalled("log", "msg-$i", ts = i.toDouble())
        }
        inspector.drain()
        assertEquals(1000, inspector.state.entries.size)
        assertEquals(2L, inspector.state.entries.first().id)
        assertEquals(1001L, inspector.state.entries.last().id)
        assertEquals("msg-2", inspector.state.entries.first().args.first().value)
        assertEquals("msg-1001", inspector.state.entries.last().args.first().value)
        assertFalse(inspector.state.entries.any { it.id == 1L })
    }

    @Test
    fun perf01_4_multipleAdditionalEntries_collectionRemainsAt1000() {
        bindAndEnable()
        for (i in 1..1050) {
            deliverApiCalled("log", "msg-$i", ts = i.toDouble())
        }
        inspector.drain()
        assertEquals(1000, inspector.state.entries.size)
        assertEquals(51L, inspector.state.entries.first().id)
        assertEquals(1050L, inspector.state.entries.last().id)
        assertEquals("msg-51", inspector.state.entries.first().args.first().value)
        assertEquals("msg-1050", inspector.state.entries.last().args.first().value)
    }

    @Test
    fun perf01_5_chronologicalOrderingPreserved() {
        bindAndEnable()
        for (i in 1..1050) {
            deliverApiCalled("log", "msg-$i", ts = i.toDouble())
        }
        inspector.drain()
        val entries = inspector.state.entries
        assertEquals(1000, entries.size)
        for (idx in 0 until entries.size) {
            val expectedNum = idx + 51
            assertEquals(expectedNum.toLong(), entries[idx].id)
            assertEquals("msg-$expectedNum", entries[idx].args.first().value)
            assertEquals(expectedNum.toDouble(), entries[idx].timestamp!!, 0.001)
        }
    }

    @Test
    fun perf01_6_entryIdsRemainUnchanged() {
        bindAndEnable()
        for (i in 1..1020) {
            deliverApiCalled("log", "msg-$i", ts = i.toDouble())
        }
        inspector.drain()
        val entries = inspector.state.entries
        assertEquals(1000, entries.size)
        val ids = entries.map { it.id }
        val expectedIds = (21L..1020L).toList()
        assertEquals(expectedIds, ids)
    }

    @Test
    fun perf01_7_mixedCommandResultAndMessageEntries_countTowardSameLimit() {
        bindAndEnable()
        for (i in 1..400) {
            deliverApiCalled("info", "message-$i", ts = i.toDouble())
        }
        for (i in 1..400) {
            inspector.submitInput("cmd-$i")
        }
        for (i in 1..300) {
            inspector.recordEvaluation(successOutcome(id = i.toLong()))
        }
        inspector.drain()

        val entries = inspector.state.entries
        assertEquals(1000, entries.size)
        assertEquals(101L, entries.first().id)
        assertFalse(entries.any { it.id <= 100L })

        val messageCount = entries.count { it.kind == ConsoleEntryKind.CONSOLE_MESSAGE }
        val commandCount = entries.count { it.kind == ConsoleEntryKind.COMMAND }
        val resultCount = entries.count { it.kind == ConsoleEntryKind.EVALUATION_RESULT }

        assertEquals(300, messageCount)
        assertEquals(400, commandCount)
        assertEquals(300, resultCount)
        assertEquals(1000, messageCount + commandCount + resultCount)
    }

    @Test
    fun perf01_8_clearingConsole_emptiesCollection() {
        bindAndEnable()
        for (i in 1..1000) {
            deliverApiCalled("log", "msg-$i", ts = i.toDouble())
        }
        inspector.drain()
        assertEquals(1000, inspector.state.entries.size)

        inspector.clear()
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)
        assertTrue(inspector.state.entries.isEmpty())
        assertFalse(inspector.state.hasEntries)
    }

    @Test
    fun perf01_9_newEntriesAfterClearing_behaveNormally() {
        bindAndEnable()
        for (i in 1..1000) {
            deliverApiCalled("log", "old-$i", ts = i.toDouble())
        }
        inspector.drain()
        assertEquals(1000, inspector.state.entries.size)

        inspector.clear()
        inspector.drain()
        assertEquals(0, inspector.state.entries.size)

        for (i in 1..5) {
            deliverApiCalled("log", "new-$i", ts = (1000 + i).toDouble())
        }
        inspector.drain()
        assertEquals(5, inspector.state.entries.size)
        assertEquals("new-1", inspector.state.entries.first().args.first().value)
        assertEquals("new-5", inspector.state.entries.last().args.first().value)
    }

    @Test
    fun perf01_10_existingJavaScriptEvaluationBehaviour_remainsUnaffected() {
        bindAndEnable()
        executionEngine.bindSession(session)
        inspector.setInput("2 + 2")

        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state.input != "2 + 2" && System.currentTimeMillis() < deadline) Thread.sleep(10)

        inspector.submitInput()
        inspector.drain()

        val cmdEntry = inspector.state.entries.firstOrNull { it.isCommand }
        assertNotNull(cmdEntry)
        assertEquals("2 + 2", cmdEntry!!.text)
        assertEquals(ConsoleEntryKind.COMMAND, cmdEntry.kind)

        val evalMsg = transport.sentMessages.last { it.contains("Runtime.evaluate") }
        val evalId = Regex("\"id\":(\\d+)").find(evalMsg)!!.groupValues[1].toLong()

        transport.deliverMessage("""{"id":$evalId,"sessionId":"${session.sessionId}","result":{"result":{"type":"number","value":4,"description":"4"}}}""")
        awaitEntryCount(2)

        val evalResultEntry = inspector.state.entries.firstOrNull { it.isEvaluationResult }
        assertNotNull(evalResultEntry)
        assertEquals("4", evalResultEntry!!.displayText)
        assertEquals(ConsoleEntryKind.EVALUATION_RESULT, evalResultEntry.kind)
        assertEquals(ConsoleSeverity.LOG, evalResultEntry.severity)
    }
}
