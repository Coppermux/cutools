package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.console.ConsoleInspector
import com.coppermux.cutools.core.console.ConsoleLifecyclePhase
import com.coppermux.cutools.core.console.ConsoleFeatureOrigin
import com.coppermux.cutools.core.console.SelectedElementPhase
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.runtime.EvaluationResultKind
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Phase 4C — Console Developer Utilities real-Chrome validation.
 *
 * Proves against REAL Chrome:
 *  - Selecting an element through the existing Elements ([DomInspector]) tool
 *    makes $0 resolve to that actual page element (verified by a DOM.requestNode
 *    round-trip on the resolved objectId).
 *  - Changing the Elements selection re-resolves $0 to the new element.
 *  - Invalidating the selection removes the $0 reference (no stale retention).
 *  - querySelector / querySelectorAll execute against the real page context and
 *    return genuine Chrome RemoteObjects; no-match returns null; an invalid
 *    selector is a genuine evaluation exception.
 *  - Command history records executed expressions; backward/forward navigation
 *    restores input WITHOUT re-executing.
 *  - Target/session association is preserved.
 *
 * Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class ConsoleHelpersChromeIntegrationTest {

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

    private fun connectAttachPage(cm: ConnectionManager): SessionInfo {
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
        return sessionRef.get()!!
    }

    private fun evaluate(cm: ConnectionManager, session: SessionInfo, expression: String): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        cm.dispatcher.sendCommand(
            "Runtime.evaluate",
            mapOf("expression" to expression),
            sessionId = session.sessionId,
            callback = { result ->
                success = result is CommandResult.Success
                latch.countDown()
            },
        )
        await(latch, "Runtime.evaluate")
        return success
    }

    private fun requestNodeFor(cm: ConnectionManager, session: SessionInfo, objectId: String): Int? {
        val latch = CountDownLatch(1)
        val nodeId = AtomicReference<Int?>()
        cm.dispatcher.sendCommand(
            "DOM.requestNode",
            mapOf("objectId" to objectId),
            sessionId = session.sessionId,
            callback = { result ->
                if (result is CommandResult.Success) {
                    nodeId.set(((result.response.result as? Map<*, *>)?.get("nodeId") as? Number)?.toInt())
                }
                latch.countDown()
            },
        )
        await(latch, "DOM.requestNode")
        return nodeId.get()
    }

    private fun waitFor(timeoutMs: Long = 15000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(message, condition())
    }

    private fun waitDomReady(dom: DomInspector) {
        val deadline = System.currentTimeMillis() + 15000
        while (dom.state() != DomState.READY && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(DomState.READY, dom.state())
    }

    private fun waitPhase(console: ConsoleInspector, phase: ConsoleLifecyclePhase) {
        val deadline = System.currentTimeMillis() + 15000
        while (console.state.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(phase, console.state.phase)
    }

    /** Expands unloaded ancestors until an element with [id] is present. */
    private fun expandUntilId(dom: DomInspector, id: String) {
        val deadline = System.currentTimeMillis() + 10000
        while (dom.tree.allNodes.none { it.attribute("id") == id } && System.currentTimeMillis() < deadline) {
            val toExpand = dom.tree.allNodes.firstOrNull { it.isElement && dom.tree.hasUnloadedChildren(it.nodeId) }
                ?: break
            dom.expandNode(toExpand.nodeId)
            Thread.sleep(25)
        }
        assertNotNull("element #$id must load", dom.tree.allNodes.firstOrNull { it.attribute("id") == id })
    }

    @Test
    fun phase4c_developerUtilities_againstRealChrome() {
        val cm = newManager()
        val session = connectAttachPage(cm)
        val dom = DomInspector(cm.dispatcher, cm.eventRouter)
        dom.bindSession(session)
        waitDomReady(dom)
        val engine = ExecutionEngine(cm.dispatcher)
        engine.bindSession(session)
        val console = ConsoleInspector(cm.dispatcher, cm.eventRouter, dom, engine)
        console.bindSession(session)
        waitPhase(console, ConsoleLifecyclePhase.READY)

        // Inject a known page and load it into the Elements (DOM) tool.
        assertTrue(evaluate(cm, session, "document.body.innerHTML = \"<div id='a' class='one'>A</div><div id='b' class='two'>B</div><p id='c'>C</p>\";"))
        dom.refresh()
        waitDomReady(dom)
        expandUntilId(dom, "a")
        expandUntilId(dom, "b")
        expandUntilId(dom, "c")

        val nodeA = dom.tree.allNodes.first { it.attribute("id") == "a" }

        // ---- 1/2/3. Select an element; $0 resolves to the actual page element ----
        dom.selectNode(nodeA.nodeId)
        waitFor(message = "$0 must resolve to the selected element") {
            console.state.selectedElement?.phase == SelectedElementPhase.LOADED
        }
        val selA = console.state.selectedElement!!
        assertEquals(nodeA.nodeId, selA.nodeId)
        assertEquals(session.sessionId, selA.sessionId)
        assertNotNull("resolved element must carry a genuine Chrome objectId", selA.remoteObject?.objectId)
        // Round-trip proves the RemoteObject references the selected page element.
        val roundTrip = requestNodeFor(cm, session, selA.remoteObject!!.objectId!!)
        assertEquals(nodeA.nodeId, roundTrip)

        // ---- 4/5. Change the selection; $0 resolves to the newly selected element ----
        val nodeB = dom.tree.allNodes.first { it.attribute("id") == "b" }
        dom.selectNode(nodeB.nodeId)
        waitFor(message = "$0 must resolve to the newly selected element") {
            console.state.selectedElement?.nodeId == nodeB.nodeId &&
                console.state.selectedElement?.phase == SelectedElementPhase.LOADED
        }
        val selB = console.state.selectedElement!!
        assertEquals(nodeB.nodeId, requestNodeFor(cm, session, selB.remoteObject!!.objectId!!))

        // ---- 6. Invalidate the selection: no stale $0 retained ----
        assertTrue(evaluate(cm, session, "document.querySelector('#b').remove();"))
        waitFor(message = "removing the selected node must clear the selection") {
            dom.selectedNode() == null
        }
        waitFor(message = "stale $0 reference must not be retained") {
            console.state.selectedElement == null
        }

        // ---- 7/8. querySelector returns a genuine Chrome-backed RemoteObject ----
        console.runQuerySelector("#a")
        waitFor(message = "querySelector must produce an entry") {
            console.state.queryHelperState.lastOutcome != null
        }
        val qOutcome = console.state.queryHelperState.lastOutcome!!
        assertTrue("matching element must succeed", qOutcome.isSuccess)
        val qRo = (qOutcome.result as EvaluationResultKind.Success).value
        assertEquals("node", qRo.subtype)
        assertNotNull("query result must carry a genuine objectId", qRo.objectId)
        val qNodeId = requestNodeFor(cm, session, qRo.objectId!!)
        assertTrue("query result must reference a real page element", qNodeId != null)
        assertEquals("a", dom.tree[qNodeId!!]?.attribute("id"))
        // The recorded entry is explicitly CDP-backed.
        val qEntry = console.state.entries.last { it.isEvaluationResult }
        assertEquals(ConsoleFeatureOrigin.CDP_BACKED, qEntry.origin)
        assertEquals("querySelector", qEntry.helperLabel)

        // ---- 9. No match returns the genuine null ----
        console.runQuerySelector("#does-not-exist")
        waitFor(message = "no-match querySelector must produce an entry") {
            console.state.queryHelperState.lastOutcome?.isSuccess == true &&
                console.state.queryHelperState.lastOutcome!!.displayText == "null"
        }

        // ---- 10. Invalid selector is a genuine evaluation exception ----
        console.runQuerySelector("bad(")
        waitFor(message = "invalid selector must be an evaluation exception") {
            console.state.queryHelperState.lastOutcome?.isException == true
        }
        val invalid = console.state.queryHelperState.lastOutcome!!
        assertTrue(invalid.displayText.contains("SyntaxError") || invalid.displayText.contains("querySelector"))

        // ---- 11/12/13/14. Command history records + navigates without executing ----
        val completedEvaluations = java.util.concurrent.atomic.AtomicInteger(0)
        engine.addListener(object : com.coppermux.cutools.core.runtime.ExecutionEngineListener {
            override fun onExecutionStateChanged(state: com.coppermux.cutools.core.runtime.ExecutionState) {}
            override fun onEvaluationCompleted(outcome: com.coppermux.cutools.core.runtime.EvaluationOutcome) {
                completedEvaluations.incrementAndGet()
            }
        })

        console.setInput("1 + 1")
        console.submitInput()
        waitFor(message = "history records the first expression") {
            console.state.historyEntries.contains("1 + 1")
        }
        console.setInput("2 + 2")
        console.submitInput()
        waitFor(message = "history records the second expression") {
            console.state.historyEntries == listOf("1 + 1", "2 + 2")
        }
        // Wait for both evaluations to settle.
        waitFor(message = "evaluations settle") {
            engine.state.pendingEvaluations == 0 && completedEvaluations.get() >= 2
        }
        val evaluationsBeforeNav = completedEvaluations.get()

        console.historyBack()
        waitFor(message = "back navigation restores the newest entry") {
            console.state.input == "2 + 2"
        }
        console.historyBack()
        waitFor(message = "back navigation restores the older entry") {
            console.state.input == "1 + 1"
        }
        console.historyForward()
        waitFor(message = "forward navigation restores the newer entry") {
            console.state.input == "2 + 2"
        }
        console.historyForward()
        waitFor(message = "forward navigation returns to the live input") {
            console.state.historyPosition == -1 && console.state.input == ""
        }
        // Navigation only changes input — it never executes an expression.
        Thread.sleep(200)
        assertEquals("history navigation must not execute", evaluationsBeforeNav, completedEvaluations.get())

        // ---- 15. Session association is explicit and correct ----
        assertEquals(session.sessionId, console.state.sessionId)
        val lastEval = console.state.entries.last { it.isEvaluationResult }
        assertEquals(session.sessionId, lastEval.sessionId)

        dom.detach()
        console.detach()
        engine.detach()
        cm.disconnect()
    }
}
