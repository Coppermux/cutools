package com.coppermux.cutools.core.console

import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.model.Command
import com.coppermux.cutools.core.model.Protocol
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Protocol-level tests for the Console subsystem: CDP command serialization,
 * end-to-end event parsing/routing through the raw transport + classifier +
 * router, and malformed / unsupported event tolerance. Uses the real
 * [CommandDispatcher], [CdpEventRouter], and message classifier on an
 * in-memory fake transport.
 */
class ConsoleProtocolTest {

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
        assertTrue(inspector.state.entries.size >= count)
    }

    private fun bindAndEnable() {
        inspector.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        awaitPhase(ConsoleLifecyclePhase.READY)
    }

    // ============ command serialization ============

    @Test
    fun runtimeEnable_serializesWithMethodAndSessionId() {
        inspector.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("Runtime.enable") }
        assertTrue(raw.contains("\"method\":\"Runtime.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        val command: Command? = Protocol.commandAdapter.fromJson(raw)
        assertNotNull(command)
        assertEquals("Runtime.enable", command!!.method)
        assertEquals("session-1", command.sessionId)
    }

    @Test
    fun consoleEnable_serializesWithMethodAndSessionId() {
        inspector.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("\"method\":\"Console.enable\"") }
        assertTrue(raw.contains("\"method\":\"Console.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        val command: Command? = Protocol.commandAdapter.fromJson(raw)
        assertNotNull(command)
        assertEquals("Console.enable", command!!.method)
        assertEquals("session-1", command.sessionId)
    }

    // ============ event parsing + routing through raw transport ============

    @Test
    fun consoleEvent_parsedClassifiedAndRouted_toConsoleInspector() {
        bindAndEnable()
        transport.deliverMessage(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"warning","timestamp":1000.0,"executionContextId":1,"args":[{"type":"string","value":"hey"}]},"sessionId":"session-1"}"""
        )
        awaitEntryCount(1)
        val entry = inspector.state.entries[0]
        assertEquals(ConsoleSeverity.WARNING, entry.severity)
        assertEquals("hey", entry.args[0].value)
        assertEquals(1000.0, entry.timestamp!!, 0.0001)
    }

    @Test
    fun exceptionEvent_parsedAndRouted_asRuntimeException() {
        bindAndEnable()
        transport.deliverMessage(
            """{"method":"Runtime.exceptionThrown","params":{"timestamp":2000.0,"exceptionDetails":{"exceptionId":3,"text":"Uncaught","lineNumber":2,"columnNumber":3,"exception":{"description":"Error: nope"}}},"sessionId":"session-1"}"""
        )
        awaitEntryCount(1)
        assertTrue(inspector.state.entries[0].isRuntimeException)
        assertEquals("Error: nope", inspector.state.entries[0].exceptionDescription)
    }

    @Test
    fun consoleMessageAddedEvent_parsedAndRouted() {
        bindAndEnable()
        transport.deliverMessage(
            """{"method":"Console.messageAdded","params":{"message":{"source":"deprecation","level":"warning","text":"Use X instead"}},"sessionId":"session-1"}"""
        )
        awaitEntryCount(1)
        assertEquals(ConsoleSeverity.WARNING, inspector.state.entries[0].severity)
        assertEquals("deprecation", inspector.state.entries[0].source)
    }

    // ============ malformed / unsupported events ============

    @Test
    fun malformedJson_doesNotCrashPipeline() {
        bindAndEnable()
        transport.deliverMessage("{ this is not json !")
        transport.deliverMessage("""{"id":999,"result":{}}""")
        Thread.sleep(200)
        assertEquals(0, inspector.state.entries.size)
        assertEquals(ConsoleLifecyclePhase.READY, inspector.state.phase)
    }

    @Test
    fun malformedParams_inKnownEvent_areTolerated() {
        bindAndEnable()
        // Non-map params → no entry, no crash.
        transport.deliverMessage(
            """{"method":"Runtime.consoleAPICalled","params":"not-a-map","sessionId":"session-1"}"""
        )
        Thread.sleep(200)
        assertEquals(0, inspector.state.entries.size)
        // A minimal-but-valid params object is still a genuine event → mapped.
        transport.deliverMessage(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"log"},"sessionId":"session-1"}"""
        )
        awaitEntryCount(1)
        assertEquals(ConsoleSeverity.LOG, inspector.state.entries[0].severity)
    }

    @Test
    fun unsupportedEventMethod_isIgnored() {
        bindAndEnable()
        transport.deliverMessage("""{"method":"Some.UnknownEvent","params":{"x":1},"sessionId":"session-1"}""")
        Thread.sleep(200)
        assertEquals(0, inspector.state.entries.size)
    }

    @Test
    fun eventForOtherSession_isNotRoutedToThisInspector() {
        bindAndEnable()
        transport.deliverMessage(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"error","args":[{"type":"string","value":"leak"}]},"sessionId":"other-session"}"""
        )
        Thread.sleep(200)
        assertEquals(0, inspector.state.entries.size)
    }
}
