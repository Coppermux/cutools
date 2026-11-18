package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end protocol tests for Phase 5A: raw CDP JSON messages are delivered
 * via the fake transport and flow through the classifier → router → engine,
 * proving `Network.enable` serialization and genuine event parsing in the same
 * pipeline the app uses against Chrome.
 */
class NetworkProtocolTest {

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

    private fun bind() {
        engine.bindSession(session)
        val net = parseIdFor("Network.enable")
        transport.deliverMessage("""{"id":$net,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.phase == NetworkLifecyclePhase.READY })
    }

    @Test
    fun networkEnable_serializesWithMethodAndSessionId() {
        engine.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("\"method\":\"Network.enable\"") }
        assertTrue(raw.contains("\"method\":\"Network.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun requestWillBeSent_rawMessage_reachesEngine() {
        bind()
        transport.deliverMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"raw-1","documentURL":"https://raw.example/","request":{"url":"https://raw.example/","method":"GET"}},"sessionId":"session-1"}"""
        )
        assertTrue(waitFor { engine.state.requests.isNotEmpty() })
        val model = engine.state.requests[0]
        assertEquals("raw-1", model.requestId)
        assertEquals(NetworkRequestStage.PENDING, model.stage)
        assertEquals("https://raw.example/", model.url)
    }

    @Test
    fun fullLifecycle_rawMessages_advanceStages() {
        bind()
        transport.deliverMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"raw-lc","documentURL":"https://lc.example/","request":{"url":"https://lc.example/","method":"HEAD"}},"sessionId":"session-1"}"""
        )
        assertTrue(waitFor { engine.state.requests.isNotEmpty() })
        transport.deliverMessage(
            """{"method":"Network.responseReceived","params":{"requestId":"raw-lc","type":"document","response":{"url":"https://lc.example/","status":301,"statusText":"Moved","mimeType":"text/html"}},"sessionId":"session-1"}"""
        )
        assertTrue(waitFor { engine.state.requests.any { it.requestId == "raw-lc" && it.hasResponse } })
        transport.deliverMessage(
            """{"method":"Network.loadingFinished","params":{"requestId":"raw-lc"},"sessionId":"session-1"}"""
        )
        assertTrue(waitFor { engine.state.requests.any { it.requestId == "raw-lc" && it.isFinished } })
        val model = engine.state.requests.first { it.requestId == "raw-lc" }
        assertEquals(301, model.responseStatus)
        assertEquals("HEAD", model.method)
    }

    @Test
    fun loadingFailed_rawMessage_createsFailedRequest() {
        bind()
        transport.deliverMessage(
            """{"method":"Network.loadingFailed","params":{"requestId":"raw-fail","error":"net::ERR_ABORTED","canceled":true},"sessionId":"session-1"}"""
        )
        assertTrue(waitFor { engine.state.requests.isNotEmpty() })
        val model = engine.state.requests[0]
        assertEquals(NetworkRequestStage.FAILED, model.stage)
        assertEquals("net::ERR_ABORTED", model.loadingError)
        assertTrue(model.canceled)
    }

    @Test
    fun eventForOtherSession_rawMessage_isNotApplied() {
        bind()
        transport.deliverMessage(
            """{"method":"Network.requestWillBeSent","params":{"requestId":"other","documentURL":"https://other.example/","request":{"url":"https://other.example/"}},"sessionId":"other-session"}"""
        )
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
    }

    @Test
    fun malformedJson_doesNotBreakPipeline() {
        bind()
        transport.deliverMessage("{ this is not json !")
        transport.deliverMessage(
            """{"method":"Network.requestWillBeSent","params":{"documentURL":"https://no-id.example/"},"sessionId":"session-1"}"""
        )
        Thread.sleep(200)
        assertEquals(0, engine.state.requests.size)
        assertEquals(NetworkLifecyclePhase.READY, engine.state.phase)
    }
}