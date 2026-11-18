package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CdpNetworkEventListener] mapping the four genuine Chrome
 * `Network` lifecycle events into [NetworkRequestStore] model updates.
 */
class CdpNetworkEventListenerTest {

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    private fun event(method: String, params: Any?): Event =
        Event(method, params, sessionId = session.sessionId, targetId = session.targetId)

    private fun listener(): CdpNetworkEventListener = CdpNetworkEventListener(NetworkRequestStore())

    private fun requestWillBeSentParams(url: String = "https://example.com/path", requestId: String = "r1"): Map<String, Any?> =
        mapOf(
            "requestId" to requestId,
            "loaderId" to "loader-1",
            "documentURL" to url,
            "initiator" to "subresource",
            "type" to "xhr",
            "time" to 100.0,
            "request" to mapOf(
                "url" to url,
                "method" to "GET",
                "headers" to mapOf("Accept" to "*/*", "User-Agent" to "CuTools"),
            ),
        )

    @Test
    fun requestWillBeSent_createsPendingEntryWithChromeFields() {
        val store = NetworkRequestStore()
        listener(store).apply(event("Network.requestWillBeSent", requestWillBeSentParams()), session)
        val model = store.get("r1")!!
        assertEquals(NetworkRequestStage.PENDING, model.stage)
        assertEquals("https://example.com/path", model.url)
        assertEquals("GET", model.method)
        assertEquals(100.0, model.timestamp!!, 0.0001)
        assertEquals("subresource", model.initiatorType)
        assertEquals("xhr", model.resourceType)
        assertEquals("*/*", model.requestHeaders["Accept"])
        assertEquals("CuTools", model.requestHeaders["User-Agent"])
        assertEquals("session-1", model.sessionId)
        assertEquals("target-1", model.targetId)
    }

    @Test
    fun responseReceived_promotesExistingToResponseStage_withResponseFields() {
        val store = NetworkRequestStore()
        listener(store).apply(event("Network.requestWillBeSent", requestWillBeSentParams()), session)
        listener(store).apply(event("Network.responseReceived", mapOf(
            "requestId" to "r1",
            "type" to "xhr",
            "time" to 110.0,
            "response" to mapOf(
                "url" to "https://example.com/path",
                "status" to 200,
                "statusText" to "OK",
                "mimeType" to "application/json",
                "headers" to mapOf("Content-Type" to "application/json"),
            ),
        )), session)

        val model = store.get("r1")!!
        assertEquals(NetworkRequestStage.RESPONSE_RECEIVED, model.stage)
        assertTrue(model.hasResponse)
        assertEquals(200, model.responseStatus)
        assertEquals("OK", model.responseStatusText)
        assertEquals("application/json", model.responseMimeType)
        assertEquals("application/json", model.responseHeaders["Content-Type"])
        assertEquals("xhr", model.resourceType)
        // Request metadata is preserved.
        assertEquals("https://example.com/path", model.url)
        assertEquals("GET", model.method)
    }

    @Test
    fun loadingFinished_promotesToFinishedTerminal() {
        val store = NetworkRequestStore()
        listener(store).apply(event("Network.requestWillBeSent", requestWillBeSentParams()), session)
        listener(store).apply(event("Network.loadingFinished", mapOf("requestId" to "r1", "time" to 120.0)), session)
        val model = store.get("r1")!!
        assertEquals(NetworkRequestStage.FINISHED, model.stage)
        assertTrue(model.isFinished)
        assertTrue(model.isTerminal)
    }

    @Test
    fun loadingFailed_promotesToFailedWithErrorReason() {
        val store = NetworkRequestStore()
        listener(store).apply(event("Network.requestWillBeSent", requestWillBeSentParams()), session)
        listener(store).apply(event("Network.loadingFailed", mapOf(
            "requestId" to "r1",
            "time" to 130.0,
            "error" to "net::ERR_CONNECTION_RESET",
            "canceled" to false,
        )), session)
        val model = store.get("r1")!!
        assertEquals(NetworkRequestStage.FAILED, model.stage)
        assertTrue(model.isFailed)
        assertEquals("net::ERR_CONNECTION_RESET", model.loadingError)
        assertFalse(model.canceled)
    }

    @Test
    fun terminalStage_isNotRegressedByLaterEvents() {
        val store = NetworkRequestStore()
        val l = listener(store)
        l.apply(event("Network.requestWillBeSent", requestWillBeSentParams()), session)
        l.apply(event("Network.loadingFinished", mapOf("requestId" to "r1", "time" to 120.0)), session)
        assertEquals(NetworkRequestStage.FINISHED, store.get("r1")!!.stage)

        // A late responseReceived / requestWillBeSent must NOT downgrade it.
        l.apply(event("Network.responseReceived", mapOf("requestId" to "r1", "response" to mapOf("status" to 200))), session)
        l.apply(event("Network.requestWillBeSent", requestWillBeSentParams(requestId = "r1")), session)
        assertEquals(NetworkRequestStage.FINISHED, store.get("r1")!!.stage)
        assertTrue(store.get("r1")!!.isFinished)
    }

    @Test
    fun responseArrivingBeforeRequestStillCreatesEntry() {
        val store = NetworkRequestStore()
        listener(store).apply(event("Network.responseReceived", mapOf(
            "requestId" to "orphan",
            "time" to 50.0,
            "response" to mapOf("url" to "https://example.com/x", "status" to 404),
        )), session)
        val model = store.get("orphan")!!
        assertEquals(NetworkRequestStage.RESPONSE_RECEIVED, model.stage)
        assertEquals(404, model.responseStatus)
        assertEquals("https://example.com/x", model.url)
    }

    @Test
    fun postData_text_isPreserved() {
        val store = NetworkRequestStore()
        listener(store).apply(event("Network.requestWillBeSent", mapOf(
            "requestId" to "post",
            "request" to mapOf(
                "method" to "POST",
                "postData" to mapOf("text" to "hello=world"),
            ),
        )), session)
        assertEquals("hello=world", store.get("post")!!.postData)
    }

    @Test
    fun malformedParams_areToleratedWithoutMutation() {
        val store = NetworkRequestStore()
        val l = listener(store)
        // Non-map params.
        l.apply(event("Network.requestWillBeSent", "not-a-map"), session)
        // Map without a requestId.
        l.apply(event("Network.responseReceived", mapOf("type" to "xhr")), session)
        l.apply(event("Network.loadingFailed", mapOf("no-id-here" to 1)), session)
        assertEquals(0, store.size())
        assertNull(store.get("r1"))
    }

    @Test
    fun unknownNetworkMethod_isIgnored() {
        val store = NetworkRequestStore()
        listener(store).apply(event("Network.someOtherEvent", mapOf("requestId" to "r1")), session)
        assertEquals(0, store.size())
    }

    @Test
    fun multipleConcurrentEvents_forSameRequest_applySerially() {
        val store = NetworkRequestStore()
        val l = listener(store)
        // Simulate a burst of events for the same request applied in order.
        l.apply(event("Network.requestWillBeSent", requestWillBeSentParams()), session)
        l.apply(event("Network.responseReceived", mapOf(
            "requestId" to "r1", "time" to 110.0,
            "response" to mapOf("status" to 200, "mimeType" to "text/html"),
        )), session)
        l.apply(event("Network.loadingFinished", mapOf("requestId" to "r1", "time" to 120.0)), session)
        val model = store.get("r1")!!
        assertEquals(NetworkRequestStage.FINISHED, model.stage)
        assertNotNull(model)
        assertEquals(1, store.size())
        assertTrue(store.get("r1")!!.hasResponse)
    }

    private fun listener(store: NetworkRequestStore): CdpNetworkEventListener = CdpNetworkEventListener(store)
}