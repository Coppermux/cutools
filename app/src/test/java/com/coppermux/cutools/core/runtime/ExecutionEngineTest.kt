package com.coppermux.cutools.core.runtime

import com.coppermux.cutools.core.model.CommandResult
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic unit tests for [ExecutionEngine] — evaluation command sending,
 * result classification, state transitions, session/target association, and
 * lifecycle cleanup — driven through the real [CommandDispatcher] on an
 * in-memory fake transport.
 */
class ExecutionEngineTest {

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
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun awaitReady() {
        val deadline = System.currentTimeMillis() + 3000
        while (engine.state.phase != ExecutionPhase.READY && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(ExecutionPhase.READY, engine.state.phase)
    }

    private fun awaitOutcome(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun evaluateAndCapture(request: EvaluationRequest, onResult: (EvaluationOutcome) -> Unit) {
        engine.evaluate(request, onResult)
    }

    // ============ lifecycle / binding ============

    @Test
    fun bindSession_setsReadyAndAssociation() {
        engine.bindSession(session)
        awaitReady()
        assertEquals(session.sessionId, engine.session?.sessionId)
        assertEquals(session.sessionId, engine.state.sessionId)
        assertEquals(session.targetId, engine.state.targetId)
    }

    @Test
    fun detach_clearsState() {
        engine.bindSession(session)
        awaitReady()
        engine.detach()
        val deadline = System.currentTimeMillis() + 3000
        while (engine.state.phase != ExecutionPhase.INACTIVE && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(ExecutionPhase.INACTIVE, engine.state.phase)
        assertNull(engine.session)
    }

    // ============ evaluation command sending ============

    @Test
    fun evaluate_sendsRuntimeEvaluate_onSession() {
        engine.bindSession(session)
        awaitReady()
        engine.evaluate(EvaluationRequest("1 + 1")) { }
        assertTrue(transport.sentMessages.any { it.contains("Runtime.evaluate") })
        val sent = transport.sentMessages.first { it.contains("Runtime.evaluate") }
        assertTrue(sent.contains("\"expression\":\"1 + 1\""))
        assertTrue(sent.contains("\"sessionId\":\"session-1\""))
        assertTrue(sent.contains("\"returnByValue\":false"))
    }

    // ============ success ============

    @Test
    fun success_response_producesSuccessOutcomeAndUpdatesState() {
        engine.bindSession(session)
        awaitReady()
        val outcomeRef = AtomicReference<EvaluationOutcome?>()
        var completed = false
        val listener = object : ExecutionEngineListener {
            override fun onExecutionStateChanged(state: ExecutionState) {}
            override fun onEvaluationCompleted(outcome: EvaluationOutcome) { outcomeRef.set(outcome); completed = true }
        }
        engine.addListener(listener)
        engine.evaluate(EvaluationRequest("1 + 1")) { }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":2,"description":"2"}}}"""
        )
        assertTrue(awaitOutcome { completed })
        val outcome = outcomeRef.get()!!
        assertTrue(outcome.isSuccess)
        assertEquals("2", outcome.displayText)
        assertEquals(session.sessionId, outcome.sessionId)
        assertEquals(session.targetId, outcome.targetId)
        assertEquals(outcome, engine.state.lastOutcome)
        assertEquals(0, engine.state.pendingEvaluations)
    }

    @Test
    fun success_response_invokesCallback() {
        engine.bindSession(session)
        awaitReady()
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("42")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":42,"description":"42"}}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        assertTrue(callbackRef.get()!!.isSuccess)
    }

    // ============ evaluation exception ============

    @Test
    fun exception_response_producesExceptionOutcome() {
        engine.bindSession(session)
        awaitReady()
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("(()=>{throw new Error('x')})()")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"object","subtype":"error"},"exceptionDetails":{"exceptionId":1,"text":"Uncaught","lineNumber":0,"columnNumber":14,"exception":{"type":"object","subtype":"error","description":"Error: x"}}}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        val outcome = callbackRef.get()!!
        assertTrue(outcome.isException)
        assertEquals("Uncaught Error: x", outcome.displayText)
    }

    // ============ protocol failure ============

    @Test
    fun protocolError_response_producesProtocolErrorOutcome() {
        engine.bindSession(session)
        awaitReady()
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32001,"message":"Session with given id not found."}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        val outcome = callbackRef.get()!!
        assertTrue(outcome.isProtocolError)
        assertNotNull(outcome.error)
        assertTrue(outcome.error!!.message!!.contains("-32001"))
    }

    // ============ local validation ============

    @Test
    fun evaluate_whenNotBound_producesLocalProtocolError() {
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> callbackRef.set(o) }
        assertTrue(awaitOutcome { callbackRef.get() != null })
        val outcome = callbackRef.get()!!
        assertTrue(outcome.isProtocolError)
        assertTrue(outcome.error!!.message!!.contains("Not attached"))
    }

    @Test
    fun evaluate_blankExpression_rejectedWithoutChromeCall() {
        engine.bindSession(session)
        awaitReady()
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("   ")) { o -> callbackRef.set(o) }
        assertTrue(awaitOutcome { callbackRef.get() != null })
        assertTrue(callbackRef.get()!!.isProtocolError)
        assertFalse(transport.sentMessages.any { it.contains("Runtime.evaluate") })
    }

    // ============ pending count ============

    @Test
    fun pendingCount_incrementsAndReturnsToZero() {
        engine.bindSession(session)
        awaitReady()
        val stateRef = AtomicReference<ExecutionState?>()
        engine.addListener(object : ExecutionEngineListener {
            override fun onExecutionStateChanged(state: ExecutionState) { stateRef.set(state) }
            override fun onEvaluationCompleted(outcome: EvaluationOutcome) {}
        })
        engine.evaluate(EvaluationRequest("1")) { }
        // pending should have been observed > 0
        val sawPending = awaitOutcome { stateRef.get()?.pendingEvaluations == 1 }
        assertTrue("pending count must be observed", sawPending)
        val id = parseIdFor("Runtime.evaluate")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":1,"description":"1"}}}"""
        )
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 0 })
    }

    // ============ lifecycle: stale target switching ============

    @Test
    fun staleResponse_afterDetach_isDropped() {
        engine.bindSession(session)
        awaitReady()
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> callbackRef.set(o) }
        val id = parseIdFor("Runtime.evaluate")
        engine.detach()
        // Response for the old target arrives after detach — must be dropped.
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":1,"description":"1"}}}"""
        )
        engine.drain()
        assertNull("stale response must not invoke the callback", callbackRef.get())
        assertEquals(ExecutionPhase.INACTIVE, engine.state.phase)
        assertNull(engine.state.lastOutcome)
    }

    @Test
    fun staleResponse_afterTargetSwitch_isNotAttributedToNewTarget() {
        engine.bindSession(session)
        awaitReady()
        val oldCallback = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> oldCallback.set(o) }
        val id = parseIdFor("Runtime.evaluate")

        // Switch to a new target before the response arrives.
        engine.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        val deadline = System.currentTimeMillis() + 3000
        while (engine.state.phase != ExecutionPhase.READY && System.currentTimeMillis() < deadline) Thread.sleep(10)

        // Deliver the old session's response — must be dropped, not attributed.
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":1,"description":"1"}}}"""
        )
        engine.drain()
        assertNull("old session response must not invoke old callback", oldCallback.get())
        assertNull("new target must not adopt the old outcome", engine.state.lastOutcome)
        assertEquals("session-2", engine.state.sessionId)
    }

    @Test
    fun evaluation_afterRebind_targetsNewSession() {
        engine.bindSession(session)
        awaitReady()
        engine.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        val deadline = System.currentTimeMillis() + 3000
        while (engine.state.phase != ExecutionPhase.READY && System.currentTimeMillis() < deadline) Thread.sleep(10)
        engine.evaluate(EvaluationRequest("7")) { }
        val sent = transport.sentMessages.last { it.contains("Runtime.evaluate") }
        assertTrue(sent.contains("\"sessionId\":\"session-2\""))
    }

    @Test
    fun staleResponse_afterClear_doesNotLeavePendingEvaluationsStuck() {
        engine.bindSession(session)
        awaitReady()
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1 + 1")) { o -> callbackRef.set(o) }
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 1 })
        val id = parseIdFor("Runtime.evaluate")

        // Clear resets pending evaluations to 0 and invalidates current generation
        engine.clear()
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 0 })

        // Deliver stale response for pre-clear evaluate call
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":2,"description":"2"}}}"""
        )
        engine.drain()
        assertNull("stale response must not invoke callback", callbackRef.get())
        assertEquals(0, engine.state.pendingEvaluations)
    }

    @Test
    fun staleResponseFromPreResetEvaluation_doesNotDecrementPendingForPostResetEvaluation() {
        engine.bindSession(session)
        awaitReady()

        val outcomeA = AtomicReference<EvaluationOutcome?>()
        val outcomeB = AtomicReference<EvaluationOutcome?>()

        // 1. Start evaluation A
        engine.evaluate(EvaluationRequest("1 + 1")) { o -> outcomeA.set(o) }
        val idA = parseIdFor("Runtime.evaluate")
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 1 })

        // 2. Reset the execution state
        engine.clear()
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 0 })

        // 3. Start evaluation B
        engine.evaluate(EvaluationRequest("2 + 2")) { o -> outcomeB.set(o) }
        val idB = transport.sentMessages.mapNotNull { msg ->
            if (msg.contains("\"expression\":\"2 + 2\"")) {
                Regex("\"id\":(\\d+)").find(msg)?.groupValues?.get(1)?.toLong()
            } else null
        }.last()
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 1 })

        // 4. Deliver evaluation A's delayed response
        transport.deliverMessage(
            """{"id":$idA,"sessionId":"session-1","result":{"result":{"type":"number","value":2,"description":"2"}}}"""
        )
        engine.drain()

        // 5. Verify B remains pending until B itself completes; A's response was dropped without decrementing B
        assertNull("Stale evaluation A response must not invoke callback A", outcomeA.get())
        assertNull("Evaluation B must still be pending", outcomeB.get())
        assertEquals("Evaluation B pending count must remain intact (1)", 1, engine.state.pendingEvaluations)

        // 6. Complete B and verify pending count reaches zero and B's result is handled correctly
        transport.deliverMessage(
            """{"id":$idB,"sessionId":"session-1","result":{"result":{"type":"number","value":4,"description":"4"}}}"""
        )
        assertTrue(awaitOutcome { outcomeB.get() != null })
        val bResult = outcomeB.get()!!
        assertTrue(bResult.isSuccess)
        assertEquals("4", bResult.displayText)
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 0 })
        assertEquals(0, engine.state.pendingEvaluations)
    }

    @Test
    fun evaluate_executionContextDestroyed_producesProtocolErrorAndResetsPending() {
        engine.bindSession(session)
        awaitReady()
        val callbackRef = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("location.href")) { o -> callbackRef.set(o) }
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 1 })
        val id = parseIdFor("Runtime.evaluate")

        // Chrome destroys execution context during navigation or crash
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Execution context was destroyed."}}"""
        )
        assertTrue(awaitOutcome { callbackRef.get() != null })
        val outcome = callbackRef.get()!!
        assertTrue(outcome.isProtocolError)
        assertTrue(outcome.error!!.message!!.contains("Execution context was destroyed"))
        assertEquals(0, engine.state.pendingEvaluations)
    }

    @Test
    fun staleResponse_afterTargetSwitch_doesNotLeavePendingEvaluationsStuck() {
        engine.bindSession(session)
        awaitReady()
        val oldCallback = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest("1")) { o -> oldCallback.set(o) }
        assertTrue(awaitOutcome { engine.state.pendingEvaluations == 1 })
        val id = parseIdFor("Runtime.evaluate")

        // Switch target
        engine.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        assertTrue(awaitOutcome { engine.state.sessionId == "session-2" && engine.state.phase == ExecutionPhase.READY })
        assertEquals(0, engine.state.pendingEvaluations)

        // Deliver old session response
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"result":{"type":"number","value":1,"description":"1"}}}"""
        )
        engine.drain()
        assertNull(oldCallback.get())
        assertEquals(0, engine.state.pendingEvaluations)
    }
}
