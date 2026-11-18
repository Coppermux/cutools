package com.coppermux.cutools.core.browser

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.network.NetworkEngine
import com.coppermux.cutools.core.network.NetworkLifecyclePhase
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration tests for the CDP-first DevTools Dashboard network-event pipeline.
 *
 * Verifies that:
 * 1. DevTools Dashboard obtains response headers exclusively from genuine CDP
 *    `Network.responseReceived` events.
 * 2. Requests are correlated and lifecycle-managed using native Chromium request IDs.
 * 3. Lifecycle updates (requestWillBeSent -> responseReceived -> loadingFinished)
 *    mutate the existing Dashboard card in-place without duplicate card creation.
 * 4. HTTP redirects and repeated requests to identical URLs are handled deterministically.
 * 5. Request failures and incomplete events surface honest error states.
 * 6. Phase 19 fault injections (mock body and dropped connections) are represented
 *    faithfully via CDP events without WebView-generated duplicates.
 * 7. Session detachment and reconnection do not leave duplicate event listeners.
 */
class CdpDashboardNetworkPipelineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var eventStream: DashboardEventStream
    private lateinit var networkEngine: NetworkEngine

    private val session1 = SessionInfo("session-1", "target-1", "ws://test/1")
    private val session2 = SessionInfo("session-2", "target-1", "ws://test/2")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        eventStream = DashboardEventStream()
        networkEngine = NetworkEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            dashboardEventStream = eventStream,
        )
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return condition()
    }

    private fun bindSession(sess: SessionInfo = session1) {
        networkEngine.bindSession(sess)
        val id = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"${sess.sessionId}","result":{}}""")
        assertTrue(waitFor { networkEngine.state.phase == NetworkLifecyclePhase.READY })
    }

    private fun deliverEvent(method: String, params: Any?, sess: SessionInfo = session1) {
        router.handleEvent(Event(method, params, sessionId = sess.sessionId, targetId = sess.targetId))
    }

    // ================================================================
    // Genuine Response Header Propagation
    // ================================================================

    @Test
    fun responseHeaders_arePopulatedFromCdpResponseReceived() {
        bindSession()

        // 1. requestWillBeSent creates the in-flight card
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf(
                "requestId" to "req-100",
                "request" to mapOf(
                    "url" to "https://api.example.com/profile",
                    "method" to "GET",
                    "headers" to mapOf("Accept" to "application/json"),
                ),
                "timestamp" to 1000.0,
                "type" to "XHR",
            ),
        )

        assertTrue(waitFor { eventStream.state.eventCount >= 1 })
        val initialEvent = eventStream.state.events.first()
        assertEquals("req-100", initialEvent.requestId)
        assertTrue(initialEvent.responseHeaders.isEmpty())

        // 2. responseReceived populates genuine response headers
        val genuineHeaders = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Server" to "envoy",
            "ETag" to "W/\"12345\"",
            "X-RateLimit-Remaining" to "99",
        )
        deliverEvent(
            "Network.responseReceived",
            mapOf(
                "requestId" to "req-100",
                "type" to "XHR",
                "response" to mapOf(
                    "url" to "https://api.example.com/profile",
                    "status" to 200,
                    "statusText" to "OK",
                    "mimeType" to "application/json",
                    "headers" to genuineHeaders,
                ),
            ),
        )

        // Still exactly 1 event (updated in-place)!
        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.responseHeaders?.isNotEmpty() == true })
        assertEquals(1, eventStream.state.eventCount)
        val updatedEvent = eventStream.state.events.first()
        assertEquals("req-100", updatedEvent.requestId)
        assertEquals(200, updatedEvent.statusCode)
        assertEquals("OK", updatedEvent.statusText)
        assertEquals(genuineHeaders, updatedEvent.responseHeaders)
        assertFalse(updatedEvent.responseHeaders.isEmpty())
        assertEquals("envoy", updatedEvent.responseHeaders["Server"])
    }

    // ================================================================
    // Full Lifecycle Updates & ID Preservation
    // ================================================================

    @Test
    fun requestLifecycle_updatesSingleCardInPlace() {
        bindSession()

        deliverEvent(
            "Network.requestWillBeSent",
            mapOf(
                "requestId" to "req-lifecycle",
                "request" to mapOf("url" to "https://example.com/test", "method" to "GET"),
                "timestamp" to 1000.0,
                "type" to "Fetch",
            ),
        )
        assertTrue(waitFor { eventStream.state.eventCount >= 1 })
        val idAtStart = eventStream.state.events.first().id

        deliverEvent(
            "Network.responseReceived",
            mapOf(
                "requestId" to "req-lifecycle",
                "type" to "Fetch",
                "response" to mapOf(
                    "url" to "https://example.com/test",
                    "status" to 200,
                    "headers" to mapOf("Cache-Control" to "no-cache"),
                ),
                "timing" to mapOf(
                    "receiveHeadersEnd" to 142.0,
                ),
            ),
        )

        deliverEvent(
            "Network.loadingFinished",
            mapOf(
                "requestId" to "req-lifecycle",
                "encodedDataLength" to 2048,
            ),
        )

        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.statusCode == 200 })
        assertEquals(1, eventStream.state.eventCount)
        val finalEvent = eventStream.state.events.first()
        assertEquals(idAtStart, finalEvent.id)
        assertEquals("req-lifecycle", finalEvent.requestId)
        assertEquals(200, finalEvent.statusCode)
        assertEquals(142L, finalEvent.durationMs)
    }

    // ================================================================
    // Repeated URLs & Redirects
    // ================================================================

    @Test
    fun repeatedRequestsToSameUrl_maintainSeparateDashboardCards() {
        bindSession()

        // First request to /poll
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "poll-1", "request" to mapOf("url" to "https://example.com/poll", "method" to "GET"), "type" to "XHR"),
        )
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "poll-1", "type" to "XHR", "response" to mapOf("url" to "https://example.com/poll", "status" to 200, "headers" to mapOf("poll" to "1"))),
        )
        assertTrue(waitFor { eventStream.state.eventCount >= 1 })

        // Second request to identical URL /poll with new requestId
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "poll-2", "request" to mapOf("url" to "https://example.com/poll", "method" to "GET"), "type" to "XHR"),
        )
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "poll-2", "type" to "XHR", "response" to mapOf("url" to "https://example.com/poll", "status" to 200, "headers" to mapOf("poll" to "2"))),
        )

        assertTrue(waitFor { eventStream.state.events.getOrNull(1)?.responseHeaders?.isNotEmpty() == true })
        assertEquals(2, eventStream.state.eventCount)
        assertEquals("poll-1", eventStream.state.events[0].requestId)
        assertEquals(mapOf("poll" to "1"), eventStream.state.events[0].responseHeaders)
        assertEquals("poll-2", eventStream.state.events[1].requestId)
        assertEquals(mapOf("poll" to "2"), eventStream.state.events[1].responseHeaders)
    }

    @Test
    fun redirect_updatesExistingCardWithRedirectMetadata() {
        bindSession()

        // Initial request to /short
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "redir-1", "request" to mapOf("url" to "https://example.com/short", "method" to "GET"), "type" to "Document"),
        )
        assertTrue(waitFor { eventStream.state.eventCount >= 1 })

        // Redirect response to /destination
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf(
                "requestId" to "redir-1",
                "request" to mapOf("url" to "https://example.com/destination", "method" to "GET"),
                "redirectResponse" to mapOf(
                    "url" to "https://example.com/short",
                    "status" to 301,
                    "statusText" to "Moved Permanently",
                    "headers" to mapOf("Location" to "https://example.com/destination"),
                ),
                "type" to "Document",
            ),
        )

        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.statusCode == 301 })
        assertEquals(1, eventStream.state.eventCount)
        val event = eventStream.state.events.first()
        assertEquals("redir-1", event.requestId)
        assertEquals(301, event.statusCode)
        assertEquals(DashboardSeverity.WARNING, event.severity)
        assertTrue(event.detail.orEmpty().contains("Redirected from https://example.com/short (301)"))
    }

    // ================================================================
    // Failed Requests
    // ================================================================

    @Test
    fun loadingFailed_surfacesHonestErrorState() {
        bindSession()

        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "fail-1", "request" to mapOf("url" to "https://broken.domain/api", "method" to "GET"), "type" to "XHR"),
        )
        assertTrue(waitFor { eventStream.state.eventCount >= 1 })

        deliverEvent(
            "Network.loadingFailed",
            mapOf(
                "requestId" to "fail-1",
                "errorText" to "net::ERR_NAME_NOT_RESOLVED",
                "canceled" to false,
            ),
        )

        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.kind == "FAILED" })
        assertEquals(1, eventStream.state.eventCount)
        val event = eventStream.state.events.first()
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("FAILED", event.kind)
        assertEquals("net::ERR_NAME_NOT_RESOLVED", event.detail)
    }

    // ================================================================
    // Phase 19 Fault Injection via CDP Pipeline
    // ================================================================

    @Test
    fun faultInjection_mockOverrideSurfacesViaCdpWithoutDuplicates() {
        bindSession()

        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "fault-mock-1", "request" to mapOf("url" to "https://example.com/api/test", "method" to "GET"), "type" to "XHR"),
        )
        assertTrue(waitFor { eventStream.state.eventCount >= 1 })

        deliverEvent(
            "Network.responseReceived",
            mapOf(
                "requestId" to "fault-mock-1",
                "type" to "XHR",
                "response" to mapOf(
                    "url" to "https://example.com/api/test",
                    "status" to 200,
                    "headers" to mapOf("X-CuTools-Fault" to "injected", "Content-Type" to "application/json"),
                ),
            ),
        )

        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.kind == "FAULT MOCK" })
        assertEquals(1, eventStream.state.eventCount)
        val event = eventStream.state.events.first()
        assertEquals("FAULT MOCK", event.kind)
        assertEquals("mock body injected", event.detail)
        assertEquals("injected", event.responseHeaders["X-CuTools-Fault"])
    }

    @Test
    fun faultInjection_connectionDropSurfacesAsConnectionDropBadge() {
        bindSession()

        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "fault-drop-1", "request" to mapOf("url" to "https://example.com/api/test", "method" to "GET"), "type" to "XHR"),
        )
        assertTrue(waitFor { eventStream.state.eventCount >= 1 })

        deliverEvent(
            "Network.loadingFailed",
            mapOf(
                "requestId" to "fault-drop-1",
                "errorText" to "net::ERR_CONNECTION_RESET (CuTools simulated connection drop)",
                "canceled" to false,
            ),
        )

        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.kind == "CONNECTION DROP" })
        assertEquals(1, eventStream.state.eventCount)
        val event = eventStream.state.events.first()
        assertEquals(DashboardSeverity.ERROR, event.severity)
        assertEquals("CONNECTION DROP", event.kind)
    }

    // ================================================================
    // Reconnection & Session Transitions
    // ================================================================

    @Test
    fun reconnection_doesNotDuplicateListenersOrEvents() {
        // Initial session bind
        bindSession(session1)

        deliverEvent("Network.requestWillBeSent", mapOf("requestId" to "s1-r1", "request" to mapOf("url" to "https://a.com", "method" to "GET")), sess = session1)
        assertTrue(waitFor { eventStream.state.eventCount == 1 })

        // Detach session
        networkEngine.detach()

        // Reconnect with new session
        bindSession(session2)

        deliverEvent("Network.requestWillBeSent", mapOf("requestId" to "s2-r1", "request" to mapOf("url" to "https://b.com", "method" to "GET")), sess = session2)

        // Exactly 1 new event added (total 2 in stream)
        assertTrue(waitFor { eventStream.state.eventCount == 2 })

        // Events from old session are ignored
        deliverEvent("Network.requestWillBeSent", mapOf("requestId" to "s1-r2", "request" to mapOf("url" to "https://c.com", "method" to "GET")), sess = session1)
        Thread.sleep(50)
        assertEquals(2, eventStream.state.eventCount)
    }

    // ================================================================
    // Pipeline Reset & Ghost Event Mitigation
    // ================================================================

    @Test
    fun pipelineReset_dropsLateEventsForPreResetRequests_withoutResurrectingCards() {
        bindSession()

        // 1. In-flight request initiated before reset
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "pre-1", "request" to mapOf("url" to "https://example.com/inflight", "method" to "GET")),
        )
        assertTrue(waitFor { eventStream.state.eventCount == 1 })
        assertTrue(waitFor { networkEngine.state.requests.size == 1 })

        // 2. Coordinated reset of network storage and dashboard stream
        networkEngine.clear()
        assertTrue(waitFor { eventStream.state.eventCount == 0 })
        assertTrue(waitFor { networkEngine.state.requests.isEmpty() })

        // 3. Late completion events arrive for the pre-reset request
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "pre-1", "response" to mapOf("url" to "https://example.com/inflight", "status" to 200, "headers" to mapOf("content-type" to "application/json"))),
        )
        deliverEvent(
            "Network.loadingFinished",
            mapOf("requestId" to "pre-1", "time" to 2000.0),
        )

        Thread.sleep(100)

        // 4. Assert zero ghost cards were resurrected and network engine remains clean
        assertEquals(0, eventStream.state.eventCount)
        assertTrue(networkEngine.state.requests.isEmpty())
    }

    @Test
    fun pipelineReset_allowsGenuinelyNewRequestsAfterReset() {
        bindSession()

        // 1. Initial request before reset
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "pre-2", "request" to mapOf("url" to "https://example.com/old", "method" to "GET")),
        )
        assertTrue(waitFor { eventStream.state.eventCount == 1 })

        // 2. Reset
        networkEngine.clear()
        assertTrue(waitFor { eventStream.state.eventCount == 0 })

        // 3. Genuinely new request initiated after reset
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "post-1", "request" to mapOf("url" to "https://example.com/new", "method" to "GET")),
        )
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "post-1", "response" to mapOf("url" to "https://example.com/new", "status" to 200, "headers" to mapOf("x-fresh" to "true"))),
        )
        deliverEvent(
            "Network.loadingFinished",
            mapOf("requestId" to "post-1", "time" to 3000.0),
        )

        // 4. Recorded normally with response headers and finished stage
        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.responseHeaders?.isNotEmpty() == true })
        val card = eventStream.state.events.first()
        assertEquals("post-1", card.requestId)
        assertEquals("https://example.com/new", card.url)
        assertEquals(mapOf("x-fresh" to "true"), card.responseHeaders)
        assertEquals(200, card.statusCode)

        assertTrue(waitFor { networkEngine.state.requests.any { it.requestId == "post-1" && it.isFinished } })
        val model = networkEngine.state.requests.first { it.requestId == "post-1" }
        assertEquals("post-1", model.requestId)
        assertTrue(model.isFinished)
    }

    @Test
    fun pipelineReset_allowsReusedRequestIdAfterReset() {
        bindSession()

        // 1. Request with reused-id before reset
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "reused-100", "request" to mapOf("url" to "https://example.com/first", "method" to "GET")),
        )
        assertTrue(waitFor { eventStream.state.eventCount == 1 })

        // 2. Reset
        networkEngine.clear()
        assertTrue(waitFor { eventStream.state.eventCount == 0 })

        // 3. Late response for old lifecycle is dropped
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "reused-100", "response" to mapOf("url" to "https://example.com/first", "status" to 200)),
        )
        networkEngine.drain()
        assertEquals(0, eventStream.state.eventCount)

        // 4. New request starting with the same ID is NOT suppressed
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "reused-100", "request" to mapOf("url" to "https://example.com/second", "method" to "POST")),
        )
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "reused-100", "response" to mapOf("url" to "https://example.com/second", "status" to 201)),
        )

        assertTrue(waitFor { eventStream.state.events.firstOrNull()?.statusCode == 201 })
        val card = eventStream.state.events.first()
        assertEquals("https://example.com/second", card.url)
        assertEquals(201, card.statusCode)
    }

    @Test
    fun pipelineReset_preResetEventsQueuedBeforeClear_cannotRepopulateDashboard() {
        val testExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-network").apply { isDaemon = true }
        }
        val customEngine = NetworkEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            dashboardEventStream = eventStream,
            executor = testExecutor,
        )
        customEngine.bindSession(session1)
        val id = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { customEngine.state.phase == NetworkLifecyclePhase.READY })

        val blockLatch = java.util.concurrent.CountDownLatch(1)
        val enteredBlock = java.util.concurrent.CountDownLatch(1)
        testExecutor.execute {
            enteredBlock.countDown()
            blockLatch.await()
        }
        assertTrue(enteredBlock.await(3, java.util.concurrent.TimeUnit.SECONDS))

        // Deliver an event while executor is blocked.
        // onNetworkEvent captures ingressGeneration synchronously under ingressLock and queues task.
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "queued-pre-1", "request" to mapOf("url" to "https://example.com/queued", "method" to "GET")),
            sess = session1,
        )

        // Coordinated reset before the executor processes the event:
        customEngine.clear()

        // Unblock executor and wait for all tasks to drain
        blockLatch.countDown()
        testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

        // Verify that the queued pre-reset event was discarded and did not repopulate Dashboard or Engine
        assertEquals(0, eventStream.state.eventCount)
        assertTrue(customEngine.state.requests.isEmpty())

        // Late responseReceived arriving post-reset for the pre-reset request must also be dropped
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "queued-pre-1", "response" to mapOf("url" to "https://example.com/queued", "status" to 200)),
            sess = session1,
        )
        testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("Late responseReceived for pre-reset request must not resurrect card", 0, eventStream.state.eventCount)
        assertTrue(customEngine.state.requests.isEmpty())

        // Genuine post-reset request must be accepted normally
        deliverEvent(
            "Network.requestWillBeSent",
            mapOf("requestId" to "post-fresh-1", "request" to mapOf("url" to "https://example.com/fresh", "method" to "GET")),
            sess = session1,
        )
        deliverEvent(
            "Network.responseReceived",
            mapOf("requestId" to "post-fresh-1", "response" to mapOf("url" to "https://example.com/fresh", "status" to 200, "headers" to mapOf("x-fresh" to "1"))),
            sess = session1,
        )
        testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("Post-reset request must create exactly 1 event card", 1, eventStream.state.eventCount)
        assertEquals("post-fresh-1", eventStream.state.events.first().requestId)
        assertEquals(200, eventStream.state.events.first().statusCode)

        customEngine.shutdown()
        testExecutor.shutdown()
    }

    @Test
    fun pipelineReset_oldResponseQueuedBeforeReset_doesNotModifyReusedRequestId() {
        val testExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-network").apply { isDaemon = true }
        }
        val customEngine = NetworkEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            dashboardEventStream = eventStream,
            executor = testExecutor,
        )
        val blockLatch = java.util.concurrent.CountDownLatch(1)
        val enteredBlock = java.util.concurrent.CountDownLatch(1)
        try {
            customEngine.bindSession(session1)
            val id = parseIdFor("Network.enable")
            transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
            assertTrue(waitFor { customEngine.state.phase == NetworkLifecyclePhase.READY })

            // 1. Initial request with id "reused-id"
            deliverEvent(
                "Network.requestWillBeSent",
                mapOf("requestId" to "reused-id", "request" to mapOf("url" to "https://example.com/old", "method" to "GET")),
                sess = session1,
            )
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, eventStream.state.eventCount)
            assertEquals("https://example.com/old", eventStream.state.events.first().url)

            // 2. Pause the executor to queue an old response before Reset
            testExecutor.execute {
                enteredBlock.countDown()
                blockLatch.await()
            }
            assertTrue(enteredBlock.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // 3. Queue an old response for "reused-id" (captured at pre-reset generation)
            deliverEvent(
                "Network.responseReceived",
                mapOf("requestId" to "reused-id", "response" to mapOf("url" to "https://example.com/old", "status" to 200, "headers" to mapOf("x-old" to "true"))),
                sess = session1,
            )

            // 4. Coordinated Reset
            customEngine.clear()
            assertEquals(0, eventStream.state.eventCount)
            assertTrue(customEngine.state.requests.isEmpty())

            // 5. Post-reset new request reusing the same request ID "reused-id"
            deliverEvent(
                "Network.requestWillBeSent",
                mapOf("requestId" to "reused-id", "request" to mapOf("url" to "https://example.com/new", "method" to "POST")),
                sess = session1,
            )
            // Post-reset response for the new request
            deliverEvent(
                "Network.responseReceived",
                mapOf("requestId" to "reused-id", "response" to mapOf("url" to "https://example.com/new", "status" to 201, "headers" to mapOf("x-new" to "true"))),
                sess = session1,
            )

            // 6. Unblock executor
            blockLatch.countDown()
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

            // 7. Verification: The old response must not modify the new request or dashboard entry
            assertEquals("Exactly 1 event card must exist post-reset", 1, eventStream.state.eventCount)
            val card = eventStream.state.events.first()
            assertEquals("reused-id", card.requestId)
            assertEquals("https://example.com/new", card.url)
            assertEquals(201, card.statusCode)
            assertEquals("POST", card.method)
            assertEquals(mapOf("x-new" to "true"), card.responseHeaders)

            val model = customEngine.state.requests.first()
            assertEquals("reused-id", model.requestId)
            assertEquals("https://example.com/new", model.url)
            assertEquals(201, model.responseStatus)
        } finally {
            blockLatch.countDown()
            customEngine.shutdown()
            testExecutor.shutdownNow()
        }
    }

    @Test
    fun pipelineReset_resetAtBoundaryBetweenEventProcessingAndDashboardPublication_preventsRepopulation() {
        val testExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-network").apply { isDaemon = true }
        }
        val customEngine = NetworkEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            dashboardEventStream = eventStream,
            executor = testExecutor,
        )
        val resetThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-reset").apply { isDaemon = true }
        }
        val reachedBoundary = java.util.concurrent.CountDownLatch(1)
        val continuePublication = java.util.concurrent.CountDownLatch(1)
        val resetFinished = java.util.concurrent.CountDownLatch(1)
        try {
            customEngine.bindSession(session1)
            val id = parseIdFor("Network.enable")
            transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
            assertTrue(waitFor { customEngine.state.phase == NetworkLifecyclePhase.READY })

            customEngine.preDashboardPublicationHook = {
                reachedBoundary.countDown()
                continuePublication.await()
            }

            // Deliver an event to be processed
            deliverEvent(
                "Network.requestWillBeSent",
                mapOf("requestId" to "boundary-1", "request" to mapOf("url" to "https://example.com/boundary", "method" to "GET")),
                sess = session1,
            )

            // Wait until event processing reaches the boundary (listener.apply completed)
            assertTrue(reachedBoundary.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // Invoke reset on a concurrent thread while at the publication boundary
            resetThread.execute {
                customEngine.clear()
                resetFinished.countDown()
            }

            // Release the publication boundary. mutationLock ensures serialization with clear().
            continuePublication.countDown()

            // Wait for reset to finish
            assertTrue(resetFinished.await(3, java.util.concurrent.TimeUnit.SECONDS))
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

            // Verification: once Reset completes, no pre-reset event may repopulate the dashboard or request state
            assertEquals("No pre-reset event may repopulate dashboard after reset", 0, eventStream.state.eventCount)
            assertTrue("No pre-reset requests may remain in state after reset", customEngine.state.requests.isEmpty())

            // A normal post-reset request with a new ID works
            deliverEvent(
                "Network.requestWillBeSent",
                mapOf("requestId" to "post-boundary-1", "request" to mapOf("url" to "https://example.com/post-boundary", "method" to "GET")),
                sess = session1,
            )
            deliverEvent(
                "Network.responseReceived",
                mapOf("requestId" to "post-boundary-1", "response" to mapOf("url" to "https://example.com/post-boundary", "status" to 200)),
                sess = session1,
            )
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("Post-reset request must be published to dashboard", 1, eventStream.state.eventCount)
            assertEquals("post-boundary-1", eventStream.state.events.first().requestId)
            assertEquals(200, eventStream.state.events.first().statusCode)
            assertEquals(1, customEngine.state.requests.size)
        } finally {
            reachedBoundary.countDown()
            continuePublication.countDown()
            resetFinished.countDown()
            customEngine.preDashboardPublicationHook = null
            customEngine.shutdown()
            testExecutor.shutdownNow()
            resetThread.shutdownNow()
        }
    }

    @Test
    fun pipelineReset_reusedRequestIdIngressAtClearGenerationBoundary_acceptedWithoutStaleCorruption() {
        val testExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-network").apply { isDaemon = true }
        }
        val customEngine = NetworkEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            dashboardEventStream = eventStream,
            executor = testExecutor,
        )
        val resetExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-reset").apply { isDaemon = true }
        }

        val executorBlockLatch = java.util.concurrent.CountDownLatch(1)
        val executorEnteredBlock = java.util.concurrent.CountDownLatch(1)
        val boundaryReachedLatch = java.util.concurrent.CountDownLatch(1)
        val continueResetLatch = java.util.concurrent.CountDownLatch(1)
        val ingressStartedLatch = java.util.concurrent.CountDownLatch(1)
        val ingressFinishedLatch = java.util.concurrent.CountDownLatch(1)
        val resetFinishedLatch = java.util.concurrent.CountDownLatch(1)

        val reusedRequestId = "reused-boundary-id"
        var ingressThread: Thread? = null

        try {
            customEngine.bindSession(session1)
            val id = parseIdFor("Network.enable")
            transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
            assertTrue(waitFor { customEngine.state.phase == NetworkLifecyclePhase.READY })

            // 1. Create an existing request before reset and confirm its store/dashboard entry exists
            deliverEvent(
                "Network.requestWillBeSent",
                mapOf(
                    "requestId" to reusedRequestId,
                    "request" to mapOf("url" to "https://example.com/pre-reset", "method" to "GET"),
                ),
                sess = session1,
            )
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, eventStream.state.eventCount)
            assertEquals("https://example.com/pre-reset", eventStream.state.events.first().url)
            assertEquals(1, customEngine.state.requests.size)
            assertEquals(reusedRequestId, customEngine.state.requests.first().requestId)

            // 2. Pause the executor to queue an old response before Reset (Requirement 6)
            testExecutor.execute {
                executorEnteredBlock.countDown()
                executorBlockLatch.await()
            }
            assertTrue(executorEnteredBlock.await(3, java.util.concurrent.TimeUnit.SECONDS))

            deliverEvent(
                "Network.responseReceived",
                mapOf(
                    "requestId" to reusedRequestId,
                    "response" to mapOf(
                        "url" to "https://example.com/pre-reset",
                        "status" to 200,
                        "headers" to mapOf("x-old-pre" to "stale-val"),
                    ),
                ),
                sess = session1,
            )

            // 3. Set hook to capture the clear() generation boundary
            customEngine.clearGenerationBoundaryHook = {
                boundaryReachedLatch.countDown()
                continueResetLatch.await()
            }

            // 4. Begin Reset on resetExecutor
            resetExecutor.execute {
                customEngine.clear()
                resetFinishedLatch.countDown()
            }
            assertTrue(boundaryReachedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // 5. While Reset has advanced its generation boundary under ingressLock, attempt ingress
            // of a legitimate post-reset request reusing the same request ID
            ingressThread = Thread({
                ingressStartedLatch.countDown()
                deliverEvent(
                    "Network.requestWillBeSent",
                    mapOf(
                        "requestId" to reusedRequestId,
                        "request" to mapOf("url" to "https://example.com/post-fresh", "method" to "POST"),
                    ),
                    sess = session1,
                )
                ingressFinishedLatch.countDown()
            }, "test-cutools-ingress").apply {
                isDaemon = true
                start()
            }

            assertTrue(ingressStartedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // Wait deterministically for ingressThread to reach ingressLock and become BLOCKED
            val deadline = System.currentTimeMillis() + 3000
            while (ingressThread.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
                Thread.yield()
            }
            assertEquals("ingressThread must be serialized on ingressLock", Thread.State.BLOCKED, ingressThread.state)

            // 6. Release Reset to complete stale-ID bookkeeping under ingressLock
            continueResetLatch.countDown()
            assertTrue(ingressFinishedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(resetFinishedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // 7. Unblock the executor to process queued events
            executorBlockLatch.countDown()
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

            // 8. Deliver response for the new post-reset request
            deliverEvent(
                "Network.responseReceived",
                mapOf(
                    "requestId" to reusedRequestId,
                    "response" to mapOf(
                        "url" to "https://example.com/post-fresh",
                        "status" to 201,
                        "headers" to mapOf("x-fresh-header" to "valid-v2"),
                    ),
                ),
                sess = session1,
            )
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

            // 9. Verifications
            // Exactly 1 dashboard card exists, belonging to the new post-reset request (no resurrecting stale pre-reset card)
            assertEquals("Dashboard must contain exactly 1 event card post-reset", 1, eventStream.state.eventCount)
            val card = eventStream.state.events.first()
            assertEquals(reusedRequestId, card.requestId)
            assertEquals("https://example.com/post-fresh", card.url)
            assertEquals("POST", card.method)
            assertEquals(201, card.statusCode)
            assertEquals(mapOf("x-fresh-header" to "valid-v2"), card.responseHeaders)
            assertFalse("Stale response header must not corrupt post-reset request", card.responseHeaders.containsKey("x-old-pre"))

            // Engine state contains only the post-reset request model
            assertEquals("Engine state must contain exactly 1 request model", 1, customEngine.state.requests.size)
            val model = customEngine.state.requests.first()
            assertEquals(reusedRequestId, model.requestId)
            assertEquals("https://example.com/post-fresh", model.url)
            assertEquals("POST", model.method)
            assertEquals(201, model.responseStatus)
            assertEquals(mapOf("x-fresh-header" to "valid-v2"), model.responseHeaders)
        } finally {
            executorBlockLatch.countDown()
            boundaryReachedLatch.countDown()
            continueResetLatch.countDown()
            customEngine.clearGenerationBoundaryHook = null
            customEngine.shutdown()
            testExecutor.shutdownNow()
            resetExecutor.shutdownNow()
            ingressThread?.join(3000)
        }
    }

    @Test
    fun pipelineReset_postResetEventDuringResetInterleaving_preservesPostResetEventAndDropsPreReset() {
        val testExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-network").apply { isDaemon = true }
        }
        val customEngine = NetworkEngine(
            dispatcher = dispatcher,
            eventRouter = router,
            dashboardEventStream = eventStream,
            executor = testExecutor,
        )
        val resetExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cutools-reset").apply { isDaemon = true }
        }

        val executorBlockLatch = java.util.concurrent.CountDownLatch(1)
        val executorEnteredBlock = java.util.concurrent.CountDownLatch(1)
        val generationBoundaryReachedLatch = java.util.concurrent.CountDownLatch(1)
        val continueGenerationLatch = java.util.concurrent.CountDownLatch(1)
        val mutationBoundaryReachedLatch = java.util.concurrent.CountDownLatch(1)
        val continueMutationLatch = java.util.concurrent.CountDownLatch(1)
        val ingressWaitingOnLockLatch = java.util.concurrent.CountDownLatch(1)
        val ingressStartedLatch = java.util.concurrent.CountDownLatch(1)
        val ingressFinishedLatch = java.util.concurrent.CountDownLatch(1)
        val resetFinishedLatch = java.util.concurrent.CountDownLatch(1)

        val preRequestId = "pre-req-interleaving"
        val postRequestId = "post-req-interleaving"
        var ingressThread: Thread? = null

        try {
            customEngine.bindSession(session1)
            val id = parseIdFor("Network.enable")
            transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
            assertTrue(waitFor { customEngine.state.phase == NetworkLifecyclePhase.READY })

            // 1. Initial pre-reset request
            deliverEvent(
                "Network.requestWillBeSent",
                mapOf("requestId" to preRequestId, "request" to mapOf("url" to "https://example.com/pre", "method" to "GET")),
                sess = session1,
            )
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, eventStream.state.eventCount)
            assertEquals(1, customEngine.state.requests.size)

            // 2. Pause executor to queue a pre-reset response before reset
            testExecutor.execute {
                executorEnteredBlock.countDown()
                executorBlockLatch.await()
            }
            assertTrue(executorEnteredBlock.await(3, java.util.concurrent.TimeUnit.SECONDS))

            deliverEvent(
                "Network.responseReceived",
                mapOf("requestId" to preRequestId, "response" to mapOf("url" to "https://example.com/pre", "status" to 200)),
                sess = session1,
            )

            // 3. Set hooks on customEngine:
            // - clearGenerationBoundaryHook holds ingressLock and mutationLock to verify ingress serialization.
            // - clearMutationBoundaryHook holds mutationLock after ingressLock is released, allowing post-reset ingress
            //   to queue on executor while mutationLock serializes clear() and store.clear()/stream.reset().
            // - preIngressLockHook explicitly signals when ingressThread reaches ingressLock without polling Thread.State.BLOCKED.
            customEngine.clearGenerationBoundaryHook = {
                generationBoundaryReachedLatch.countDown()
                continueGenerationLatch.await()
            }
            customEngine.clearMutationBoundaryHook = {
                mutationBoundaryReachedLatch.countDown()
                continueMutationLatch.await()
            }
            customEngine.preIngressLockHook = {
                ingressWaitingOnLockLatch.countDown()
            }

            // 4. Initiate clear on resetExecutor and wait until generation boundary inside ingressLock is reached
            resetExecutor.execute {
                customEngine.clear()
                resetFinishedLatch.countDown()
            }
            assertTrue(generationBoundaryReachedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // 5. While clear() has advanced generation under ingressLock, attempt ingress
            // of a legitimate post-reset request. Ingress reaches ingressLock and deterministically waits.
            ingressThread = Thread({
                ingressStartedLatch.countDown()
                deliverEvent(
                    "Network.requestWillBeSent",
                    mapOf("requestId" to postRequestId, "request" to mapOf("url" to "https://example.com/post", "method" to "POST")),
                    sess = session1,
                )
                ingressFinishedLatch.countDown()
            }, "test-cutools-ingress").apply {
                isDaemon = true
                start()
            }
            assertTrue("Ingress thread must start", ingressStartedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Ingress thread must arrive at ingressLock gate", ingressWaitingOnLockLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("ingressThread must be serialized on ingressLock", 1L, ingressFinishedLatch.count)

            // 6. Release generation boundary in clear(). clear() completes stale-ID bookkeeping and releases ingressLock,
            // then enters clearMutationBoundaryHook still holding mutationLock.
            continueGenerationLatch.countDown()
            assertTrue("clear() must reach mutation boundary under mutationLock", mutationBoundaryReachedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // Now that ingressLock is released, ingressThread completes stamping post-reset generation and enqueues on executor.
            assertTrue("ingressThread must complete ingress after clear() releases ingressLock", ingressFinishedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // 7. Unblock executor while clear() still holds mutationLock at clearMutationBoundaryHook.
            // Both pre-reset response and post-reset request are in testExecutor's queue, but testExecutor
            // is blocked trying to acquire mutationLock.
            executorBlockLatch.countDown()

            // 8. Release mutation boundary in clear() to execute store.clear() and coordinated dashboardEventStream.reset()
            // atomically under mutationLock. If stream reset were deferred outside mutationLock, testExecutor would acquire
            // mutationLock, publish post-reset event, and the subsequent separate stream reset would erase it.
            continueMutationLatch.countDown()
            assertTrue("clear() must finish under mutationLock", resetFinishedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

            // 9. Wait for testExecutor to process all queued events under mutationLock:
            // - Queued pre-reset response has ingressGeneration < activeGen, so it is dropped without resurrecting card.
            // - Post-reset request has ingressGeneration == activeGen, so it publishes to stream AFTER stream reset.
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

            // 10. Deliver post-reset response to complete the post-reset request
            deliverEvent(
                "Network.responseReceived",
                mapOf("requestId" to postRequestId, "response" to mapOf("url" to "https://example.com/post", "status" to 201)),
                sess = session1,
            )
            testExecutor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)

            // 11. Verifications:
            // - The pre-reset event must NOT resurrect the cleared request or dashboard card.
            // - The valid post-reset event MUST be visible and not erased by dashboard reset.
            assertEquals("Dashboard must contain exactly 1 event card post-reset", 1, eventStream.state.eventCount)
            val card = eventStream.state.events.first()
            assertEquals(postRequestId, card.requestId)
            assertEquals("https://example.com/post", card.url)
            assertEquals(201, card.statusCode)

            assertEquals("Engine state must contain exactly 1 request model", 1, customEngine.state.requests.size)
            val model = customEngine.state.requests.first()
            assertEquals(postRequestId, model.requestId)
            assertEquals("https://example.com/post", model.url)
            assertEquals(201, model.responseStatus)
        } finally {
            executorBlockLatch.countDown()
            generationBoundaryReachedLatch.countDown()
            continueGenerationLatch.countDown()
            mutationBoundaryReachedLatch.countDown()
            continueMutationLatch.countDown()
            ingressWaitingOnLockLatch.countDown()
            customEngine.clearGenerationBoundaryHook = null
            customEngine.clearMutationBoundaryHook = null
            customEngine.preIngressLockHook = null
            customEngine.shutdown()
            testExecutor.shutdownNow()
            resetExecutor.shutdownNow()
            ingressThread?.join(3000)
        }
    }
}
