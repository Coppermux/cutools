package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.css.CssCapabilityStatus
import com.coppermux.cutools.core.css.CssDeclarationStatus
import com.coppermux.cutools.core.css.CssInspectionPhase
import com.coppermux.cutools.core.css.CssInspectionState
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.css.CssStyleSheetOrigin
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
 * Phase 3B — Computed Styles, Cascade and Box Model real-Chrome validation.
 *
 * Proves against REAL Chrome:
 *  - Computed styles for a selected DOM node (`CSS.getComputedStyleForNode`)
 *  - Box-model retrieval (`DOM.getBoxModel`)
 *  - Active / overridden declaration status derived from Chrome's cascade order
 *  - Cascade origins + source locations (matched rule order)
 *  - Cascade-layer information (`CSS.getLayersForNode`, rule `layers`)
 *  - Selection changes driven by the shared DomInspector lifecycle
 *
 * Uses genuine CDP commands through the Phase 1 foundation (no second
 * WebSocket). Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class CssChromePhase3BIntegrationTest {

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

    private fun waitForCapability(css: CssInspector, status: CssCapabilityStatus) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.capability != status && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(status, css.capability)
    }

    private fun waitForPhase(
        css: CssInspector,
        phase: CssInspectionPhase,
        getter: (CssInspectionState) -> CssInspectionPhase,
        what: String,
    ) {
        val deadline = System.currentTimeMillis() + 15000
        while (getter(css.inspectionState) != phase && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(what, phase, getter(css.inspectionState))
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for: $what", condition())
    }

    /** Injects a canonical test page with layers, author rules, and inline styles. */
    private fun injectTestPage(cm: ConnectionManager, session: SessionInfo) {
        // Clear leftover probe styles from the shared page so the test is
        // deterministic across suite runs.
        val expression = """
            document.head.querySelectorAll('style').forEach(function(st){ st.remove(); });
            var s = document.createElement('style'); s.id = 'b3-style';
            s.textContent = `
              @layer base, theme;
              @layer base { h1 { color: blue; } }
              @layer theme { .wrap h1 { color: purple; } }
              h1 { color: green; margin: 10px; width: 300px; height: 50px; }
            `;
            document.head.appendChild(s);
            document.body.innerHTML = "<div id='wrap' class='wrap'><h1 id='t' style='color: red'>Hello</h1></div><div id='box'>Box</div>";
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
    fun phase3b_computedBoxCascade_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)

        val h1 = waitForTag(inspector, "H1")
        assertNotNull("H1 must load", h1)

        // Select H1 → all four fetch families dispatched from Chrome.
        inspector.selectNode(h1!!.nodeId)
        waitForPhase(css, CssInspectionPhase.LOADED, { it.phase }, "matched styles")
        waitForPhase(css, CssInspectionPhase.LOADED, { it.computedStylePhase }, "computed style")
        waitForPhase(css, CssInspectionPhase.LOADED, { it.boxModelPhase }, "box model")

        // 2. Computed styles come from Chrome, not local merging.
        val computed = css.inspectionState.computedStyle.orEmpty()
        val color = computed.firstOrNull { it.name == "color" }?.value
        assertNotNull("computed color must exist", color)
        assertEquals("computed color must be red (inline wins)", "rgb(255, 0, 0)", color)
        val width = computed.firstOrNull { it.name == "width" }?.value
        assertNotNull("computed width must exist", width)
        assertEquals("computed width must be 300px", "300px", width)

        // 3. Box model from Chrome (border-box geometry, no padding/border here).
        val box = css.inspectionState.boxModel!!
        assertEquals(300, box.width)
        assertEquals(50, box.height)
        assertNotNull(box.content)
        assertEquals("content quad has 8 values", 8, box.content!!.values.size)

        // 4. Cascade: inline wins color; other author candidates are overridden.
        val styles = css.inspectionState.styles!!
        // Chrome may report a property more than once (raw + expansion); at least
        // the winning inline declaration must be INLINE.
        assertTrue(
            "inline color declaration must be the active winner",
            styles.inlineDeclarations.any { it.name == "color" && it.status == CssDeclarationStatus.INLINE }
        )
        val authorColors = styles.matchedRules
            .filter { it.origin == CssStyleSheetOrigin.REGULAR }
            .flatMap { it.declarations }
            .filter { it.name == "color" }
        assertTrue("author color candidates must be present", authorColors.isNotEmpty())
        assertTrue(
            "author color candidates must all be overridden by the inline declaration",
            authorColors.all { it.status == CssDeclarationStatus.OVERRIDDEN }
        )
        val margin = styles.matchedRules.flatMap { it.declarations }.firstOrNull { it.name == "margin" }
        assertNotNull("margin declaration must exist", margin)
        assertEquals(CssDeclarationStatus.ACTIVE, margin!!.status)

        // 4b. Cascade origin + source location exposed per rule.
        val authorRule = styles.matchedRules.firstOrNull { it.selectorText == "h1" && it.origin == CssStyleSheetOrigin.REGULAR }
        assertNotNull("author h1 rule must exist", authorRule)
        assertNotNull("author rule must carry a source range", authorRule!!.sourceRange)

        // 5. Cascade layers exposed via getLayersForNode and per-rule layers.
        val layeredRules = styles.matchedRules.filter { it.layers.isNotEmpty() }
        assertTrue("rules from @layer must expose layer info", layeredRules.isNotEmpty())
        assertTrue(
            "layer names must be exposed",
            layeredRules.flatMap { it.layers }.any { it.text == "base" || it.text == "theme" }
        )

        inspector.detach()
        cm.disconnect()
    }

    @Test
    fun phase3b_selectionChange_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)
        val h1 = waitForTag(inspector, "H1")
        val div = waitForTag(inspector, "DIV")
        assertNotNull(h1)
        assertNotNull(div)

        // Select H1 then immediately DIV: final state must reflect DIV only.
        inspector.selectNode(h1!!.nodeId)
        inspector.selectNode(div!!.nodeId)
        waitForPhase(css, CssInspectionPhase.LOADED, { it.phase }, "latest selection styles")
        waitForPhase(css, CssInspectionPhase.LOADED, { it.computedStylePhase }, "latest computed styles")
        waitForPhase(css, CssInspectionPhase.LOADED, { it.boxModelPhase }, "latest box model")
        assertEquals(div.nodeId, css.inspectionState.nodeId)

        // DIV box model differs from H1's (no explicit width → computed layout width).
        val box = css.inspectionState.boxModel!!
        assertTrue("DIV box model must have a width", box.width != null && box.width!! > 0)

        // Selection cleared resets everything to IDLE.
        inspector.clearSelection()
        waitForPhase(css, CssInspectionPhase.IDLE, { it.phase }, "selection cleared")
        waitForPhase(css, CssInspectionPhase.IDLE, { it.computedStylePhase }, "computed cleared")
        assertNull(css.inspectionState.computedStyle)

        inspector.detach()
        cm.disconnect()
    }
}