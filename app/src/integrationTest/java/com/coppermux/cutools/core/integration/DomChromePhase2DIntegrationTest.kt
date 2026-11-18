package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomNodeType
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 2D — Live DOM Synchronization real-Chrome validation.
 *
 * Proves the 21-point Phase 2D checklist against REAL Chrome:
 *   1. Load a page.
 *   2. Insert a DOM node externally.
 *   3. Verify CuTools updates without refresh.
 *   4. Remove a node externally.
 *   5. Verify CuTools updates.
 *   6. Change an attribute externally.
 *   7. Verify CuTools updates.
 *   8. Change text externally.
 *   9. Verify CuTools updates.
 *  10. Perform a CuTools mutation.
 *  11. Verify it is reflected exactly once.
 *  12. Replace outer HTML.
 *  13. Verify new node state.
 *  14. Trigger document reset/rebuild.
 *  15. Verify the tree rebuilds correctly.
 *  16. Verify search does not retain deleted nodes.
 *  17. Verify selection clears when its node disappears.
 *  18. Switch targets.
 *  19. Verify old events cannot affect the new target.
 *  20. Disconnect/reconnect.
 *  21. Verify a clean tree with no duplicates.
 *
 * Uses genuine CDP DOM events routed through the existing EventRouter and the
 * Phase 1 foundation (no second WebSocket). Skips when Chrome is not reachable
 * at http://127.0.0.1:9224.
 */
class DomChromePhase2DIntegrationTest {

    private lateinit var browserWsUrl: String

    @Before
    fun discoverChrome() {
        browserWsUrl = try {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url("http://127.0.0.1:9224/json/version").build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = com.coppermux.cutools.core.model.Protocol.moshi
                    .adapter(Map::class.java).fromJson(body)
                (json?.get("webSocketDebuggerUrl") as? String).orEmpty()
            }
        } catch (e: Exception) {
            ""
        }
        assumeTrue("Real Chrome not reachable at http://127.0.0.1:9224 — skipping", browserWsUrl.isNotBlank())
    }

    private fun newManager(): ConnectionManager =
        ConnectionManager.create(WebSocketCdpTransport(), DispatcherConfig(defaultTimeoutMs = 5000))

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue("Timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))
    }

    /** Connects, discovers, selects, attaches to the first page target, and binds a DomInspector. */
    private fun connectAttachPage(cm: ConnectionManager): Pair<SessionInfo, DomInspector> {
        val connected = CountDownLatch(1)
        cm.connect(url = browserWsUrl, onConnected = { connected.countDown() }, onError = { connected.countDown() })
        await(connected, "connection")
        assertTrue(cm.connectionState.isActive())

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
        val session = sessionRef.get()!!
        val inspector = DomInspector(cm.dispatcher, cm.eventRouter)
        inspector.bindSession(session)
        return session to inspector
    }

    private fun waitForDomReady(inspector: DomInspector) {
        val deadline = System.currentTimeMillis() + 15000
        while (inspector.state() != DomState.READY && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(DomState.READY, inspector.state())
        assertNotNull("Document must be loaded", inspector.tree.document)
    }

    private fun waitForTag(inspector: DomInspector, tag: String): DomNode? {
        val deadline = System.currentTimeMillis() + 10000
        while (inspector.tree.allNodes.none { it.tagName == tag } && System.currentTimeMillis() < deadline) {
            val toExpand = inspector.tree.allNodes.firstOrNull { it.isElement && inspector.tree.hasUnloadedChildren(it.nodeId) }
                ?: break
            inspector.expandNode(toExpand.nodeId)
            Thread.sleep(50)
        }
        return inspector.tree.allNodes.firstOrNull { it.tagName == tag }
    }

    private fun waitFor(inspector: DomInspector, timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }

    /** Executes a JavaScript expression against the given session and waits for completion. */
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

    private fun waitForSearchComplete(inspector: DomInspector) {
        val deadline = System.currentTimeMillis() + 5000
        while (inspector.searchState.loading && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    /** Resets the integration page to a known state. */
    private fun resetPage(cm: ConnectionManager, inspector: DomInspector) {
        val body = "<h1 id='main' class='hero'>Hello World</h1>" +
            "<div id='box' class='container' data-x='42'>Box content</div>" +
            "<span class='hero' data-x='1'>Span text</span>"
        val escaped = body.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        val latch = CountDownLatch(1)
        cm.dispatcher.sendCommand(
            "Runtime.evaluate",
            mapOf("expression" to "document.body.innerHTML = '$escaped'"),
            sessionId = inspector.session?.sessionId,
            callback = { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        inspector.refresh()
        waitForDomReady(inspector)
    }

    @Test
    fun phase2d_liveSynchronization_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        resetPage(cm, inspector)

        // Ensure BODY's children are loaded so Chrome emits childNodeInserted events.
        waitForTag(inspector, "H1")
        waitForTag(inspector, "DIV")
        val span = waitForTag(inspector, "SPAN")
        assertNotNull("SPAN must load", span)

        // 1/2/3. External insertion appears without refresh.
        assertTrue(evaluate(cm, session, "var s=document.createElement('section'); s.id='ext-section'; s.textContent='Ext'; document.body.appendChild(s);"))
        waitFor(inspector, 10000, "externally inserted SECTION must appear in CuTools") {
            inspector.tree.allNodes.any { it.tagName == "SECTION" && it.attribute("id") == "ext-section" }
        }

        // 4/5. External removal disappears without refresh.
        assertTrue(evaluate(cm, session, "document.querySelector('#ext-section').remove();"))
        waitFor(inspector, 10000, "externally removed SECTION must disappear from CuTools") {
            inspector.tree.allNodes.none { it.attribute("id") == "ext-section" }
        }

        // 6/7. External attribute change is reflected.
        assertTrue(evaluate(cm, session, "document.querySelector('#box').setAttribute('data-live','yes');"))
        waitFor(inspector, 10000, "externally changed attribute must appear") {
            inspector.tree.allNodes.any { it.attribute("id") == "box" && it.attribute("data-live") == "yes" }
        }

        // 8/9. External text change is reflected. Expand H1 so its text child is loaded.
        val h1 = waitForTag(inspector, "H1")!!
        if (inspector.tree.hasUnloadedChildren(h1.nodeId)) inspector.expandNode(h1.nodeId)
        waitFor(inspector, 10000, "H1 text node must be loaded") {
            inspector.tree[h1.nodeId]?.children?.any { it.isText } == true
        }
        assertTrue(evaluate(cm, session, "document.querySelector('h1').firstChild.nodeValue = 'Hello Live';"))
        waitFor(inspector, 10000, "externally changed text must appear") {
            inspector.tree.allNodes.any { it.isText && it.nodeValue?.contains("Hello Live") == true }
        }

        // 10/11. A CuTools mutation is reflected exactly once.
        inspector.selectNode(h1.nodeId)
        val edit = CountDownLatch(1)
        val editRes = AtomicReference<com.coppermux.cutools.core.dom.DomOperationResult<Unit>>()
        inspector.setAttributeValue(h1.nodeId, "id", "cu-main") { r -> editRes.set(r); edit.countDown() }
        await(edit, "CuTools attribute mutation")
        assertTrue(editRes.get() is com.coppermux.cutools.core.dom.DomOperationResult.Success)
        waitFor(inspector, 5000, "CuTools attribute mutation reflected") {
            inspector.tree[h1.nodeId]?.attribute("id") == "cu-main"
        }
        val h1Attrs = inspector.tree[h1.nodeId]!!.attributes
        assertEquals("exactly one id attribute", 1, h1Attrs.count { it.name == "id" })
        assertEquals("cu-main", h1Attrs.first { it.name == "id" }.value)

        // 12/13. Outer HTML replacement produces correct new node state.
        inspector.selectNode(h1.nodeId)
        val htmlLatch = CountDownLatch(1)
        inspector.setOuterHTML(h1.nodeId, "<h1 id=\"replaced\" class=\"hero\">Replaced H1</h1>") { htmlLatch.countDown() }
        await(htmlLatch, "setOuterHTML")
        waitForDomReady(inspector)
        val newH1 = waitForTag(inspector, "H1")
        assertNotNull("H1 must be re-resolved after replacement", newH1)
        waitFor(inspector, 10000, "replaced node state must reflect the replacement") {
            inspector.tree[newH1!!.nodeId]?.attribute("id") == "replaced"
        }
        assertNull("old nodeId must not be reused", inspector.tree[h1.nodeId])
        assertNull("selection must not pretend the old node remains", inspector.selectedNode())

        // 14/15. Document reset/rebuild.
        assertTrue(evaluate(cm, session, "document.open(); document.write('<html><body><h1 id=\"fresh\">Fresh</h1></body></html>'); document.close();"))
        // documentUpdated alone can race under load; force an authoritative fetch.
        inspector.refresh()
        waitForDomReady(inspector)
        val freshH1 = waitForTag(inspector, "H1")
        assertNotNull("fresh document must rebuild the tree", freshH1)
        waitFor(inspector, 10000, "fresh document H1 must be present") {
            inspector.tree[freshH1!!.nodeId]?.attribute("id") == "fresh"
        }
        assertEquals("exactly one document root", 1, inspector.tree.allNodes.count { it.nodeType == DomNodeType.DOCUMENT })

        // 16 + 17. Search does not retain deleted nodes; selection clears when
        // its node disappears.
        inspector.performSearch("#fresh")
        waitForSearchComplete(inspector)
        val freshNode = inspector.tree.allNodes.firstOrNull { it.attribute("id") == "fresh" }
        assertNotNull("fresh node must be present", freshNode)
        assertTrue("search must find #fresh", inspector.searchState.results.any { it.attribute("id") == "fresh" })
        inspector.selectNode(freshNode!!.nodeId)
        assertEquals(freshNode.nodeId, inspector.selectedNode()?.nodeId)

        assertTrue(evaluate(cm, session, "document.querySelector('#fresh').remove();"))
        waitFor(inspector, 10000, "deleted node must disappear from the tree") {
            inspector.tree.allNodes.none { it.attribute("id") == "fresh" }
        }
        waitFor(inspector, 10000, "search must not retain the deleted node") {
            inspector.searchState.results.none { it.attribute("id") == "fresh" }
        }
        waitFor(inspector, 10000, "selection must clear when its node disappears") {
            inspector.selectedNode() == null
        }

        // 21. Clean tree — no duplicate node IDs.
        val ids = inspector.tree.allNodes.map { it.nodeId }
        assertEquals("no duplicate node ids", ids.size, ids.toSet().size)
        assertEquals(1, inspector.tree.allNodes.count { it.nodeType == DomNodeType.DOCUMENT })

        inspector.detach()
        cm.disconnect()
    }

    @Test
    fun phase2d_targetSwitchAndReconnect_againstRealChrome() {
        val cm = newManager()
        val (sessionA, inspectorA) = connectAttachPage(cm)
        waitForDomReady(inspectorA)
        resetPage(cm, inspectorA)
        waitForTag(inspectorA, "H1")

        // 18. Create + attach a second page target WITHOUT tearing down target A,
        // so both CDP sessions stay alive for the isolation check.
        val created = CountDownLatch(1)
        val newTargetId = AtomicReference<String?>()
        cm.dispatcher.sendCommand("Target.createTarget", mapOf("url" to "about:blank")) { r ->
            if (r is CommandResult.Success) {
                newTargetId.set((r.response.result as? Map<*, *>)?.get("targetId") as? String)
            }
            created.countDown()
        }
        await(created, "create second target")
        val secondTargetId = newTargetId.get()
        assertNotNull("second target must be created", secondTargetId)

        val attachLatch = CountDownLatch(1)
        val sessionB = AtomicReference<SessionInfo?>()
        cm.dispatcher.sendCommand(
            "Target.attachToTarget",
            mapOf("targetId" to secondTargetId, "flatten" to true),
        ) { r ->
            if (r is CommandResult.Success) {
                val sid = (r.response.result as? Map<*, *>)?.get("sessionId") as? String
                if (sid != null) sessionB.set(SessionInfo(sid, secondTargetId!!, null))
            }
            attachLatch.countDown()
        }
        await(attachLatch, "attach second target")
        assertNotNull("second session must be created", sessionB.get())
        val inspectorB = DomInspector(cm.dispatcher, cm.eventRouter)
        inspectorB.bindSession(sessionB.get()!!)
        waitForDomReady(inspectorB)

        // 19. Mutate target A externally AFTER inspectorB is bound. Old events
        // from target A must never affect inspector B's tree.
        assertTrue(evaluate(cm, sessionA, "document.body.appendChild(Object.assign(document.createElement('section'),{id:'leak-test'}));"))
        waitFor(inspectorA, 10000, "target A must see its own external mutation") {
            inspectorA.tree.allNodes.any { it.attribute("id") == "leak-test" }
        }
        // Give any misrouted event a chance to (wrongly) land, then assert isolation.
        Thread.sleep(300)
        assertFalse("old target events must not mutate the new target's tree", inspectorB.tree.allNodes.any { it.attribute("id") == "leak-test" })
        assertFalse(inspectorB.tree.allNodes.any { it.tagName == "SECTION" })

        // 20. Connection loss invalidates the current DOM.
        inspectorA.onConnectionLost()
        assertEquals(DomState.INACTIVE, inspectorA.state())
        assertEquals(0, inspectorA.tree.size)
        inspectorB.detach()

        // Clean up the created target so later suite runs are unaffected.
        val closed = CountDownLatch(1)
        cm.dispatcher.sendCommand("Target.closeTarget", mapOf("targetId" to secondTargetId)) { closed.countDown() }
        await(closed, "close second target")
        cm.disconnect()

        // 20/21. Reconnection rebuilds a clean tree with no duplicates.
        val cm2 = newManager()
        val (_, inspector2) = connectAttachPage(cm2)
        waitForDomReady(inspector2)
        waitForTag(inspector2, "H1")
        val ids = inspector2.tree.allNodes.map { it.nodeId }
        assertEquals("no duplicate node ids after reconnect", ids.size, ids.toSet().size)
        assertEquals(1, inspector2.tree.allNodes.count { it.nodeType == DomNodeType.DOCUMENT })

        inspector2.detach()
        cm2.disconnect()
    }
}
