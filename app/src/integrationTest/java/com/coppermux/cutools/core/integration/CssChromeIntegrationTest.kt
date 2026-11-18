package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.css.CssCapabilityStatus
import com.coppermux.cutools.core.css.CssInspectionPhase
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
 * Phase 3A — CSS Foundation and Style Inspection real-Chrome validation.
 *
 * Proves against REAL Chrome:
 *  - CSS domain availability (`CSS.enable` succeeds → capability supported)
 *  - Selected-node matched CSS rule retrieval (`CSS.getMatchedStylesForNode`)
 *  - Inline style (style attribute), author rules, and user-agent rules
 *  - CSS declarations (name/value/important)
 *  - Stylesheet association where available
 *  - Rule source locations where available
 *
 * Uses genuine CDP CSS commands through the Phase 1 foundation (no second
 * WebSocket). Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class CssChromeIntegrationTest {

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

    private fun waitForCssPhase(css: CssInspector, phase: CssInspectionPhase, what: String) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.inspectionState.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("Expected CSS phase $phase: $what", phase, css.inspectionState.phase)
    }

    private fun waitForCapability(css: CssInspector, status: CssCapabilityStatus) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.capability != status && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(status, css.capability)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for: $what", condition())
    }

    /** Injects a canonical test page with an author stylesheet + inline style. */
    private fun injectTestPage(cm: ConnectionManager, session: SessionInfo) {
        // Remove leftover styles so the test is deterministic on the shared page.
        val expression = """
            document.head.querySelectorAll('style').forEach(function(st){ st.remove(); });
            var s = document.createElement('style'); s.id = 'test-style';
            s.textContent = 'h1 { color: blue; font-weight: bold; } #box { border: 1px solid black; } .hero { background: gold; }';
            document.head.appendChild(s);
            document.body.innerHTML = "<h1 id='main' class='hero' style='color: green'>Hello</h1><div id='box'>Box content</div>";
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
    fun cssInspection_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)

        // 1. CSS domain availability.
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        // 2. Inject a page with an author stylesheet and inline styles.
        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)

        // 3. Stylesheet association becomes available via styleSheetAdded events.
        waitFor("author stylesheet tracked") {
            css.inspectionState.stylesheets.any { it.origin == CssStyleSheetOrigin.REGULAR }
        }

        // 4. Load the element nodes.
        val h1 = waitForTag(inspector, "H1")
        val div = waitForTag(inspector, "DIV")
        assertNotNull("H1 must load", h1)
        assertNotNull("DIV must load", div)

        // 5. Select H1 → matched CSS rules + inline style.
        inspector.selectNode(h1!!.nodeId)
        waitForCssPhase(css, CssInspectionPhase.LOADED, "H1 styles")
        val h1State = css.inspectionState
        assertEquals(h1.nodeId, h1State.nodeId)
        assertNotNull(h1State.styles)

        // Inline style (style attribute).
        val inline = h1State.styles!!.inlineDeclarations
        assertTrue("inline style must contain color: green", inline.any { it.name == "color" && it.value == "green" })

        // Author rule 'h1' with declarations.
        val authorRules = h1State.styles!!.matchedRules.filter { it.origin == CssStyleSheetOrigin.REGULAR }
        assertTrue("H1 must have author rules", authorRules.isNotEmpty())
        val h1Rule = authorRules.firstOrNull { it.selectorText == "h1" }
        assertNotNull("H1 must match author rule 'h1'", h1Rule)
        assertTrue(
            "author rule 'h1' must declare color: blue",
            h1Rule!!.declarations.any { it.name == "color" && it.value == "blue" }
        )

        // User-agent styles where exposed.
        val uaRules = h1State.styles!!.matchedRules.filter { it.origin == CssStyleSheetOrigin.USER_AGENT }
        assertTrue("user-agent rules must be represented", uaRules.isNotEmpty())

        // Rule source location where available (author rules carry a range).
        assertTrue(
            "author rule must expose a source location",
            authorRules.any { it.sourceRange != null }
        )

        // Stylesheet association: the author rule's styleSheetId resolves to a
        // tracked stylesheet of author origin.
        val sheet = h1State.stylesheets.firstOrNull { it.styleSheetId == h1Rule.styleSheetId }
        assertNotNull("author rule must be associated with a tracked stylesheet", sheet)
        assertEquals(CssStyleSheetOrigin.REGULAR, sheet!!.origin)

        // 6. Select DIV → its own matched rule (#box).
        inspector.selectNode(div!!.nodeId)
        waitForCssPhase(css, CssInspectionPhase.LOADED, "DIV styles")
        val divState = css.inspectionState
        val divBoxRule = divState.styles?.matchedRules?.firstOrNull { it.selectorText.contains("#box") }
        assertNotNull("DIV must match author rule '#box'", divBoxRule)
        assertTrue(
            "author rule '#box' must declare border",
            divBoxRule!!.declarations.any { it.name == "border" }
        )

        inspector.detach()
        cm.disconnect()
    }

    @Test
    fun cssSelectionChange_againstRealChrome() {
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

        // Select H1 then immediately DIV; the eventual state must reflect DIV
        // (the newest selection), never the older H1 response.
        inspector.selectNode(h1!!.nodeId)
        inspector.selectNode(div!!.nodeId)
        waitForCssPhase(css, CssInspectionPhase.LOADED, "latest selection styles")
        assertEquals(div.nodeId, css.inspectionState.nodeId)
        assertNotNull(css.inspectionState.styles)

        // Clearing selection resets to IDLE.
        inspector.clearSelection()
        waitForCssPhase(css, CssInspectionPhase.IDLE, "selection cleared")
        assertNull(css.inspectionState.nodeId)

        inspector.detach()
        cm.disconnect()
    }
}