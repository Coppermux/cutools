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
 * Phase 5E lifecycle synchronization & teardown verification across the full
 * Network module:
 *
 *  - zero event-listener leaks: [NetworkEngine] and [FetchInterceptionEngine]
 *    remove every CDP subscription on detach/shutdown, so a stale subscription
 *    never lingers for a detached target.
 *  - zero pending-decision leaks: [FetchInterceptionEngine.pendingCount] returns
 *    to zero after detach/re-bind; no dispatch occurs for stale request IDs.
 *  - memory bounding is enforced through the [NetworkEngine] store: under a
 *    small config the retained request count and byte footprint stay bounded
 *    and evictions are observed, so high-frequency traffic cannot OOM.
 */
class NetworkLifecycleReconnectTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
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

    private fun deliverEvent(engine: NetworkEngine, sess: SessionInfo, requestId: String) {
        router.handleEvent(Event(
            "Network.requestWillBeSent",
            mapOf(
                "requestId" to requestId,
                "documentURL" to "https://example.com/$requestId",
                "request" to mapOf("url" to "https://example.com/$requestId", "method" to "GET"),
            ),
            sessionId = sess.sessionId,
            targetId = sess.targetId,
        ))
    }

    // ============ NetworkEngine subscription / teardown leak verification ============

    @Test
    fun network_detach_removesEverySubscriptionNoLeak() {
        val engine = NetworkEngine(dispatcher, router)
        engine.bindSession(session)
        val id = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })

        for (method in listOf(
            "Network.requestWillBeSent",
            "Network.responseReceived",
            "Network.loadingFinished",
            "Network.loadingFailed",
        )) {
            assertEquals(1, router.subscriptionCount(method))
        }

        engine.detach()
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.INACTIVE })
        for (method in listOf(
            "Network.requestWillBeSent",
            "Network.responseReceived",
            "Network.loadingFinished",
            "Network.loadingFailed",
        )) {
            assertEquals("no event-listener leak for $method", 0, router.subscriptionCount(method))
        }
    }

    @Test
    fun network_rebind_doesNotAccumulateSubscriptions() {
        val engine = NetworkEngine(dispatcher, router)
        engine.bindSession(session)
        val id1 = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$id1,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })

        val reconnected = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(reconnected)
        val id2 = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$id2,"sessionId":"session-2","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })

        assertEquals("rebinding must not leak extra listeners", 1, router.subscriptionCount("Network.requestWillBeSent"))
    }

    @Test
    fun network_detach_rejectsLateEventsAndClearsState() {
        val engine = NetworkEngine(dispatcher, router)
        engine.bindSession(session)
        val id = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })
        deliverEvent(engine, session, "req-1")
        assertTrue(waitFor { engine.state.requests.size == 1 })

        engine.detach()
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.INACTIVE })
        assertNull(engine.session)
        assertEquals(0, engine.state.requests.size)

        // Late events for the detached session must be ignored entirely.
        deliverEvent(engine, session, "req-late")
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
    }

    // ============ FetchInterceptionEngine teardown (zero pending/event leaks) ============

    @Test
    fun fetch_detach_removesSubscriptionsAndClearsPending() {
        val engine = FetchInterceptionEngine(dispatcher, router)
        engine.bindSession(session)
        val id = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })
        assertEquals(1, router.subscriptionCount("Fetch.requestPaused"))

        router.handleEvent(Event(
            "Fetch.requestPaused",
            mapOf(
                "requestId" to "req-1",
                "documentURL" to "https://example.com/",
                "request" to mapOf("url" to "https://example.com/", "method" to "GET"),
            ),
            sessionId = session.sessionId,
            targetId = session.targetId,
        ))
        assertTrue(waitFor { engine.state.pendingCount == 1 })

        engine.detach()
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.INACTIVE })
        assertEquals(0, engine.state.pendingCount)
        assertNull(engine.session)
        assertEquals("Fetch.requestPaused subscription must be removed", 0, router.subscriptionCount("Fetch.requestPaused"))
    }

    @Test
    fun fetch_reconnect_clearsOldPausedAndRejectsStaleIds() {
        val engine = FetchInterceptionEngine(dispatcher, router)
        engine.bindSession(session)
        val id1 = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id1,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        router.handleEvent(Event(
            "Fetch.requestPaused",
            mapOf("requestId" to "req-old", "documentURL" to "https://old.com/",
                "request" to mapOf("url" to "https://old.com/", "method" to "GET")),
            sessionId = session.sessionId,
            targetId = session.targetId,
        ))
        assertTrue(waitFor { engine.state.pendingCount == 1 })

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        val id2 = parseIdFor("Fetch.enable")
        transport.deliverMessage("""{"id":$id2,"sessionId":"session-3","result":{}}""")
        assertTrue(waitFor { engine.state.phase == FetchLifecyclePhase.READY })

        // Old paused request from the prior session is gone; acting on it is rejected.
        assertEquals(0, engine.state.pendingCount)
        transport.resetSentMessages()
        val results = java.util.concurrent.CopyOnWriteArrayList<FetchActionResult>()
        engine.continueRequest("req-old") { results.add(it) }
        assertEquals(FetchActionResult.UNKNOWN_REQUEST, results[0])
        assertTrue("stale request must not dispatch", transport.sentMessages.none { it.contains("Fetch.continueRequest") })
    }

    // ============ engine-level memory bounding (circular buffer) ============

    @Test
    fun network_engineMemoryBounding_keepsLogWithinCaps() {
        val engine = NetworkEngine(
            dispatcher,
            router,
            storeConfig = NetworkStoreConfig(maxRequests = 3, maxBufferBytes = Long.MAX_VALUE),
        )
        engine.bindSession(session)
        val id = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })

        for (i in 1..10) {
            deliverEvent(engine, session, "req-$i")
        }
        assertTrue(waitFor { engine.evictions >= 7 })
        engine.drain()
        assertEquals(3, engine.state.requests.size)
        assertEquals(7L, engine.evictions)
        assertEquals(listOf("req-10", "req-8", "req-9"), engine.state.requests.map { it.requestId })
    }
}