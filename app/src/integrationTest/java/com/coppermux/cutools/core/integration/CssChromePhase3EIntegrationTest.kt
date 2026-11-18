package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.css.CssCapabilityStatus
import com.coppermux.cutools.core.css.CssInspectionPhase
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.css.CssOverlayPhase
import com.coppermux.cutools.core.css.CssOverlayType
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomNode
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
 * Phase 3E — Layout Overlays real-Chrome validation.
 *
 * Proves against REAL Chrome:
 *  - Overlay domain enablement
 *  - Grid / Flex / Container-query overlay show/hide via `Overlay.setShow*Overlays`
 *  - State transitions DISABLED → REQUESTED → ACTIVE (only after Chrome confirms)
 *  - Browser-backed behavior: `Overlay.getGridHighlightObjectsForTest` returns
 *    real grid highlight data after the overlay is shown
 *  - Selection changes clear the overlay
 *
 * NOTE: overlay support is a separate capability from structured Grid/Flex
 * inspection (Phase 3D), which this protocol version does not expose.
 * Uses genuine CDP commands through the Phase 1 foundation (no second
 * WebSocket). Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class CssChromePhase3EIntegrationTest {

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

    private fun waitForOverlayPhase(css: CssInspector, phase: CssOverlayPhase) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.inspectionState.overlayState.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(phase, css.inspectionState.overlayState.phase)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for: $what", condition())
    }

    private fun elementById(inspector: DomInspector, id: String): DomNode? =
        inspector.tree.allNodes.firstOrNull { it.attribute("id") == id }

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

    /** Injects a page with a grid container, a flex container, and a container query. */
    private fun injectTestPage(cm: ConnectionManager, session: SessionInfo) {
        val expression = """
            document.head.querySelectorAll('style').forEach(function(st){ st.remove(); });
            var s = document.createElement('style'); s.id = 'e3-style';
            s.textContent = `
              .grid { display: grid; grid-template-columns: 100px 100px; height: 100px; }
              .flex { display: flex; }
              .container { container-type: inline-size; }
            `;
            document.head.appendChild(s);
            document.body.innerHTML =
              "<div id='grid' class='grid'><span>a</span><span>b</span></div>" +
              "<div id='flex' class='flex'><span>x</span></div>" +
              "<div id='container' class='container'><span>c</span></div>";
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
    fun phase3e_overlays_againstRealChrome() {
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
        expandUntilId(inspector, "container")

        val grid = elementById(inspector, "grid")!!
        val flex = elementById(inspector, "flex")!!
        val container = elementById(inspector, "container")!!

        // Grid overlay.
        inspector.selectNode(grid.nodeId)
        val gLatch = CountDownLatch(1)
        css.setOverlay(CssOverlayType.GRID, grid.nodeId, true) { gLatch.countDown() }
        await(gLatch, "show grid overlay")
        waitForOverlayPhase(css, CssOverlayPhase.ACTIVE)
        assertEquals(CssOverlayType.GRID, css.inspectionState.overlayState.type)

        // Browser-backed proof: getGridHighlightObjectsForTest returns grid data.
        val hlLatch = CountDownLatch(1)
        val gridData = AtomicReference<Boolean>(false)
        cm.dispatcher.sendCommand(
            "Overlay.getGridHighlightObjectsForTest",
            mapOf("nodeIds" to listOf(grid.nodeId)),
            sessionId = session.sessionId,
            callback = { r ->
                if (r is CommandResult.Success) {
                    val highlights = (r.response.result as? Map<*, *>)?.get("highlights") as? Map<*, *>
                    gridData.set(highlights != null && highlights.isNotEmpty())
                }
                hlLatch.countDown()
            },
        )
        await(hlLatch, "grid highlight data")
        assertTrue("Chrome must return real grid highlight data", gridData.get())

        // Flex overlay on a different selection (overlay does not transfer).
        inspector.selectNode(flex.nodeId)
        waitFor("grid overlay cleared on selection change") {
            !css.inspectionState.overlayState.isActive
        }
        val fLatch = CountDownLatch(1)
        css.setOverlay(CssOverlayType.FLEX, flex.nodeId, true) { fLatch.countDown() }
        await(fLatch, "show flex overlay")
        waitForOverlayPhase(css, CssOverlayPhase.ACTIVE)
        assertEquals(CssOverlayType.FLEX, css.inspectionState.overlayState.type)

        // Container-query overlay (applies to the selected container node).
        inspector.selectNode(container.nodeId)
        val cLatch = CountDownLatch(1)
        css.setOverlay(CssOverlayType.CONTAINER_QUERY, container.nodeId, true) { cLatch.countDown() }
        await(cLatch, "show container overlay")
        waitForOverlayPhase(css, CssOverlayPhase.ACTIVE)
        assertEquals(CssOverlayType.CONTAINER_QUERY, css.inspectionState.overlayState.type)

        // Disable the container overlay.
        val offLatch = CountDownLatch(1)
        css.setOverlay(CssOverlayType.CONTAINER_QUERY, container.nodeId, false) { offLatch.countDown() }
        await(offLatch, "hide container overlay")
        waitForOverlayPhase(css, CssOverlayPhase.DISABLED)
        assertFalse(css.inspectionState.overlayState.isActive)

        inspector.detach()
        cm.disconnect()
    }

    @Test
    fun phase3e_overlaySelectionAndDisconnect_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)
        expandUntilId(inspector, "grid")
        val grid = elementById(inspector, "grid")!!

        inspector.selectNode(grid.nodeId)
        val latch = CountDownLatch(1)
        css.setOverlay(CssOverlayType.GRID, grid.nodeId, true) { latch.countDown() }
        await(latch, "show grid overlay")
        waitForOverlayPhase(css, CssOverlayPhase.ACTIVE)

        // Disconnect must clear overlay state so no stale UI claims an active overlay.
        inspector.onConnectionLost()
        val deadline = System.currentTimeMillis() + 3000
        while (css.inspectionState.overlayState.phase != CssOverlayPhase.DISABLED && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(CssOverlayPhase.DISABLED, css.inspectionState.overlayState.phase)
        assertFalse(css.inspectionState.overlayState.isActive)

        cm.disconnect()
    }
}