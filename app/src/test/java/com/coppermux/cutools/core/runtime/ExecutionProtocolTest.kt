package com.coppermux.cutools.core.runtime

import com.coppermux.cutools.core.model.Command
import com.coppermux.cutools.core.model.Protocol
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Protocol-level tests for the JavaScript execution subsystem: `Runtime.evaluate`
 * command serialization and end-to-end response parsing/routing through the raw
 * transport + classifier + dispatcher on an in-memory fake transport.
 */
class ExecutionProtocolTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: ExecutionEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = ExecutionEngine(dispatcher)
        engine.bindSession(session)
        val deadline = System.currentTimeMillis() + 3000
        while (engine.state.phase != ExecutionPhase.READY && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun awaitOutcome(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    // ============ command serialization ============

    @Test
    fun runtimeEvaluate_serializesMethodParamsAndSessionId() {
        engine.evaluate(EvaluationRequest("1 + 1", returnByValue = false, generatePreview = true)) { }
        val raw = transport.sentMessages.last { it.contains("Runtime.evaluate") }
        assertTrue(raw.contains("\"method\":\"Runtime.evaluate\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"expression\":\"1 + 1\""))
        assertTrue(raw.contains("\"returnByValue\":false"))
        assertTrue(raw.contains("\"generatePreview\":true"))

        val command: Command? = Protocol.commandAdapter.fromJson(raw)
        assertNotNull(command)
        assertEquals("Runtime.evaluate", command!!.method)
        assertEquals("session-1", command.sessionId)
        val params = command.params as? Map<*, *>
        assertNotNull(params)
        assertEquals("1 + 1", params!!["expression"])
    }

    // ============ end-to-end response parsing ============

    @Test
    fun successResponse_parsedAndRoutedEndToEnd() {
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1 + 1")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":2,"description":"2"}}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        val outcome = callbackRef.get()!!
        assertTrue(outcome.isSuccess)
        assertEquals("2", outcome.displayText)
        assertEquals("session-1", outcome.sessionId)
    }

    @Test
    fun exceptionResponse_parsedAndRoutedEndToEnd() {
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("boom()")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"object","subtype":"error"},"exceptionDetails":{"exceptionId":1,"text":"Uncaught","lineNumber":0,"columnNumber":14,"exception":{"type":"object","subtype":"error","description":"ReferenceError: boom is not defined"}}}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        assertTrue(callbackRef.get()!!.isException)
        assertTrue(callbackRef.get()!!.displayText.contains("boom is not defined"))
    }

    @Test
    fun protocolErrorResponse_parsedAndRoutedEndToEnd() {
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32001,"message":"Session with given id not found."}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        assertTrue(callbackRef.get()!!.isProtocolError)
    }

    @Test
    fun malformedResultResponse_isProtocolError() {
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":"not-an-object"}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        assertTrue(callbackRef.get()!!.isProtocolError)
    }

    @Test
    fun malformedExceptionDetails_isProtocolError() {
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"undefined"},"exceptionDetails":"malformed"}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        assertTrue(callbackRef.get()!!.isProtocolError)
    }
}
