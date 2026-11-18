package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.console.ConsoleEntryKind
import com.coppermux.cutools.core.console.ConsoleLifecyclePhase
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.runtime.EvaluationOutcome
import com.coppermux.cutools.core.runtime.EvaluationRequest
import com.coppermux.cutools.core.runtime.EvaluationResultKind
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.runtime.ExecutionEngineListener
import com.coppermux.cutools.core.runtime.ExecutionPhase
import com.coppermux.cutools.core.runtime.ExecutionState
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 4B — JavaScript Execution real-Chrome validation.
 *
 * Proves genuine page-context execution against REAL Chrome through the Phase 1
 * foundation (no second WebSocket): number/string/boolean/null/undefined/object/
 * array results, a genuine JavaScript exception, a syntax error, protocol-error
 * distinction, RemoteObject information, session/target association, and the
 * integration of results into Console state as evaluation entries.
 *
 * Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class EvaluationChromeIntegrationTest {

    private lateinit var browserWsUrl: String

    @Before
    fun discoverChrome() {
        val env = ChromeEnvironment()
        if (!env.isReachable) {
            env.startHeadlessChrome()
        }
        browserWsUrl = env.discoverWebSocketUrl()
        assumeTrue("Real Chrome not reachable at http://127.0.0.1:9224 — skipping", browserWsUrl.isNotBlank())
    }

    private fun newManager(): ConnectionManager =
        ConnectionManager.create(WebSocketCdpTransport(), DispatcherConfig(defaultTimeoutMs = 5000))

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue("Timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))
    }

    private fun connectAttachPage(cm: ConnectionManager): Pair<SessionInfo, TargetInfo> {
        val connected = CountDownLatch(1)
        cm.connect(url = browserWsUrl, onConnected = { connected.countDown() }, onError = { connected.countDown() })
        await(connected, "connection")

        val disc = CountDownLatch(1)
        val targets = AtomicReference<List<TargetInfo>>()
        cm.discoverTargets(callback = { t -> targets.set(t); disc.countDown() }, onError = { fail("discover: ${it.message}") })
        await(disc, "target discovery")
        val page = targets.get().first { it.isPage }
        cm.selectTarget(page.targetId)

        val attached = CountDownLatch(1)
        val sessionRef = AtomicReference<SessionInfo?>()
        val attachError = AtomicReference<Throwable?>()
        cm.attachToTarget(
            onAttached = { s -> sessionRef.set(s); attached.countDown() },
            onError = { e -> attachError.set(e); attached.countDown() },
        )
        await(attached, "target attach")
        assertNull("Attach error: ${attachError.get()}", attachError.get())
        return sessionRef.get()!! to page
    }

    private fun waitReady(engine: ExecutionEngine) {
        val deadline = System.currentTimeMillis() + 10000
        while (engine.state.phase != ExecutionPhase.READY && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(ExecutionPhase.READY, engine.state.phase)
    }

    private fun waitPhase(console: ConsoleInspector, phase: ConsoleLifecyclePhase) {
        val deadline = System.currentTimeMillis() + 15000
        while (console.state.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(phase, console.state.phase)
    }

    private fun waitFor(timeoutMs: Long = 15000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }

    private fun evaluateSync(engine: ExecutionEngine, expression: String): EvaluationOutcome {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<EvaluationOutcome?>()
        engine.evaluate(EvaluationRequest(expression)) { o -> ref.set(o); latch.countDown() }
        await(latch, "evaluate $expression")
        return ref.get()!!
    }

    @Test
    fun phase4b_evaluation_againstRealChrome() {
        val cm = newManager()
        val (session, target) = connectAttachPage(cm)
        val engine = ExecutionEngine(cm.dispatcher)
        engine.bindSession(session)
        waitReady(engine)

        // The Console tool enables the Runtime domain; Chrome only populates
        // exceptionDetails.stackTrace in evaluate responses when the Runtime
        // domain is enabled, so bind the console first (mirrors real app usage).
        val dom = DomInspector(cm.dispatcher, cm.eventRouter)
        dom.bindSession(session)
        val console = ConsoleInspector(cm.dispatcher, cm.eventRouter, dom, engine)
        console.bindSession(session)
        waitPhase(console, ConsoleLifecyclePhase.READY)

        // 1 + 1 → number 2
        val num = evaluateSync(engine, "1 + 1")
        assertTrue("1+1 must succeed", num.isSuccess)
        assertEquals("2", num.displayText)
        val numValue = (num.result as EvaluationResultKind.Success).value
        assertEquals("number", numValue.type)
        assertEquals(2.0, (numValue.value as Number).toDouble(), 0.0)
        assertEquals(session.sessionId, num.sessionId)
        assertEquals(target.targetId, num.targetId)

        // string expression
        val str = evaluateSync(engine, "\"hello \" + \"world\"")
        assertTrue(str.isSuccess)
        assertEquals("hello world", (str.result as EvaluationResultKind.Success).value.value)

        // boolean
        val bool = evaluateSync(engine, "true")
        assertTrue((bool.result as EvaluationResultKind.Success).value.value == true)

        // null
        val nul = evaluateSync(engine, "null")
        assertEquals("null", (nul.result as EvaluationResultKind.Success).value.subtype)

        // undefined
        val undef = evaluateSync(engine, "undefined")
        assertEquals("undefined", (undef.result as EvaluationResultKind.Success).value.type)

        // object result — browser-owned RemoteObject with objectId
        val obj = evaluateSync(engine, "({a:1,b:2})")
        assertTrue(obj.isSuccess)
        val objRo = (obj.result as EvaluationResultKind.Success).value
        assertEquals("Object", objRo.className)
        assertNotNull("object must carry a browser objectId", objRo.objectId)
        assertEquals("Object", objRo.description)

        // array result
        val arr = evaluateSync(engine, "[1,2,3]")
        val arrRo = (arr.result as EvaluationResultKind.Success).value
        assertEquals("array", arrRo.subtype)
        assertEquals("Array", arrRo.className)
        assertNotNull(arrRo.objectId)
        assertTrue(arr.displayText.contains("Array"))

        // genuine JavaScript exception
        val exc = evaluateSync(engine, "(()=>{ throw new Error('phase4b-boom'); })()")
        assertTrue("thrown error must be an evaluation exception", exc.isException)
        assertTrue(exc.displayText.contains("phase4b-boom"))
        assertNotNull("exception stack must be exposed", (exc.result as EvaluationResultKind.Exception).details.stackTrace)

        // syntax / evaluation error
        val syn = evaluateSync(engine, "1 +")
        assertTrue("syntax error must be an evaluation exception", syn.isException)
        assertTrue(syn.displayText.contains("SyntaxError"))

        // ---- Console integration: results appear as EVALUATION_RESULT entries ----
        engine.addListener(object : ExecutionEngineListener {
            override fun onExecutionStateChanged(state: ExecutionState) {}
            override fun onEvaluationCompleted(outcome: EvaluationOutcome) {
                console.recordEvaluation(outcome)
            }
        })
        val result = evaluateSync(engine, "7 * 6")
        assertTrue(result.isSuccess)
        assertEquals("42", result.displayText)
        waitFor(message = "evaluation result must appear in console state") {
            console.state.entries.any { it.kind == ConsoleEntryKind.EVALUATION_RESULT }
        }
        val entry = console.state.entries.first { it.kind == ConsoleEntryKind.EVALUATION_RESULT }
        assertEquals("42", entry.displayText)
        assertEquals(session.sessionId, entry.sessionId)
        assertNull("evaluation entries have no fabricated browser timestamp", entry.timestamp)

        // ---- Protocol error is distinct from an evaluation exception ----
        // A bogus (nonexistent) session yields a genuine CDP protocol error.
        val bogusEngine = ExecutionEngine(cm.dispatcher)
        bogusEngine.bindSession(SessionInfo("bogus-session-xyz", "bogus-target", null))
        waitReady(bogusEngine)
        val protoErr = evaluateSync(bogusEngine, "1")
        assertTrue("invalid session must be a protocol error", protoErr.isProtocolError)
        assertNotNull("protocol error must carry the CDP error", protoErr.error)
        assertTrue(protoErr.error!!.message!!.contains("-32001"))

        // ---- Target/session association: a newly selected target is used ----
        val created = CountDownLatch(1)
        val secondTargetId = AtomicReference<String?>()
        cm.dispatcher.sendCommand("Target.createTarget", mapOf("url" to "about:blank")) { r ->
            if (r is CommandResult.Success) {
                secondTargetId.set((r.response.result as? Map<*, *>)?.get("targetId") as? String)
            }
            created.countDown()
        }
        await(created, "create second target")
        val secondId = secondTargetId.get()
        assertNotNull("second target must be created", secondId)

        val attachLatch = CountDownLatch(1)
        val secondSession = AtomicReference<SessionInfo?>()
        cm.dispatcher.sendCommand("Target.attachToTarget", mapOf("targetId" to secondId, "flatten" to true)) { r ->
            if (r is CommandResult.Success) {
                val sid = (r.response.result as? Map<*, *>)?.get("sessionId") as? String
                if (sid != null) secondSession.set(SessionInfo(sid, secondId!!, null))
            }
            attachLatch.countDown()
        }
        await(attachLatch, "attach second target")
        assertNotNull("second session must be created", secondSession.get())
        val engineB = ExecutionEngine(cm.dispatcher)
        engineB.bindSession(secondSession.get()!!)
        waitReady(engineB)
        val bOutcome = evaluateSync(engineB, "40 + 2")
        assertTrue(bOutcome.isSuccess)
        assertEquals("42", bOutcome.displayText)
        assertEquals("evaluation must be associated with the second target's session", secondSession.get()!!.sessionId, bOutcome.sessionId)
        assertEquals(secondId, bOutcome.targetId)

        // Cleanup
        val closed = CountDownLatch(1)
        cm.dispatcher.sendCommand("Target.closeTarget", mapOf("targetId" to secondId)) { closed.countDown() }
        await(closed, "close second target")
        engine.detach()
        engineB.detach()
        bogusEngine.detach()
        console.detach()
        cm.disconnect()
    }

    @Test
    fun phase2_requiredAcceptanceTests_againstRealChrome() {
        val cm = newManager()
        val (session, target) = connectAttachPage(cm)
        val engine = ExecutionEngine(cm.dispatcher, dispatcherProvider = { cm.dispatcher })
        engine.bindSession(session)
        waitReady(engine)

        val dom = DomInspector(cm.dispatcher, cm.eventRouter)
        dom.bindSession(session)
        val console = ConsoleInspector(
            cm.dispatcher,
            cm.eventRouter,
            dom,
            engine,
            dispatcherProvider = { cm.dispatcher },
            eventRouterProvider = { cm.eventRouter },
        )
        console.bindSession(session)
        waitPhase(console, ConsoleLifecyclePhase.READY)

        // 1. "1 + 1" -> Expected result: "2"
        val evalMath = evaluateSync(engine, "1 + 1")
        assertTrue("1 + 1 evaluation must succeed", evalMath.isSuccess)
        assertEquals("2", evalMath.displayText)
        val mathValue = (evalMath.result as EvaluationResultKind.Success).value
        assertEquals("number", mathValue.type)
        assertEquals(2.0, (mathValue.value as Number).toDouble(), 0.0)

        // 2. "document.title" -> Expected result: actual title of the loaded page
        val setTitle = evaluateSync(engine, "document.title = 'CuTools CDP Phase 2 Verification'")
        assertTrue("Setting document.title must succeed", setTitle.isSuccess)

        val evalTitle = evaluateSync(engine, "document.title")
        assertTrue("document.title evaluation must succeed", evalTitle.isSuccess)
        assertEquals("CuTools CDP Phase 2 Verification", evalTitle.displayText)
        val titleValue = (evalTitle.result as EvaluationResultKind.Success).value
        assertEquals("string", titleValue.type)
        assertEquals("CuTools CDP Phase 2 Verification", titleValue.value)

        // 3. "location.href" -> Expected result: actual URL of the loaded page
        val evalHref = evaluateSync(engine, "location.href")
        assertTrue("location.href evaluation must succeed", evalHref.isSuccess)
        val hrefValue = (evalHref.result as EvaluationResultKind.Success).value
        assertEquals("string", hrefValue.type)
        assertNotNull("location.href value must not be null", hrefValue.value)
        val hrefStr = hrefValue.value as String
        assertTrue(
            "location.href must be a valid URL, got: $hrefStr",
            hrefStr.startsWith("about:") || hrefStr.startsWith("http://") || hrefStr.startsWith("https://") || hrefStr.startsWith("data:")
        )
        assertEquals(hrefStr, evalHref.displayText)

        // End-to-end Console validation: input submission through ConsoleInspector
        console.setInput("1 + 1")
        console.submitInput()
        waitFor(message = "Console must record 1 + 1 evaluation result") {
            console.state.entries.any { it.kind == ConsoleEntryKind.EVALUATION_RESULT && it.displayText == "2" }
        }

        console.setInput("document.title")
        console.submitInput()
        waitFor(message = "Console must record document.title evaluation result") {
            console.state.entries.any { it.kind == ConsoleEntryKind.EVALUATION_RESULT && it.displayText == "CuTools CDP Phase 2 Verification" }
        }

        console.setInput("location.href")
        console.submitInput()
        waitFor(message = "Console must record location.href evaluation result") {
            console.state.entries.any { it.kind == ConsoleEntryKind.EVALUATION_RESULT && it.displayText == hrefStr }
        }

        engine.detach()
        console.detach()
        cm.disconnect()
    }
}
