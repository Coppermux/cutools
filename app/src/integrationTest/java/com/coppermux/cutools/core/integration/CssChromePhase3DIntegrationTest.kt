package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.css.CssCapabilityStatus
import com.coppermux.cutools.core.css.CssInspectionPhase
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomState
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
 * Phase 3D — Layout, Grid, Flexbox and Container Queries real-Chrome validation.
 *
 * Proves against REAL Chrome:
 *  - Node-level layout geometry (`DOM.getContentQuads`, `DOM.getRelayoutBoundary`)
 *  - Container-query relationships (`DOM.getContainerForNode`,
 *    `DOM.getQueryingDescendantsForContainer`) and per-rule `containerQueries`
 *  - Grid / Flexbox: this CDP version exposes ONLY Overlay highlight APIs (a
 *    Phase 3E feature); structured inspection is represented honestly as
 *    unsupported — never fabricated.
 *  - Selection changes reset layout/container state.
 *
 * Uses genuine CDP commands through the Phase 1 foundation (no second
 * WebSocket). Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class CssChromePhase3DIntegrationTest {

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
        cm.attachToTarget(
            onAttached = { s -> sessionRef.set(s); attached.countDown() },
            onError = { e -> attached.countDown() },
        )
        await(attached, "target attach")
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

    private fun waitForCapability(css: CssInspector, status: CssCapabilityStatus) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.capability != status && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(status, css.capability)
    }

    /** Expands the tree until an element with the given tag is loaded. */
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

    private fun waitForLayoutPhase(css: CssInspector, phase: CssInspectionPhase) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.inspectionState.layoutPhase != phase && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(phase, css.inspectionState.layoutPhase)
    }

    private fun waitForContainerPhase(css: CssInspector, phase: CssInspectionPhase) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.inspectionState.containerPhase != phase && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(phase, css.inspectionState.containerPhase)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for: $what", condition())
    }

    private fun elementById(inspector: DomInspector, id: String): DomNode? =
        inspector.tree.allNodes.firstOrNull { it.attribute("id") == id }

    /** Expands the tree until an element with the given id is reachable. */
    private fun expandUntilId(inspector: DomInspector, id: String) {
        val deadline = System.currentTimeMillis() + 10000
        while (inspector.tree.allNodes.none { it.attribute("id") == id } && System.currentTimeMillis() < deadline) {
            val toExpand = inspector.tree.allNodes.firstOrNull { it.isElement && inspector.tree.hasUnloadedChildren(it.nodeId) }
                ?: break
            inspector.expandNode(toExpand.nodeId)
            Thread.sleep(50)
        }
        assertNotNull("element #$id must load", elementById(inspector, id))
    }

    /** Injects a page with a grid container, a flex container, and a container-query. */
    private fun injectTestPage(cm: ConnectionManager, session: SessionInfo) {
        val expression = """
            document.head.querySelectorAll('style').forEach(function(st){ st.remove(); });
            var s = document.createElement('style'); s.id = 'p3d-style';
            s.textContent = `
              .grid { display: grid; grid-template-columns: 100px 100px; }
              .flex { display: flex; }
              .container { container-type: inline-size; }
              @container (min-width: 100px) { .child { color: red; } }
            `;
            document.head.appendChild(s);
            document.body.innerHTML =
              "<div id='grid' class='grid'><span>g1</span><span>g2</span></div>" +
              "<div id='flex' class='flex'><span>f1</span></div>" +
              "<div id='container' class='container'><div id='child' class='child'>c</div></div>";
        """
        val latch = CountDownLatch(1)
        cm.dispatcher.sendCommand(
            "Runtime.evaluate",
            mapOf("expression" to expression),
            sessionId = session.sessionId,
            callback = { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun phase3d_layoutAndContainer_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)
        // Expand the tree so the injected elements are reachable.
        expandUntilId(inspector, "grid")
        expandUntilId(inspector, "child")
        expandUntilId(inspector, "container")

        val grid = elementById(inspector, "grid")
        val child = elementById(inspector, "child")
        val container = elementById(inspector, "container")
        assertNotNull("grid must load", grid)
        assertNotNull("child must load", child)
        assertNotNull("container must load", container)

        // 1. Layout geometry from Chrome for the grid container.
        inspector.selectNode(grid!!.nodeId)
        waitForLayoutPhase(css, CssInspectionPhase.LOADED)
        val layout = css.inspectionState.layoutInfo!!
        assertTrue("content quads must exist", layout.contentQuads.isNotEmpty())
        assertNotNull("content quad must have real geometry", layout.contentQuads[0].left)
        assertTrue(
            "quad width must be positive",
            (layout.contentQuads[0].right!! - layout.contentQuads[0].left!!) > 0
        )
        assertEquals("layout must belong to the selected node", grid.nodeId, layout.nodeId)

        // 2. Grid / Flex: no structured data in this protocol version — honest UNSUPPORTED.
        assertEquals(CssCapabilityStatus.UNSUPPORTED, css.inspectionState.gridCapability)
        assertEquals(CssCapabilityStatus.UNSUPPORTED, css.inspectionState.flexCapability)
        assertFalse(css.inspectionState.isGridSupported)
        assertFalse(css.inspectionState.isFlexSupported)

        // 3. Container queries — the child sits inside a container.
        inspector.selectNode(child!!.nodeId)
        waitForContainerPhase(css, CssInspectionPhase.LOADED)
        val childInfo = css.inspectionState.containerInfo
        assertNotNull("child must report a container", childInfo)
        assertNotNull("child container nodeId must be present", childInfo!!.containerNodeId)

        // 4. Selecting the container reports the child as a querying descendant.
        inspector.selectNode(container!!.nodeId)
        waitForContainerPhase(css, CssInspectionPhase.LOADED)
        waitFor("container reports the child as a querying descendant") {
            css.inspectionState.containerInfo?.queryingDescendants?.contains(child.nodeId) == true
        }
        assertNotNull(css.inspectionState.containerInfo)
        assertTrue(
            "container must report the child as a querying descendant",
            css.inspectionState.containerInfo!!.queryingDescendants.contains(child.nodeId)
        )

        // 5. The @container rule is exposed on the child's matched rules.
        inspector.selectNode(child.nodeId)
        waitFor("container rule exposed on child") {
            css.inspectionState.styles?.matchedRules?.any {
                it.selectorText == ".child" && it.containerQueries.isNotEmpty()
            } == true
        }
        val condition = css.inspectionState.styles?.matchedRules
            ?.firstOrNull { it.selectorText == ".child" }?.containerQueries?.firstOrNull()?.conditionText
        assertNotNull("container query condition must be exposed", condition)
        assertTrue(
            "container query condition is real",
            condition!!.uppercase().contains("MIN-WIDTH")
        )

        inspector.detach()
        cm.disconnect()
    }

    @Test
    fun phase3d_selectionChangeNotStale_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)
        expandUntilId(inspector, "grid")
        expandUntilId(inspector, "flex")

        val grid = elementById(inspector, "grid")
        val flex = elementById(inspector, "flex")
        assertNotNull("grid must load", grid)
        assertNotNull("flex must load", flex)

        inspector.selectNode(grid!!.nodeId)
        waitForLayoutPhase(css, CssInspectionPhase.LOADED)
        waitFor("layout associated with grid") {
            css.inspectionState.layoutInfo?.nodeId == grid.nodeId
        }
        assertEquals(grid.nodeId, css.inspectionState.layoutInfo?.nodeId)

        // Selecting a different node must associate layout data with the NEW node.
        inspector.selectNode(flex!!.nodeId)
        waitFor("layout associated with flex node") {
            css.inspectionState.layoutInfo?.nodeId == flex.nodeId
        }
        waitForLayoutPhase(css, CssInspectionPhase.LOADED)
        assertEquals(flex.nodeId, css.inspectionState.layoutInfo?.nodeId)
        assertEquals(flex.nodeId, css.inspectionState.nodeId)

        inspector.detach()
        cm.disconnect()
    }
}