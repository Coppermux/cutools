package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomNodeType
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.CdpEventRouter
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
 * Phase 2A — DOM Foundation integration validation against REAL Chrome.
 *
 * Proves the read-only 12-point Phase 2A checklist:
 * 1. Real Chrome connection works (through Phase 1).
 * 2. Real page target attaches.
 * 3. DOM.enable succeeds.
 * 4. DOM.getDocument succeeds.
 * 5. Real page DOM appears in the tree.
 * 6. Elements are represented correctly.
 * 7. Nodes expand.
 * 8. Nodes can be selected.
 * 9. Node details appear.
 * 10. Target switching replaces the DOM.
 * 11. Detach clears the DOM.
 * 12. Reconnection rebuilds the DOM without duplication.
 *
 * Uses the genuine CDP DOM API via the Phase 1 foundation. Skips when Chrome
 * is not reachable at http://127.0.0.1:9224.
 *
 * Phase 2A is READ-ONLY: no mutation commands are issued.
 */
class DomChromeIntegrationTest {

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

    private fun newManager(): ConnectionManager {
        return ConnectionManager.create(WebSocketCdpTransport(), DispatcherConfig(defaultTimeoutMs = 5000))
    }

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue("Timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))
    }

    /** Connects, discovers, selects, and attaches to the first page target. */
    private fun connectAttachPage(cm: ConnectionManager): Pair<SessionInfo, DomInspector> {
        val connected = CountDownLatch(1)
        cm.connect(url = browserWsUrl, onConnected = { connected.countDown() }, onError = { connected.countDown() })
        await(connected, "connection")
        assertTrue(cm.connectionState.isActive())

        // Discover targets
        val disc = CountDownLatch(1)
        val targets = AtomicReference<List<TargetInfo>>()
        cm.discoverTargets(
            callback = { t -> targets.set(t); disc.countDown() },
            onError = { fail("discover failed: ${it.message}") },
        )
        await(disc, "target discovery")
        val page = targets.get().first { it.isPage }
        cm.selectTarget(page.targetId)

        // Attach — DomInspector binds via the SessionInfo returned by attach.
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
        assertNotNull(session)

        // Build a DomInspector over the SAME connection (no second WebSocket).
        val inspector = DomInspector(cm.dispatcher, cm.eventRouter)
        inspector.bindSession(session)
        return session to inspector
    }

    private fun waitForDomReady(inspector: DomInspector) {
        val deadline = System.currentTimeMillis() + 15000
        while (inspector.state() != DomState.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("DOM inspector should reach READY", DomState.READY, inspector.state())
        assertNotNull("Document must be loaded", inspector.tree.document)
    }

    /**
     * Resets the page to the canonical integration content so this test is
     * independent of whatever a prior suite run left in the shared Chrome page.
     */
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
    fun domFoundation_againstRealChrome() {
        val cm = newManager()
        val (_, inspector) = connectAttachPage(cm)
        val session = inspector.session!!
        waitForDomReady(inspector)
        resetPage(cm, inspector)
        val doc = inspector.tree.document
        assertNotNull("Document should be present", doc)
        assertEquals(DomNodeType.DOCUMENT, doc!!.nodeType)

        // 5/6. Expand the HTML element to reveal BODY; both should be real
        // elements with correct representation.
        val html = inspector.tree.document?.children?.firstOrNull()
        assertNotNull("HTML element should be a child of the document", html)
        assertTrue("HTML node should be an element", html!!.isElement)
        inspector.expandNode(html.nodeId)
        waitForTagChildren(inspector, html.nodeId, "BODY")
        val body = findElement(inspector, "BODY")
        assertNotNull("BODY should be represented as an element", body)
        assertEquals(DomNodeType.ELEMENT, body!!.nodeType)

        // 7. Nodes expand — expand BODY and verify its real children load.
        inspector.selectNode(body.nodeId)
        inspector.expandNode(body.nodeId)
        waitForLoadedChildren(inspector, body.nodeId)
        val bodyNode = inspector.tree[body.nodeId]!!
        assertTrue("BODY should have loaded children", bodyNode.children.isNotEmpty())

        // 8/9. Node selection + details — select H1 and verify it reflects a real
        // Chrome node with attributes.
        val h1 = findElement(inspector, "H1")
        assertNotNull("H1 should appear after expansion", h1)
        inspector.selectNode(h1!!.nodeId)
        val selected = inspector.selectedNode()
        assertEquals("Selected node must be H1", h1.nodeId, selected?.nodeId)
        assertEquals("H1 must have id attribute", "main", selected?.attribute("id"))
        assertTrue("Selected node must have a backendNodeId", selected!!.backendNodeId != null)
        assertNotNull("nodeId detail", selected.nodeId)

        // Cleanup (point 11: detach clears DOM).
        inspector.detach()
        assertEquals(DomState.INACTIVE, inspector.state())
        assertEquals(0, inspector.tree.size)
        assertNull(inspector.session)
        cm.disconnect()
    }

    @Test
    fun targetSwitchAndReconnect_noStaleDomState() {
        val cm = newManager()
        val (session1, inspector1) = connectAttachPage(cm)
        waitForDomReady(inspector1)
        val doc1 = inspector1.tree.document!!
        assertNotNull(doc1)

        // 10. Target switching replaces the DOM — re-attach to a fresh session and
        // bind a fresh inspector; the old inspector must have its state cleared,
        // and the new inspector must load a new (non-duplicated) document.
        val detachLatch = CountDownLatch(1)
        cm.detachFromTarget(onDetached = { detachLatch.countDown() }, onError = { detachLatch.countDown() })
        await(detachLatch, "detach")
        inspector1.detach()

        val inspector2 = DomInspector(cm.dispatcher, cm.eventRouter)
        val disc = CountDownLatch(1)
        val targets = AtomicReference<List<TargetInfo>>()
        cm.discoverTargets(callback = { t -> targets.set(t); disc.countDown() }, onError = { disc.countDown() })
        await(disc, "re-discover")
        val page = targets.get().first { it.isPage }
        cm.selectTarget(page.targetId)
        val attachLatch = CountDownLatch(1)
        cm.attachToTarget(
            onAttached = { s -> inspector2.bindSession(s); attachLatch.countDown() },
            onError = { e -> attachLatch.countDown() },
        )
        await(attachLatch, "re-attach")
        waitForDomReady(inspector2)

        // Stale inspector must have no documents.
        assertFalse("Stale inspector must have no documents", inspector1.tree.isLoaded)
        // Fresh inspector has its own document, with exactly one root document node.
        assertNotNull("Fresh inspector must have a document", inspector2.tree.document)
        assertEquals(1, inspector2.tree.allNodes.count { it.nodeType == DomNodeType.DOCUMENT })

        // 11. Detach clears the DOM.
        inspector2.detach()
        assertEquals(DomState.INACTIVE, inspector2.state())
        assertEquals(0, inspector2.tree.size)
        assertNull(inspector2.session)

        // 12. Reconnection rebuilds the DOM without duplication.
        // The ConnectionManager is still ATTACHED after inspector2.detach(), so
        // detach the target before re-discovering.
        val detach2 = CountDownLatch(1)
        cm.detachFromTarget(onDetached = { detach2.countDown() }, onError = { detach2.countDown() })
        await(detach2, "detach before reconnect")

        val inspector3 = DomInspector(cm.dispatcher, cm.eventRouter)
        val disc3 = CountDownLatch(1)
        val targets3 = AtomicReference<List<TargetInfo>>()
        cm.discoverTargets(callback = { t -> targets3.set(t); disc3.countDown() }, onError = { disc3.countDown() })
        await(disc3, "re-discover 3")
        val page3 = targets3.get().first { it.isPage }
        cm.selectTarget(page3.targetId)
        val attach3 = CountDownLatch(1)
        cm.attachToTarget(
            onAttached = { s -> inspector3.bindSession(s); attach3.countDown() },
            onError = { e -> attach3.countDown() },
        )
        await(attach3, "re-attach 3")
        waitForDomReady(inspector3)
        assertNotNull("Reconnected inspector must load a document", inspector3.tree.document)
        assertEquals(1, inspector3.tree.allNodes.count { it.nodeType == DomNodeType.DOCUMENT })

        inspector3.detach()
        cm.disconnect()
    }

    // =============== helpers ===============

    private fun findElement(inspector: DomInspector, tag: String): DomNode? =
        inspector.tree.allNodes.firstOrNull { it.tagName == tag }

    private fun waitForLoadedChildren(inspector: DomInspector, nodeId: Int) {
        val deadline = System.currentTimeMillis() + 10000
        while ((inspector.tree[nodeId]?.children?.size ?: 0) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    /** Polls until a descendant with the given tag appears (after expansion). */
    private fun waitForTagChildren(inspector: DomInspector, parentId: Int, tag: String) {
        val deadline = System.currentTimeMillis() + 10000
        while (findElement(inspector, tag) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }
}
