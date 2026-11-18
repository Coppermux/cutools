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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic unit tests for Phase 4D Console synchronization across the
 * session/target lifecycle: target switching, disconnect, reconnect, stale
 * event rejection, stale command-response rejection, and event-cleanup.
 */
class ConsoleLifecycleSyncTest {

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
        console = ConsoleInspector(dispatcher, router, domInspector, executionEngine)
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

    private fun bind(sess: SessionInfo) {
        console.bindSession(sess)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        transport.deliverMessage("""{"id":$rt,"sessionId":"${sess.sessionId}","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"${sess.sessionId}","result":{}}""")
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.READY })
    }

    private fun deliverApi(sess: SessionInfo, type: String, text: String) {
        router.handleEvent(Event(
            "Runtime.consoleAPICalled",
            mapOf("type" to type, "timestamp" to 1000.0, "executionContextId" to 1, "args" to listOf(mapOf("type" to "string", "value" to text))),
            sessionId = sess.sessionId,
        ))
    }

    private fun awaitEntryCount(count: Int) {
        assertTrue("entries >= $count", waitFor { console.state.entries.size >= count })
    }

    // ============ domain initialization on bind ============

    @Test
    fun bind_initializesBothDomainsOnTheSession() {
        console.bindSession(session)
        assertTrue(transport.sentMessages.any { it.contains("Runtime.enable") })
        assertTrue(transport.sentMessages.any { it.contains("Console.enable") })
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")
        assertTrue(transport.sentMessages.last { it.contains("Runtime.enable") }.contains("\"sessionId\":\"session-1\""))
        assertTrue(transport.sentMessages.last { it.contains("Console.enable") }.contains("\"sessionId\":\"session-1\""))
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.READY })
    }

    @Test
    fun detach_removesSubscriptionsAndClearsTargetState() {
        bind(session)
        deliverApi(session, "log", "a")
        awaitEntryCount(1)
        console.detach()
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.INACTIVE })
        assertNull(console.session)
        assertEquals(0, console.state.entries.size)
        assertNull(console.state.sessionId)
        // Events delivered after detach (subscriptions removed / session cleared) are ignored.
        deliverApi(session, "log", "b")
        Thread.sleep(200)
        assertEquals(0, console.state.entries.size)
    }

    // ============ target switching ============

    @Test
    fun targetSwitch_clearsOldEntriesAndRejectsOldSessionEvents() {
        bind(session)
        deliverApi(session, "log", "old-target-message")
        awaitEntryCount(1)

        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        bind(newSession)
        assertEquals(0, console.state.entries.size)
        assertEquals("session-2", console.state.sessionId)
        assertEquals("target-2", console.state.targetId)

        // Events from the OLD session must never become the new target's entries.
        deliverApi(session, "warning", "leak-test")
        Thread.sleep(200)
        assertEquals(0, console.state.entries.size)

        // Events from the NEW session are accepted and associated correctly.
        deliverApi(newSession, "log", "new-target-message")
        awaitEntryCount(1)
        assertEquals("session-2", console.state.entries[0].sessionId)
        assertEquals("target-2", console.state.entries[0].targetId)
    }

    @Test
    fun targetSwitch_delayedDomainResponseFromOldSessionIsDropped() {
        // Bind to session-1 but do NOT respond to its enable commands yet.
        console.bindSession(session)
        val rt = parseIdFor("Runtime.enable")
        val ce = parseIdFor("Console.enable")

        // Switch to session-2 before the old responses arrive.
        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        console.bindSession(newSession)
        val rt2 = parseIdFor("Runtime.enable")
        val ce2 = parseIdFor("Console.enable")

        // Deliver the OLD session's enable responses — must be ignored.
        transport.deliverMessage("""{"id":$rt,"sessionId":"session-1","result":{}}""")
        transport.deliverMessage("""{"id":$ce,"sessionId":"session-1","result":{}}""")
        Thread.sleep(200)
        assertEquals("old enable responses must not flip the new session", ConsoleLifecyclePhase.ENABLING, console.state.phase)
        assertEquals("session-2", console.state.sessionId)

        // Deliver the NEW session's enable responses → READY.
        transport.deliverMessage("""{"id":$rt2,"sessionId":"session-2","result":{}}""")
        transport.deliverMessage("""{"id":$ce2,"sessionId":"session-2","result":{}}""")
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.READY })
    }

    // ============ disconnect / reconnect ============

    @Test
    fun reconnect_reestablishesDomainsAndAcceptsNewSessionEvents() {
        // Connection 1.
        bind(session)
        deliverApi(session, "log", "before-reconnect")
        awaitEntryCount(1)

        // Disconnect clears target-scoped state.
        console.detach()
        assertTrue(waitFor { console.state.phase == ConsoleLifecyclePhase.INACTIVE })
        assertEquals(0, console.state.entries.size)

        // Reconnect: a fresh session (fresh connection).
        val reconnectSession = SessionInfo("session-3", "target-3", "ws://test")
        bind(reconnectSession)
        assertEquals(0, console.state.entries.size)
        assertEquals("session-3", console.state.sessionId)

        deliverApi(reconnectSession, "log", "after-reconnect")
        awaitEntryCount(1)
        assertEquals("session-3", console.state.entries[0].sessionId)
        assertTrue(console.state.entries[0].displayText.contains("after-reconnect"))
    }

    @Test
    fun reconnect_staleOldSessionEventIsRejected() {
        bind(session)
        deliverApi(session, "log", "old")
        awaitEntryCount(1)

        // Disconnect, then reconnect to a fresh session.
        console.detach()
        val reconnectSession = SessionInfo("session-3", "target-3", "ws://test")
        bind(reconnectSession)

        // A delayed old-session event must be rejected by the new session.
        deliverApi(session, "warning", "stale-old")
        Thread.sleep(200)
        assertEquals(0, console.state.entries.size)

        deliverApi(reconnectSession, "log", "fresh")
        awaitEntryCount(1)
        assertEquals(listOf("fresh"), console.state.entries.map { it.displayText })
    }

    // ============ stale evaluation / command responses ============

    @Test
    fun staleEvaluationResponse_afterTargetSwitch_isNotApplied() {
        executionEngine.bindSession(session)
        bind(session)
        // Issue an evaluation on session-1 via the engine, then switch the console.
        val outcomeRef = AtomicReference<com.coppermux.cutools.core.runtime.EvaluationOutcome?>()
        executionEngine.evaluate(com.coppermux.cutools.core.runtime.EvaluationRequest("1 + 1")) { o -> outcomeRef.set(o) }
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        val evalId = parseIdFor("Runtime.evaluate")

        // Switch console + engine to a new session before the response arrives.
        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        executionEngine.bindSession(newSession)
        bind(newSession)

        transport.deliverMessage(
            """{"id":$evalId,"sessionId":"session-1","result":{"result":{"type":"number","value":2,"description":"2"}}}"""
        )
        Thread.sleep(200)
        assertNull("stale evaluation result must not be applied to the new target", outcomeRef.get())
        assertEquals(0, console.state.entries.size)
    }

    @Test
    fun evaluationAfterReconnect_targetsNewSession() {
        bind(session)
        executionEngine.bindSession(session)
        console.submitInput()
        // No input → no-op; set input first.
        Thread.sleep(50)
        assertEquals(0, console.state.entries.size)

        console.setInput("7 * 6")
        console.submitInput()
        assertTrue(waitFor { transport.sentMessages.any { it.contains("Runtime.evaluate") } })
        val sent = transport.sentMessages.last { it.contains("Runtime.evaluate") }
        assertTrue(sent.contains("\"sessionId\":\"session-1\""))

        // Reconnect and re-evaluate against the new session.
        console.detach()
        val reconnectSession = SessionInfo("session-3", "target-3", "ws://test")
        executionEngine.bindSession(reconnectSession)
        bind(reconnectSession)
        console.setInput("40 + 2")
        console.submitInput()
        assertTrue(waitFor {
            transport.sentMessages.any {
                it.contains("Runtime.evaluate") && it.contains("\"sessionId\":\"session-3\"")
            }
        })
        val sent2 = transport.sentMessages.last { it.contains("Runtime.evaluate") }
        assertTrue(sent2.contains("\"sessionId\":\"session-3\""))
        assertFalse(sent2.contains("\"sessionId\":\"session-1\""))
    }
}