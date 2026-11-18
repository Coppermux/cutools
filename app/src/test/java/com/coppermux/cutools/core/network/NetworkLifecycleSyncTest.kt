package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic unit tests for Phase 5A Network lifecycle synchronization:
 * target switch, disconnect, reconnect, re-binding, and stale-event rejection.
 * Verifies the engine clears/rebinds active request listeners gracefully without
 * throwing stale-state exceptions.
 */
class NetworkLifecycleSyncTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: NetworkEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = NetworkEngine(dispatcher, router)
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
        engine.bindSession(sess)
        val net = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$net,"sessionId":"${sess.sessionId}","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })
    }

    private fun deliverRequest(sess: SessionInfo, requestId: String, url: String) {
        router.handleEvent(Event(
            "Network.requestWillBeSent",
            mapOf("requestId" to requestId, "documentURL" to url, "request" to mapOf("url" to url, "method" to "GET")),
            sessionId = sess.sessionId,
            targetId = sess.targetId,
        ))
    }

    private fun awaitRequestCount(count: Int) {
        assertTrue("requests >= $count", waitFor { engine.state.requests.size >= count })
    }

    // ============ domain re-initialization on bind ============

    @Test
    fun bind_initializesNetworkDomainOnTheSession() {
        engine.bindSession(session)
        assertTrue(transport.sentMessages.any { it.contains("Network.enable") })
        val net = parseIdFor("Network.enable")
        assertTrue(transport.sentMessages.last { it.contains("Network.enable") }.contains("\"sessionId\":\"session-1\""))
        transport.deliverMessage("""{"id":$net,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })
    }

    @Test
    fun detach_removesSubscriptionsAndClearsTargetState() {
        bind(session)
        deliverRequest(session, "req-1", "https://example.com/")
        awaitRequestCount(1)

        engine.detach()
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.INACTIVE })
        assertNull(engine.session)
        assertEquals(0, engine.state.requests.size)
        assertNull(engine.state.sessionId)

        // Events delivered after detach (subscriptions removed / session cleared) are ignored.
        deliverRequest(session, "req-2", "https://after.com/")
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
    }

    // ============ target switching ============

    @Test
    fun targetSwitch_clearsOldRequestsAndRejectsOldSessionEvents() {
        bind(session)
        deliverRequest(session, "req-old", "https://old.com/")
        awaitRequestCount(1)

        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        bind(newSession)
        assertEquals(0, engine.state.requests.size)
        assertEquals("session-2", engine.state.sessionId)
        assertEquals("target-2", engine.state.targetId)

        // Events from the OLD session must never become the new target's requests.
        deliverRequest(session, "req-leak", "https://leak.com/")
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)

        // Events from the NEW session are accepted and associated correctly.
        deliverRequest(newSession, "req-new", "https://new.com/")
        awaitRequestCount(1)
        assertEquals("session-2", engine.state.requests[0].sessionId)
        assertEquals("target-2", engine.state.requests[0].targetId)
    }

    @Test
    fun targetSwitch_delayedEnableResponseFromOldSessionIsDropped() {
        // Bind to session-1 but do NOT respond to its enable command yet.
        engine.bindSession(session)
        val net1 = parseIdFor("Network.enable")

        // Switch to session-2 before the old response arrives.
        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(newSession)
        val net2 = parseIdFor("Network.enable")

        // Deliver the OLD session's enable response — must be ignored.
        transport.deliverMessage("""{"id":$net1,"sessionId":"session-1","result":{}}""")
        Thread.sleep(200)
        assertEquals("old enable response must not flip the new session", NetworkLifecyclePhase.ENABLING, engine.state.phase)
        assertEquals("session-2", engine.state.sessionId)

        // Deliver the NEW session's enable response → READY.
        transport.deliverMessage("""{"id":$net2,"sessionId":"session-2","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })
    }

    // ============ disconnect / reconnect (re-binding) ============

    @Test
    fun reconnect_rebindsDomainsAndAcceptsNewSessionEvents() {
        bind(session)
        deliverRequest(session, "before", "https://before.com/")
        awaitRequestCount(1)

        // Disconnect clears target-scoped state.
        engine.detach()
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.INACTIVE })
        assertEquals(0, engine.state.requests.size)

        // Reconnect: a fresh session (fresh connection) re-binds cleanly.
        val reconnectSession = SessionInfo("session-3", "target-3", "ws://test")
        bind(reconnectSession)
        assertEquals(0, engine.state.requests.size)
        assertEquals("session-3", engine.state.sessionId)

        deliverRequest(reconnectSession, "after", "https://after.com/")
        awaitRequestCount(1)
        assertEquals("session-3", engine.state.requests[0].sessionId)
        assertEquals("https://after.com/", engine.state.requests[0].url)
    }

    @Test
    fun reconnect_staleOldSessionEventIsRejected() {
        bind(session)
        deliverRequest(session, "old", "https://old.com/")
        awaitRequestCount(1)

        // Disconnect, then reconnect to a fresh session.
        engine.detach()
        val reconnectSession = SessionInfo("session-3", "target-3", "ws://test")
        bind(reconnectSession)

        // A delayed old-session event must be rejected by the new session.
        deliverRequest(session, "stale-old", "https://stale.com/")
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)

        deliverRequest(reconnectSession, "fresh", "https://fresh.com/")
        awaitRequestCount(1)
        assertEquals(listOf("https://fresh.com/"), engine.state.requests.map { it.url })
    }

    // ============ stale / re-bind robustness ============

    @Test
    fun rebind_doesNotThrowOnEventsFromPriorSession() {
        bind(session)
        deliverRequest(session, "req1", "https://example.com/")
        awaitRequestCount(1)

        val newSession = SessionInfo("session-2", "target-2", "ws://test")
        bind(newSession)

        // A burst of stale events from the old session must be ignored, not crash.
        for (i in 1..5) {
            deliverRequest(session, "stale-$i", "https://stale-$i.com/")
        }
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
        assertEquals(NetworkLifecyclePhase.READY, engine.state.phase)
    }

    @Test
    fun repeatedBind_clearsStoreEachTime() {
        bind(session)
        deliverRequest(session, "req1", "https://one.com/")
        awaitRequestCount(1)

        // Re-bind to the same session (e.g. re-enable) clears prior entries.
        bind(session)
        assertEquals(0, engine.state.requests.size)
        deliverRequest(session, "req2", "https://two.com/")
        awaitRequestCount(1)
        assertEquals(listOf("https://two.com/"), engine.state.requests.map { it.url })
    }
}